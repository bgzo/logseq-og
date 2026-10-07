(ns frontend.webdav.store.desktop
  "Desktop store: config/manifest under ~/.logseq-og/webdav/<graph-key>/,
   credentials encrypted via Electron safeStorage (main process)."
  (:require [cljs.reader :as reader]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.fs :as fs]
            [frontend.webdav.manifest :as manifest]
            [frontend.webdav.store :as store]
            [logseq.common.path :as path]
            [promesa.core :as p]))

(def default-config
  {:enabled false
   :url ""
   :remote-root ""
   :username ""
   :conflict-policy :prefer-local
   :interval 300
   :request-gap-ms 100
   :max-file-mb 100
   :allow-git-remote? false})

(defonce *dot-root (atom nil))

(defn- dot-root!
  []
  (if-let [r @*dot-root]
    (p/resolved r)
    (p/let [r (ipc/ipc :getLogseqDotDirRoot)]
      (reset! *dot-root r)
      r)))

(defn- key-of
  [repo]
  (-> (str (config/get-repo-dir repo))
      (string/replace "\\" "_")
      (string/replace "/" "_")
      (string/replace ":" "comma")))

(defn- dir-of
  [repo]
  (p/let [root (dot-root!)]
    (path/path-join root "webdav" (key-of repo))))

(defn- read-edn
  [dir file]
  (p/let [content (-> (fs/read-file dir file) (p/catch (fn [_] nil)))]
    (when (and content (not (string/blank? content)))
      (try
        (reader/read-string content)
        (catch :default _e
          nil)))))

(defn- write-edn-atomic!
  [dir file data]
  (p/let [_ (fs/mkdir-recur! dir)
          abs (path/path-join dir file)
          tmp (str abs ".tmp")
          _ (ipc/ipc :writeFile "" tmp (pr-str data))
          _ (ipc/ipc :rename tmp abs)]
    true))

(defrecord DesktopStore []
  store/Store
  (graph-key [_ repo] (key-of repo))

  (load-config [_ repo]
    (p/let [dir (dir-of repo)
            saved (read-edn dir "config.edn")]
      (merge default-config (or saved {}))))

  (save-config [_ repo config]
    (p/let [dir (dir-of repo)]
      (write-edn-atomic! dir "config.edn" config)))

  (load-manifest [_ repo]
    (p/let [dir (dir-of repo)
            content (-> (fs/read-file dir "manifest.edn") (p/catch (fn [_] nil)))
            parsed (when content (manifest/parse content))]
      (or parsed (manifest/blank))))

  (save-manifest [_ repo m]
    (p/let [dir (dir-of repo)]
      (write-edn-atomic! dir "manifest.edn" m)))

  (load-credentials [_ repo]
    (ipc/ipc :webdavCredentials {:op :load :graph-key (key-of repo)}))

  (save-credentials [_ repo creds]
    (ipc/ipc :webdavCredentials (merge {:op :save :graph-key (key-of repo)} creds))))

(defn make []
  (->DesktopStore))
