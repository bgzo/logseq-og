(ns frontend.webdav.ignore
  "Path and file-type rules for WebDAV sync. Pure logic, no platform deps."
  (:require [clojure.string :as string]))

(def ^:private ignored-prefixes
  ["logseq/bak" "logseq/version-files" "logseq/webdav"
   "logseq/.recycle" ".recycle" "node_modules"])

(def ^:private ignored-suffixes
  [".ds_store" "thumbs.db" "desktop.ini"])

(def ^:private ignored-regex
  #"(?i)\.(tmp|part|crdownload|icloud)$")

(defn hidden-segment?
  [segment]
  (string/starts-with? segment "."))

(defn ignored-path?
  "True when a graph-relative POSIX path must not be synced, parsed or watched.
   Mirrors the exclusions documented in docs/webdav-sync.md section 3.5."
  [relpath]
  (boolean
   (when (string? relpath)
     (let [p (string/replace relpath "\\" "/")]
       (or (string/blank? p)
           (some #(or (= p %) (string/starts-with? p (str % "/"))) ignored-prefixes)
           (some #(string/includes? p (str "/" % "/")) ignored-prefixes)
           (some (fn [segment] (hidden-segment? segment)) (string/split p #"/"))
           (some #(string/ends-with? (string/lower-case p) %) ignored-suffixes)
           (boolean (re-find ignored-regex p)))))))

(def ^:private text-extensions
  #{"md" "markdown" "org" "edn" "css" "js" "json" "txt" "html"
    "excalidraw" "tldr"})

(defn text-file?
  "True when the file should be synced as UTF-8 text, false means binary."
  [relpath]
  (let [ext (some-> (re-find #"\.([^./]+)$" (str relpath)) last string/lower-case)]
    (contains? text-extensions ext)))

(defn file-size-ok?
  [size max-file-mb]
  (or (nil? size)
      (nil? max-file-mb)
      (<= size (* max-file-mb 1024 1024))))

(defn external-artifact?
  "Detect files produced by other folder-sync tools. Used as a runtime warning
   for the single-sync-source policy (docs/webdav-sync.md section 3.6)."
  [relpath]
  (let [name (some-> (re-find #"[^/]+$" (str relpath)) string/lower-case)]
    (boolean
     (and name
          (or (string/ends-with? name ".icloud")
              (string/includes? name ".sync-conflict-")
              (string/includes? name "conflicted copy"))))))
