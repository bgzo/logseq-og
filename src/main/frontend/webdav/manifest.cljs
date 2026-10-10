(ns frontend.webdav.manifest
  "Manifest (per-device sync state) encoding and entry helpers.
   Pure data handling; storage lives in frontend.webdav.store."
  (:require [cljs.reader :as reader]))

(def current-version 1)

(defn now-iso []
  (.toISOString (js/Date.)))

(defn blank
  []
  {:version current-version
   :files {}})

(defn parse
  "Parse a manifest EDN string. Returns nil when unreadable."
  [s]
  (try
    (let [m (reader/read-string s)]
      (when (and (map? m) (map? (:files m)))
        (update m :version #(or % current-version))))
    (catch :default _e
      nil)))

(defn serialize
  [m]
  (pr-str m))

(defn entry
  [m path]
  (get-in m [:files path]))

(defn put-entry
  [m path e]
  (assoc-in m [:files path] e))

(defn remove-entry
  [m path]
  (update m :files dissoc path))

(defn synced-entry
  "Entry recorded after a successful upload/download."
  [local remote]
  {:local local
   :remote remote
   :state :synced
   :synced-at (now-iso)})

(defn with-state
  [e state]
  (assoc e :state state))

(defn local-meta
  "Normalize local file metadata from frontend.fs/stat results."
  [{:keys [mtime last-modified-at size]}]
  {:mtime (cond
            (number? mtime) mtime
            (number? last-modified-at) last-modified-at
            (some? mtime) (.getTime mtime)
            :else nil)
   :size size})

(defn remote-meta
  "Normalize remote metadata from the WebDAV client."
  [{:keys [etag size]}]
  {:etag etag
   :size size})

(defn local-changed?
  "True when local metadata differs from what the manifest recorded."
  [local-meta entry]
  (not= (select-keys local-meta [:mtime :size])
        (select-keys (:local entry) [:mtime :size])))

(defn remote-changed?
  "True when the remote etag differs from what the manifest recorded."
  [remote-meta entry]
  (not= (:etag remote-meta)
        (get-in entry [:remote :etag])))
