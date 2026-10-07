(ns frontend.webdav.engine
  "WebDAV sync engine: scan → plan → apply, one round per graph."
  (:require [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.webdav.client :as client]
            [frontend.webdav.coexist :as coexist]
            [frontend.webdav.ignore :as ignore]
            [frontend.webdav.io :as io]
            [frontend.webdav.manifest :as manifest]
            [frontend.webdav.plan :as plan]
            [frontend.webdav.scan :as scan]
            [frontend.webdav.store :as store]
            [promesa.core :as p]))

(defonce *running (atom #{}))

(defn running?
  [repo]
  (contains? @*running repo))

(defn base-url
  [{:keys [url remote-root]}]
  (let [base (-> (str url)
                 string/trim
                 (string/replace #"/+$" ""))]
    (client/child-url
     base
     (-> (str remote-root)
         string/trim
         (string/replace #"^/+" "")
         (string/replace #"/+$" "")))))

(defn- status!
  [deps repo data]
  (when (fn? (:on-status deps))
    ((:on-status deps) repo (assoc data :at (manifest/now-iso)))))

(defn- notify!
  [deps data]
  (when (fn? (:notify deps))
    ((:notify deps) data)))

(defn- timestamp
  []
  (-> (.toISOString (js/Date.))
      (string/replace #"[:.]" "-")))

(defn- conflict-backup-path
  [path]
  (str "logseq/webdav/conflicts/" (timestamp) "/" path))

(defn- read-content
  [repo path]
  (if (ignore/text-file? path)
    (io/read-text repo path)
    (io/read-base64 repo path)))

(defn- write-content!
  [repo path content binary?]
  (if binary?
    (io/write-base64! repo path content)
    (io/write-text! repo path content)))

(defn- upload-op!
  [repo client base ctx path]
  (let [binary? (not (ignore/text-file? path))]
    (p/let [content (read-content repo path)
            _ (client/ensure-parents! client base path (:created ctx))
            _ (client/put-file! client (client/child-url base path) content {:binary? binary?})
            prop (client/stat client (client/child-url base path))
            stat (io/stat repo path)]
      [path (manifest/local-meta stat) (manifest/remote-meta prop)])))

(defn- download-op!
  [repo client base path remote-meta]
  (let [binary? (not (ignore/text-file? path))]
    (p/let [resp (client/get-file client (client/child-url base path) {:binary? binary?})
            _ (when-not (= 200 (:status resp))
                (throw (ex-info "GET failed" {:status (:status resp) :path path})))
            _ (write-content! repo path (:body resp) binary?)
            stat (io/stat repo path)]
      [path (manifest/local-meta stat) (manifest/remote-meta remote-meta)])))

(defn- backup-remote!
  [repo client base path]
  (let [binary? (not (ignore/text-file? path))
        backup (conflict-backup-path path)]
    (p/let [resp (client/get-file client (client/child-url base path) {:binary? binary?})
            _ (when-not (= 200 (:status resp))
                (throw (ex-info "GET failed" {:status (:status resp) :path path})))
            _ (write-content! repo backup (:body resp) binary?)]
      backup)))

(defn- backup-local!
  [repo path]
  (let [binary? (not (ignore/text-file? path))
        backup (conflict-backup-path path)]
    (p/let [content (read-content repo path)
            _ (write-content! repo backup content binary?)]
      backup)))

(defn- apply-op!
  [repo client base deps {:keys [op path resolution remote]}]
  (case op
    :upload
    (upload-op! repo client base deps path)

    :download
    (download-op! repo client base path remote)

    :conflict
    (if (= resolution :remote)
      (p/let [backup (backup-local! repo path)
              entry (download-op! repo client base path remote)]
        (notify! deps {:type :conflict :path path :backup backup :resolution :remote})
        entry)
      (p/let [backup (backup-remote! repo client base path)
              entry (upload-op! repo client base deps path)]
        (notify! deps {:type :conflict :path path :backup backup :resolution :local})
        entry))

    (throw (ex-info "Unknown sync op" {:op op}))))

(defn- apply-ops-loop
  [repo client base deps manifest* ops applied errors]
  (if-let [op (first ops)]
    (-> (apply-op! repo client base deps op)
        (p/then (fn [[path local remote]]
                  (apply-ops-loop repo client base deps
                                  (manifest/put-entry manifest* path (manifest/synced-entry local remote))
                                  (rest ops)
                                  (update applied (:op op) (fnil inc 0))
                                  errors)))
        (p/catch (fn [e]
                   (apply-ops-loop repo client base deps
                                   manifest*
                                   (rest ops)
                                   applied
                                   (conj errors {:path (:path op) :error (str e)})))))
    (p/resolved [manifest* applied errors])))

(defn- apply-ops!
  [repo client base deps manifest {:keys [ops state-updates remove]}]
  (let [ctx (assoc deps :created (atom #{}))
        manifest* (reduce (fn [m [path {:keys [state]}]]
                            (update-in m [:files path] manifest/with-state state))
                          manifest
                          state-updates)
        manifest* (reduce manifest/remove-entry manifest* remove)]
    (apply-ops-loop repo client base ctx manifest* ops {} [])))

(defn- with-lock
  [repo deps f]
  (p/let [graph-dir (io/graph-dir repo)
          acquired (ipc/ipc :webdavSyncLock {:op :acquire :graph-dir graph-dir})]
    (if acquired
      (-> (f)
          (p/finally (fn []
                       (ipc/ipc :webdavSyncLock {:op :release :graph-dir graph-dir}))))
      (do (status! deps repo {:phase :locked})
          (p/resolved :locked)))))

(defn- sync-inner!
  [repo {:keys [store http parse-xml] :as deps}]
  (status! deps repo {:phase :checking})
  (p/let [config (store/load-config store repo)]
    (if-not (:enabled config)
      (p/resolved :disabled)
      (p/let [creds (store/load-credentials store repo)
              _ (when-not (and (not (string/blank? (:username config)))
                               (not (string/blank? (:password creds))))
                  (throw (ex-info "WebDAV credentials are missing"
                                  {:reason :unauthenticated})))
              policy (coexist/check repo config)]
        (if-not (:ok policy)
          (do (status! deps repo {:phase :blocked
                                  :reason (:reason policy)
                                  :detail (:detail policy)})
              (p/resolved :blocked))
          (with-lock
            repo deps
            (fn []
              (p/let [manifest* (store/load-manifest store repo)
                      base (base-url config)
                      client (client/make-client http {:parse-xml parse-xml
                                                       :gap-ms (:request-gap-ms config 100)})
                      _ (status! deps repo {:phase :scanning})
                      local (scan/list-local repo {:max-file-mb (:max-file-mb config)})
                      _ (when (seq (:external-artifacts local))
                          (throw (ex-info "Files from another folder-sync tool detected"
                                          {:reason :external-artifacts
                                           :artifacts (vec (take 5 (:external-artifacts local)))})))
                      _ (client/mkcol! client base)
                      remote (client/list-tree client base {})
                      plan* (plan/plan {:manifest manifest*
                                        :local (:files local)
                                        :remote remote
                                        :conflict-policy (:conflict-policy config)})
                      _ (status! deps repo {:phase :applying
                                            :total (count (:ops plan*))})
                      [manifest' applied errors] (apply-ops! repo client base deps manifest* plan*)
                      _ (store/save-manifest store repo manifest')
                      result {:uploads (get applied :upload 0)
                              :downloads (get applied :download 0)
                              :conflicts (get applied :conflict 0)
                              :errors errors}
                      _ (status! deps repo (merge {:phase :done} result))]
                  result))))))))

(defn sync!
  "Run one sync round for a graph. Returns a promise resolving to a result map,
   or to :disabled/:blocked/:locked/:running."
  [repo deps]
  (if (running? repo)
    (p/resolved :running)
    (do
      (swap! *running conj repo)
      (-> (sync-inner! repo deps)
          (p/catch (fn [e]
                     (status! deps repo (merge {:phase :error
                                                :error (str e)}
                                               (select-keys (ex-data e) [:reason :artifacts])))
                     (throw e)))
          (p/finally (fn []
                       (swap! *running disj repo)))))))
