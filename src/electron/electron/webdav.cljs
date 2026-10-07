(ns electron.webdav
  "Main-process helpers for WebDAV sync: no-CORS HTTP bridge, encrypted
   credential storage and a per-graph sync lock."
  (:require ["electron" :refer [safeStorage]]
            ["fs-extra" :as fs]
            ["path" :as node-path]
            [clojure.string :as string]
            [electron.logger :as logger]
            [electron.utils :as utils]
            [promesa.core :as p]))

(def log-error (partial logger/error "[WebDAV]"))

(defn- webdav-root
  []
  (node-path/join (utils/get-ls-dotdir-root) "webdav"))

(defn- graph-dir
  [graph-key]
  (let [dir (node-path/join (webdav-root) graph-key)]
    (fs/ensureDirSync dir)
    dir))

;; HTTP bridge

(defn fetch!
  "Send an HTTP request from the main process. `opts` mirrors the renderer
   adapter payload; uses node-fetch and the same proxy agent as the app."
  [{:keys [url method headers body body-base64? return-type timeout]}]
  (let [body* (when (some? body)
                (if body-base64?
                  (js/Buffer.from body "base64")
                  body))
        opts (cond-> {:method (keyword (string/upper-case (or method "GET")))
                      :headers (or headers {})
                      :timeout (or timeout 30000)
                      :agent @utils/*fetchAgent}
               (some? body*) (assoc :body body*))]
    (p/let [res (utils/_fetch url (clj->js opts))
            res-body (case (or return-type :text)
                       :base64 (-> (.buffer res)
                                   (p/then (fn [buf] (.toString buf "base64"))))
                       :text (.text res))]
      {:status (.-status res)
       :headers (js->clj (js/Object.fromEntries (.entries (.-headers res))))
       :body res-body})))

;; Credentials

(defn- credentials-file
  [graph-key]
  (node-path/join (graph-dir graph-key) "credentials.json"))

(def ^:private encryption-available?
  (fn [] (boolean (and safeStorage (.isEncryptionAvailable safeStorage)))))

(defn load-credentials!
  [graph-key]
  (try
    (let [file (credentials-file graph-key)]
      (when (fs/existsSync file)
        (let [payload (js/JSON.parse (.toString (fs/readFileSync file)))
              data (js/Buffer.from (.-data payload) "base64")
              json (if (.-encrypted payload)
                     (safeStorage.decryptString data)
                     (.toString data "utf8"))]
          (js->clj (js/JSON.parse json) :keywordize-keys true))))
    (catch :default e
      (log-error "load credentials failed:" e)
      nil)))

(defn save-credentials!
  [graph-key {:keys [username password]}]
  (let [file (credentials-file graph-key)
        plain (js/JSON.stringify #js {:username (or username "")
                                     :password (or password "")})
        encrypted? (encryption-available?)
        data (if encrypted?
               (.toString (safeStorage.encryptString plain) "base64")
               (.toString (js/Buffer.from plain "utf8") "base64"))]
    (fs/writeFileSync file (js/JSON.stringify #js {:encrypted encrypted?
                                                   :data data}))
    (try (fs/chmodSync file "600") (catch :default _e nil))
    {:encrypted encrypted?}))

(defn clear-credentials!
  [graph-key]
  (let [file (credentials-file graph-key)]
    (when (fs/existsSync file)
      (fs/unlinkSync file))
    true))

(defn encryption-available []
  (encryption-available?))

;; Sync lock

(defonce *locks (atom {}))

(def ^:private lock-ttl-ms (* 2 60 1000))

(defn acquire-lock!
  [^js window graph-dir]
  (let [now (.now js/Date)
        owner (.-id window)
        cur (get @*locks graph-dir)]
    (if (or (nil? cur)
            (= (:owner cur) owner)
            (> (- now (:ts cur)) lock-ttl-ms))
      (do (swap! *locks assoc graph-dir {:owner owner :ts now})
          true)
      false)))

(defn refresh-lock!
  [^js window graph-dir]
  (let [owner (.-id window)
        cur (get @*locks graph-dir)]
    (when (= (:owner cur) owner)
      (swap! *locks assoc graph-dir {:owner owner :ts (.now js/Date)}))
    (= (:owner cur) owner)))

(defn release-lock!
  [^js window graph-dir]
  (let [owner (.-id window)
        cur (get @*locks graph-dir)]
    (when (or (nil? cur) (= (:owner cur) owner))
      (swap! *locks dissoc graph-dir))
    true))
