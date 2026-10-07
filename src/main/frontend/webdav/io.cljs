(ns frontend.webdav.io
  "Graph file IO for the sync engine. Desktop implementation (Electron IPC);
   the mobile adapter lands in M3."
  (:require [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.fs :as fs]
            [goog.crypt.base64 :as base64]
            [logseq.common.path :as path]
            [promesa.core :as p]))

(defn graph-dir
  [repo]
  (config/get-repo-dir repo))

(defn abs-path
  [repo rel]
  (path/path-join (graph-dir repo) rel))

(defn read-text
  [repo rel]
  (fs/read-file (graph-dir repo) rel))

(defn read-base64
  [repo rel]
  (ipc/ipc :readFileBase64 (abs-path repo rel)))

(defn read-abs-text
  [abs]
  (ipc/ipc :readFile abs))

(defn stat
  [repo rel]
  (fs/stat (graph-dir repo) rel))

(defn path-exists?
  [repo rel]
  (-> (p/catch (p/let [_ (fs/stat (graph-dir repo) rel)] true)
               (fn [_] false))))

(defn ensure-local-parents!
  [repo rel]
  (let [parent (path/parent (abs-path repo rel))]
    (when (and parent (not= parent (graph-dir repo)))
      (fs/mkdir-recur! parent))))

(defn- write-bytes-atomic!
  [repo rel content]
  (p/let [_ (ensure-local-parents! repo rel)
          abs (abs-path repo rel)
          tmp (str abs ".webdav-tmp-" (.now js/Date))
          _ (ipc/ipc :writeFile repo tmp content)
          _ (ipc/ipc :rename tmp abs)]
    true))

(defn write-text!
  [repo rel content]
  (write-bytes-atomic! repo rel content))

(defn write-base64!
  [repo rel b64]
  (write-bytes-atomic! repo rel (base64/decodeStringToUint8Array b64)))
