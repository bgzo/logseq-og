(ns frontend.webdav.io
  "Graph file IO for the sync engine. Desktop uses Electron IPC (atomic
   temp+rename); mobile uses Capacitor Filesystem (recursive writes)."
  (:require ["@capacitor/filesystem" :refer [Encoding Filesystem]]
            [cljs-bean.core :as bean]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.fs :as fs]
            [frontend.mobile.util :as mobile-util]
            [goog.crypt.base64 :as base64]
            [logseq.common.path :as path]
            [promesa.core :as p]))

(defonce ^js mobile-utf8-encoding (.-UTF8 Encoding))

(defn- mobile? []
  (mobile-util/native-platform?))

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
  (if (mobile?)
    (-> (.readFile Filesystem #js {:path (abs-path repo rel)})
        (p/then (fn [res] (:data (bean/->clj res))))
        (p/catch (fn [e]
                   (throw (ex-info (str "Failed to read " rel ": " e) {})))))
    (ipc/ipc :readFileBase64 (abs-path repo rel))))

(defn read-abs-text
  [abs]
  (ipc/ipc :readFile abs))

(defn stat
  [repo rel]
  (fs/stat (graph-dir repo) rel))

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
  (if (mobile?)
    (-> (.writeFile Filesystem #js {:path (abs-path repo rel)
                                    :data content
                                    :encoding mobile-utf8-encoding
                                    :recursive true})
        (p/then (fn [_] true))
        (p/catch (fn [e]
                   (throw (ex-info (str "Failed to write " rel ": " e) {})))))
    (write-bytes-atomic! repo rel content)))

(defn write-base64!
  [repo rel b64]
  (if (mobile?)
    (-> (.writeFile Filesystem #js {:path (abs-path repo rel)
                                    :data b64
                                    :recursive true})
        (p/then (fn [_] true))
        (p/catch (fn [e]
                   (throw (ex-info (str "Failed to write " rel ": " e) {})))))
    (write-bytes-atomic! repo rel (base64/decodeStringToUint8Array b64))))
