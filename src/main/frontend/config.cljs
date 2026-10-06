(ns frontend.config
  "App config and fns built on top of configuration"
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [frontend.mobile.util :as mobile-util]
            [frontend.state :as state]
            [frontend.util :as util]
            [logseq.common.path :as path]
            [logseq.graph-parser.config :as gp-config]
            [shadow.resource :as rc]))

(goog-define DEV-RELEASE false)
(defonce dev-release? DEV-RELEASE)
(defonce dev? ^boolean (or dev-release? goog.DEBUG))

(goog-define PUBLISHING false)
(defonce publishing? PUBLISHING)

(goog-define REVISION "unknown")
(defonce revision REVISION)

(reset! state/publishing? publishing?)

(goog-define TEST false)
(def test? TEST)

;; Feature flags
;; =============

(goog-define ENABLE-PLUGINS true)
(defonce feature-plugin-system-on? ENABLE-PLUGINS)

;; Desktop only as other platforms requires better understanding of their
;; multi-graph workflows and optimal place for a "global" dir
(def global-config-enabled? util/electron?)

;; User level configuration for whether plugins are enabled
(defonce lsp-enabled?
         (and (util/electron?)
              (not (false? feature-plugin-system-on?))
              (state/lsp-enabled?-or-theme)))

(defn plugin-config-enabled?
  []
  (and lsp-enabled? (global-config-enabled?)))

;; :TODO: How to do this?
;; (defonce desktop? ^boolean goog.DESKTOP)

;; ============

(def app-name "logseq")
(def website
  (if dev?
    "http://localhost:3000"
    (util/format "https://%s.com" app-name)))

(def asset-domain (util/format "https://asset.%s.com"
                               app-name))

;; TODO: Remove this, switch to lazy loader
(defn asset-uri
  [path]
  (cond
    publishing?
    path

    (util/file-protocol?)
    (string/replace path "/static/" "./")

    :else
    (if dev? path
        (str asset-domain path))))

(def markup-formats
  #{:org :md :markdown :asciidoc :adoc :rst})

(def doc-formats
  #{:doc :docx :xls :xlsx :ppt :pptx :one :pdf :epub})

(def image-formats
  #{:png :jpg :jpeg :bmp :gif :webp :svg :heic})

(def audio-formats
  #{:mp3 :ogg :mpeg :wav :m4a :flac :wma :aac})

(def video-formats
  #{:mp4 :webm :mov :flv :avi :mkv})

(def media-formats (set/union (gp-config/img-formats) audio-formats video-formats))

(defn extname-of-supported?
  ([input] (extname-of-supported?
            input
            [image-formats doc-formats audio-formats
             video-formats markup-formats
             (gp-config/text-formats)]))
  ([input formats]
   (when-let [input (some->
                     (cond-> input
                       (and (string? input)
                            (not (string/blank? input)))
                       (string/replace-first "." ""))
                     (util/safe-lower-case)
                     (keyword))]
     (boolean
       (some
         (fn [s]
           (contains? s input))
         formats)))))

(defn ext-of-video?
  ([s] (ext-of-video? s true))
  ([s html5?]
   (when-let [s (and (string? s) (util/get-file-ext s))]
     (let [video-formats (cond-> video-formats
                                 html5? (disj :mkv))]
       (extname-of-supported? s [video-formats])))))

(defn ext-of-audio?
  ([s] (ext-of-audio? s true))
  ([s html5?]
   (when-let [s (and (string? s) (util/get-file-ext s))]
     (let [audio-formats (cond-> audio-formats
                                 html5? (disj :wma :ogg))]
       (extname-of-supported? s [audio-formats])))))

(defn ext-of-image?
  [s]
  (when-let [s (and (string? s) (util/get-file-ext s))]
    (extname-of-supported? s [image-formats])))

(def mobile?
  "Triggering condition: Mobile phones
   *** Warning!!! ***
   For UX logic only! Don't use for FS logic
   iPad / Android Pad doesn't trigger!

   Same as config/mobile?"
  (when-not util/node-test?
    (util/safe-re-find #"Mobi" js/navigator.userAgent)))

;; TODO: protocol design for future formats support

(defn get-block-pattern
  [format]
  (gp-config/get-block-pattern (or format (state/get-preferred-format))))

(defn get-hr
  [format]
  (let [format (or format (keyword (state/get-preferred-format)))]
    (case format
      :org
      "-----"
      :markdown
      "---"
      "")))

(defn get-bold
  [format]
  (let [format (or format (keyword (state/get-preferred-format)))]
    (case format
      :org
      "*"
      :markdown
      "**"
      "")))

(defn get-italic
  [format]
  (let [format (or format (keyword (state/get-preferred-format)))]
    (case format
      :org
      "/"
      :markdown
      "*"
      "")))
(defn get-underline
  [format]
  (let [format (or format (keyword (state/get-preferred-format)))]
    (case format
      :org
      "_"
      :markdown ;; no underline for markdown
      ""
      "")))
(defn get-strike-through
  [format]
  (let [format (or format (keyword (state/get-preferred-format)))]
    (case format
      :org
      "+"
      :markdown
      "~~"
      "")))

(defn get-highlight
  [format]
  (case format
    :org
    "^^"
    :markdown
    "=="
    ""))

(defn get-code
  [format]
  (let [format (or format (keyword (state/get-preferred-format)))]
    (case format
      :org
      "~"
      :markdown
      "`"
      "")))

(defn get-empty-link-and-forward-pos
  [format]
  (case format
    :org
    ["[[][]]" 2]
    :markdown
    ["[]()" 1]
    ["" 0]))

(defn link-format
  [format label link]
  (if (not-empty label)
    (case format
      :org
      (util/format "[[%s][%s]]" link label)
      :markdown
      (util/format "[%s](%s)" label link))
    link))

(defn with-default-link
  [format link]
  (case format
    :org
    [(util/format "[[%s][]]" link)
     (+ 4 (count link))]
    :markdown
    [(util/format "[](%s)" link)
     1]
    ["" 0]))

(defn with-label-link
  [format label link]
  (case format
    :org
    [(util/format "[[%s][%s]]" link label)
     (+ 4 (count link) (count label))]
    :markdown
    [(util/format "[%s](%s)" label link)
     (+ 4 (count link) (count label))]
    ["" 0]))

(defn with-default-label
  [format label]
  (case format
    :org
    [(util/format "[[][%s]]" label)
     2]
    :markdown
    [(util/format "[%s]()" label)
     (+ 3 (count label))]
    ["" 0]))

(defn properties-wrapper-pattern
  [format]
  (case format
    :markdown
    "---\n%s\n---"
    "%s"))

(defn get-file-extension
  [format]
  (case (keyword format)
    :markdown
    "md"
    (name format)))

(defonce default-journals-directory "journals")
(defonce default-pages-directory "pages")
(defonce default-whiteboards-directory "whiteboards")

(defn get-pages-directory
  []
  (or (state/get-pages-directory) default-pages-directory))

(defn get-journals-directory
  []
  (or (state/get-journals-directory) default-journals-directory))

(defn get-whiteboards-directory
  []
  (or (state/get-whiteboards-directory) default-whiteboards-directory))

(defonce local-repo "local")

(defn demo-graph?
  "Demo graph or nil graph?"
  ([]
   (demo-graph? (state/get-current-repo)))
  ([repo-url]
   (or (nil? repo-url) (= repo-url local-repo))))

(defonce recycle-dir ".recycle")
(def config-file "config.edn")
(def custom-css-file "custom.css")
(def export-css-file "export.css")
(def custom-js-file "custom.js")
(def config-default-content (rc/inline "templates/config.edn"))

;; NOTE: repo-url is the unique identifier of a repo.
;; - `local` => in-memory demo graph
;; - `logseq_local_/absolute/path/to/graph` => local graph, native fs backend
;; - `logseq_local_x:/absolute/path/to/graph` => local graph, native fs backend, on Windows
;; - `logseq_local_GraphName` => local graph, browser fs backend
;; - Use `""` while writing global files

(defonce idb-db-prefix "logseq-db/")
(defonce local-db-prefix "logseq_local_")
(defonce local-handle "handle")

(defn local-db?
  [s]
  (and (string? s)
       (string/starts-with? s local-db-prefix)))

(defn get-local-asset-absolute-path
  [s]
  (str "/" (string/replace s #"^[./]*" "")))

(defn get-local-dir
  [s]
  (string/replace s local-db-prefix ""))

;; FIXME(andelf): this is not the reverse op of get-repo-dir, should be fixed
(defn get-local-repo
  [dir]
  (str local-db-prefix dir))

(defn get-repo-dir
  [repo-url]
  (cond
    (nil? repo-url)
    (do
      (js/console.error "BUG: nil repo")
      nil)

    (and (util/electron?) (local-db? repo-url))
    (get-local-dir repo-url)

    (and (mobile-util/native-platform?) (local-db? repo-url))
    (let [dir (get-local-dir repo-url)]
      (if (string/starts-with? dir "file://")
        dir
        (path/path-join "file://" dir)))

    ;; Special handling for demo graph
    (= repo-url "local")
    "memory:///local"

    ;; nfs, browser-fs-access
    ;; Format: logseq_local_{dir-name}
    (local-db? repo-url)
    (string/replace-first repo-url local-db-prefix "")

    ;; unit test
    (= repo-url "test-db")
    "/test-db"

    :else
    (do
      (js/console.error "BUG: This should be unreachable! get-repo-dir" repo-url)
      (str "/"
           (->> (take-last 2 (string/split repo-url #"/"))
                (string/join "_"))))))

(defn get-repo-fpath
  [repo-url path]
  (path/path-join (get-repo-dir repo-url) path))

(defn get-repo-config-path
  []
  (path/path-join app-name config-file))

(defn get-custom-css-path
  ([]
   (get-custom-css-path (state/get-current-repo)))
  ([repo]
   (when-let [repo-dir (get-repo-dir repo)]
     (path/path-join repo-dir app-name custom-css-file))))

(defn get-export-css-path
  ([]
   (get-export-css-path (state/get-current-repo)))
  ([repo]
   (when-let [repo-dir (get-repo-dir repo)]
     (path/path-join repo-dir app-name  export-css-file))))

(defn expand-relative-assets-path
  "Resolve all relative links in custom.css to assets:// URL"
  ;; ../assets/xxx -> {assets|file}://{current-graph-root-path}/xxx
  [source]
  (when-not (string/blank? source)
    (let [protocol (and (string? source)
                        (not (string/blank? source))
                        (if (util/electron?) "assets://" "file://"))
          ;; BUG: use "assets" as fake current directory
          assets-link-fn (fn [_]
                           (let [graph-root (get-repo-dir (state/get-current-repo))
                                 protocol (if (string/starts-with? graph-root "file:") "" protocol)
                                 full-path (path/path-join protocol graph-root "assets")]
                             (str (cond-> full-path
                                          (mobile-util/native-platform?)
                                          (mobile-util/convert-file-src))
                                  "/")))]
      (string/replace source #"\.\./assets/" assets-link-fn))))

(defn get-current-repo-assets-root
  []
  (when-let [repo-dir (and (local-db? (state/get-current-repo))
                           (get-repo-dir (state/get-current-repo)))]
    (path/path-join repo-dir "assets")))

(defn get-custom-js-path
  ([]
   (get-custom-js-path (state/get-current-repo)))
  ([repo]
   (when-let [repo-dir (get-repo-dir repo)]
     (path/path-join repo-dir app-name custom-js-file))))

(defn get-block-hidden-properties
  []
  (:block-hidden-properties (state/get-config)))
