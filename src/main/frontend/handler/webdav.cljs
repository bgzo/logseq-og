(ns frontend.handler.webdav
  "UI-facing WebDAV sync handler: config, credentials, status and triggers."
  (:require [clojure.string :as string]
            [frontend.handler.notification :as notification]
            [frontend.state :as state]
            [frontend.util :as util]
            [frontend.webdav.client :as client]
            [frontend.webdav.engine :as engine]
            [frontend.webdav.http.desktop :as desktop-http]
            [frontend.webdav.store :as store]
            [frontend.webdav.store.desktop :as desktop-store]
            [frontend.webdav.xml :as xml]
            [promesa.core :as p]))

(defonce store-impl (desktop-store/make))
(defonce http-impl (desktop-http/make))
(defonce *last-sync (atom {}))
(defonce *scheduler (atom nil))

(defn get-config
  [repo]
  (store/load-config store-impl repo))

(defn save-config!
  [repo config]
  (store/save-config store-impl repo config))

(defn load-credentials
  [repo]
  (store/load-credentials store-impl repo))

(defn save-credentials!
  [repo creds]
  (store/save-credentials store-impl repo creds))

(defn- set-status!
  [repo status]
  (state/set-state! [:webdav/status repo] status))

(defn- notify!
  [{:keys [type path backup resolution]}]
  (when (= type :conflict)
    (notification/show!
     [:div
      [:p (str "WebDAV sync conflict: " path)]
      [:p.text-sm.opacity-70
       (if (= resolution :remote)
         (str "Remote version kept; your local version was saved to " backup)
         (str "Local version kept; the remote version was saved to " backup))]]
     :warning
     false)))

(defn sync-now!
  "Run one sync round for the current graph.
   Options: {:silent? false} suppresses the summary notification."
  ([repo] (sync-now! repo {}))
  ([repo {:keys [silent?] :or {silent? false}}]
   (if (engine/running? repo)
     (p/resolved :running)
     (-> (engine/sync! repo {:store store-impl
                             :http http-impl
                             :parse-xml xml/parse
                             :on-status set-status!
                             :notify notify!})
         (p/then (fn [result]
                   (when (map? result)
                     (swap! *last-sync assoc repo (.now js/Date))
                     (when (and (not silent?)
                                (or (pos? (+ (:uploads result 0)
                                             (:downloads result 0)
                                             (:conflicts result 0)))
                                    (seq (:errors result))))
                       (notification/show!
                        (str "WebDAV sync: " (:uploads result 0) " up, "
                             (:downloads result 0) " down, "
                             (:conflicts result 0) " conflicts"
                             (when (seq (:errors result))
                               (str ", " (count (:errors result)) " errors")))
                        (if (seq (:errors result)) :warning :success)
                        false)))
                   result))
         (p/catch (fn [e]
                    ;; the engine recorded the failure status already
                    (js/console.error "WebDAV sync failed:" (or (.-stack e) (str e)))
                    (p/resolved {:error (str e) :data (ex-data e)})))))))

(defn test-connection!
  "PROPFIND the configured remote root. Resolves true/false."
  [repo]
  (p/let [config (get-config repo)
          creds (load-credentials repo)]
    (if (or (string/blank? (:url config))
            (string/blank? (:username config))
            (string/blank? (:password creds)))
      (p/resolved {:ok false :status nil :reason :missing-credentials})
      (let [base (engine/base-url config)
            c (client/make-client http-impl {:parse-xml xml/parse
                                             :gap-ms (:request-gap-ms config 100)
                                             :username (:username config)
                                             :password (:password creds)})]
        (-> (client/test-connection! c base)
            (p/catch (fn [e]
                       {:ok false
                        :status nil
                        :error (str e)})))))))

(defonce scheduler-interval-ms 30000)

(defn- due?
  [repo config]
  (let [last-sync (get @*last-sync repo 0)
        interval (* 1000 (or (:interval config) 300))]
    (>= (- (.now js/Date) last-sync) interval)))

(defn- enabled-and-due?
  [repo]
  (-> (get-config repo)
      (p/then (fn [config]
                (and (:enabled config) (due? repo config))))
      (p/catch (fn [_] false))))

(defn start-scheduler!
  []
  (when (and (util/electron?) (nil? @*scheduler))
    (reset! *scheduler
            (js/setInterval
             (fn []
               (when-let [repo (state/get-current-repo)]
                 (when-not (engine/running? repo)
                   (-> (enabled-and-due? repo)
                       (p/then (fn [due?]
                                 (when due?
                                   (sync-now! repo {:silent? true}))))
                       (p/catch (fn [_]))))))
             scheduler-interval-ms))))

(defonce *last-trigger (atom {}))

(defn on-graph-ready!
  "Called after a graph finished loading: schedule a pull shortly after.
   Deduplicated because both :graph/added and :graph/ready can fire."
  [repo]
  (when (and (util/electron?) (not util/node-test?) repo)
    (let [now (.now js/Date)]
      (when (> (- now (get @*last-trigger repo 0)) 10000)
        (swap! *last-trigger assoc repo now)
        (start-scheduler!)
        (js/setTimeout
         (fn []
           (-> (get-config repo)
               (p/then (fn [config]
                         (when (:enabled config)
                           (sync-now! repo {:silent? true}))))
               (p/catch (fn [_]))))
         3000)))))
