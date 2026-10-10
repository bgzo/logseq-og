(ns frontend.webdav.store.mobile
  "Mobile store: config/manifest/credentials under the app-private
   Directory.Data/webdav/<graph-key>/ directory (Capacitor Filesystem).

   Credentials are stored as a plain JSON file inside the app sandbox. This is
   equivalent in reachability to @capacitor/preferences; a Keychain/Keystore
   plugin is the planned hardening, see docs/webdav-sync.md section 6.2."
  (:require ["@capacitor/filesystem" :refer [Directory Encoding Filesystem]]
            [cljs-bean.core :as bean]
            [cljs.reader :as reader]
            [clojure.string :as string]
            [frontend.config :as config]
            [frontend.webdav.manifest :as manifest]
            [frontend.webdav.store :as store]
            [goog.crypt :as crypt]
            [promesa.core :as p]))

(defonce ^js data-directory (.-Data Directory))
(defonce ^js utf8-encoding (.-UTF8 Encoding))

(defn- hash-str
  [s]
  (let [hasher (goog.crypt.Sha256.)]
    (.update hasher (crypt/stringToUtf8ByteArray s))
    (-> (crypt/byteArrayToHex (.digest hasher))
        (subs 0 16))))

(defn- key-of
  [repo]
  (let [dir (str (config/get-repo-dir repo))
        safe (string/replace dir #"[^a-zA-Z0-9._-]" "_")
        safe (subs safe 0 (min 60 (count safe)))]
    (str safe "-" (hash-str dir))))

(defn- path-of
  [repo file]
  (str "webdav/" (key-of repo) "/" file))

(defn- read-file
  [path]
  (-> (.readFile Filesystem #js {:path path
                                 :directory data-directory
                                 :encoding utf8-encoding})
      (p/then (fn [res] (:data (bean/->clj res))))
      (p/catch (fn [_] nil))))

(defn- write-file!
  [path content]
  (-> (.writeFile Filesystem #js {:path path
                                  :data content
                                  :directory data-directory
                                  :encoding utf8-encoding
                                  :recursive true})
      (p/then (fn [_] true))
      (p/catch (fn [e]
                 (throw (ex-info (str "Failed to write " path ": " e) {}))))))

(defrecord MobileStore []
  store/Store
  (graph-key [_ repo] (key-of repo))

  (load-config [_ repo]
    (p/let [content (read-file (path-of repo "config.edn"))
            saved (when (and content (not (string/blank? content)))
                    (try (reader/read-string content) (catch :default _ nil)))]
      (merge store/default-config (or saved {}))))

  (save-config [_ repo config]
    (write-file! (path-of repo "config.edn") (pr-str config)))

  (load-manifest [_ repo]
    (p/let [content (read-file (path-of repo "manifest.edn"))
            parsed (when content (manifest/parse content))]
      (or parsed (manifest/blank))))

  (save-manifest [_ repo m]
    (write-file! (path-of repo "manifest.edn") (pr-str m)))

  (load-credentials [_ repo]
    (p/let [content (read-file (path-of repo "credentials.json"))]
      (when (and content (not (string/blank? content)))
        (try
          (js->clj (js/JSON.parse content) :keywordize-keys true)
          (catch :default _ nil)))))

  (save-credentials [_ repo creds]
    (write-file! (path-of repo "credentials.json")
                 (js/JSON.stringify #js {:username (or (:username creds) "")
                                         :password (or (:password creds) "")}))))

(defn make []
  (->MobileStore))
