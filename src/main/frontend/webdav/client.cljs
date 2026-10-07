(ns frontend.webdav.client
  "WebDAV protocol client (PROPFIND/GET/PUT/MKCOL) for the sync engine.

   Platform access goes through frontend.webdav.http/Http; XML parsing is
   injected so the same code runs in the app and in Node tests."
  (:require [clojure.string :as string]
            [frontend.webdav.http :as http]
            [frontend.webdav.ignore :as ignore]
            [frontend.webdav.xml :as xml]
            [promesa.core :as p]))

(def propfind-body
  "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getetag/><d:getlastmodified/><d:getcontentlength/></d:prop></d:propfind>")

(defn make-client
  "http: an Http implementation
   opts: {:parse-xml fn, :gap-ms ms between requests, :log fn}"
  [http-impl {:keys [parse-xml gap-ms log] :or {gap-ms 100}}]
  {:http http-impl
   :parse-xml (or parse-xml xml/parse)
   :gap-ms gap-ms
   :log log
   :state (atom {:last-request 0})})

(defn- throttle!
  [client]
  (let [now (js/Date.now)
        last-request (:last-request @(:state client))
        wait (- (:gap-ms client) (- now last-request))]
    (if (pos? wait)
      (p/delay wait)
      (p/resolved nil))))

(defn- send!
  [client opts]
  (-> (throttle! client)
      (p/then (fn [_]
                (swap! (:state client) assoc :last-request (js/Date.now))
                (http/request (:http client) opts)))))

(defn encode-path-segment
  [segment]
  (js/encodeURIComponent segment))

(defn child-url
  "Join the WebDAV base URL with a graph-relative POSIX path."
  [base rel]
  (let [base (string/replace base #"/+$" "")]
    (if (string/blank? rel)
      base
      (str base
           "/"
           #_{:clj-kondo/ignore [:path-invalid-construct/string-join]}
           (string/join "/" (map encode-path-segment (string/split rel #"/")))))))

(defn- pathname
  [s]
  (try
    (.-pathname (js/URL. s "http://localhost"))
    (catch :default _e
      nil)))

(defn href->rel
  "Convert a multistatus href to a graph-relative path, or nil when the href is
   outside the configured remote root. Handles server-absolute hrefs and
   percent-encoding (case-insensitive)."
  [base href]
  (when (and base href)
    (let [b (some-> (pathname base) (string/replace #"/+$" ""))
          h (some-> (pathname href) (string/replace #"/+$" ""))]
      (when (and b h)
        (cond
          (= h b) ""
          (string/starts-with? h (str b "/")) (js/decodeURIComponent (subs h (inc (count b))))
          :else nil)))))

(defn- propstat-prop
  [response]
  (some (fn [ps]
          (let [status (some-> (xml/first-child ps "status") xml/text-content)]
            (when (and status (string/includes? status " 200"))
              (xml/first-child ps "prop"))))
        (xml/child-elements response "propstat")))

(defn- parse-prop
  [prop]
  (let [etag (some-> (xml/first-child prop "getetag") xml/text-content)
        last-modified (some-> (xml/first-child prop "getlastmodified") xml/text-content)
        size (some-> (xml/first-child prop "getcontentlength") xml/text-content)
        resourcetype (xml/first-child prop "resourcetype")]
    {:etag (when-not (string/blank? etag) etag)
     :last-modified (when-not (string/blank? last-modified) last-modified)
     :size (when (and size (not (string/blank? size))) (js/parseInt size 10))
     :collection? (boolean (and resourcetype
                                (seq (xml/child-elements resourcetype "collection"))))}))

(defn parse-multistatus
  "Parse a 207 response into [{:href .. :prop {..}|nil}]"
  [client s]
  (let [doc ((:parse-xml client) s)
        root (.-documentElement doc)]
    (->> (xml/child-elements root "response")
         (mapv (fn [response]
                 (let [href (some-> (xml/first-child response "href") xml/text-content)
                       prop (propstat-prop response)]
                   {:href href
                    :prop (when prop (parse-prop prop))}))))))

(defn- check-status!
  [client {:keys [method status url]} expected]
  (when-not (contains? expected status)
    (let [e (ex-info (str "WebDAV request failed"
                          (when method (str " (" method ")"))
                          " status " status)
                     {:status status :url url})]
      (when (fn? (:log client)) ((:log client) e))
      (throw e))))

(defn propfind!
  [client url depth]
  (-> (send! client {:url url
                     :method "PROPFIND"
                     :headers {"Depth" (str depth)
                               "Content-Type" "application/xml; charset=utf-8"}
                     :body propfind-body})
      (p/then (fn [{:as resp}]
                (check-status! client (assoc resp :method "PROPFIND") #{200 207})
                (parse-multistatus client (:body resp))))))

(defn- stat-loop
  [client url n delay-ms]
  (p/let [entries (propfind! client url 0)
          entry (first entries)]
    (cond
      (and entry (:prop entry)) (:prop entry)
      (pos? n) (-> (p/delay delay-ms)
                   (p/then (fn [_] (stat-loop client url (dec n) delay-ms))))
      :else nil)))

(defn stat
  "PROPFIND depth 0 on a single resource, with retry for the transient
   `propstat 404` seen on JianGuoYun right after PUT."
  ([client url] (stat client url {}))
  ([client url {:keys [retries delay-ms] :or {retries 5 delay-ms 500}}]
   (stat-loop client url retries delay-ms)))

(defn- walk-tree
  [client base queue files total seen max-entries]
  (if (empty? @queue)
    (p/resolved @files)
    (let [dir (first @queue)]
      (swap! queue subvec 1)
      (p/let [entries (propfind! client (child-url base dir) 1)]
        (doseq [{:keys [href prop]} entries]
          (let [rel (href->rel base href)]
            (when (and rel
                       (not (string/blank? rel))
                       (not (ignore/ignored-path? rel))
                       (not (ignore/external-artifact? rel)))
              (cond
                (:collection? prop)
                (when-not (contains? @seen rel)
                  (swap! seen conj rel)
                  (swap! queue conj rel))

                prop
                (do (swap! files assoc rel (select-keys prop [:etag :last-modified :size]))
                    (swap! total inc))))))
        (if (> @total max-entries)
          @files
          (walk-tree client base queue files total seen max-entries))))))

(defn list-tree
  "Recursively list a WebDAV collection.

   Returns {\"pages/a.md\" {:etag .. :last-modified .. :size ..}} for files."
  [client base {:keys [max-entries] :or {max-entries 20000}}]
  (walk-tree client base (atom [""]) (atom {}) (atom 0) (atom #{}) max-entries))

(defn get-file
  [client url {:keys [binary?]}]
  (send! client {:url url
                 :method "GET"
                 :return-type (if binary? :base64 :text)}))

(defn put-file!
  [client url body {:keys [binary? content-type]}]
  (send! client (cond-> {:url url
                         :method "PUT"
                         :headers {"Content-Type" (or content-type
                                                      (if binary?
                                                        "application/octet-stream"
                                                        "text/plain; charset=utf-8"))}
                         :body body}
                  binary? (assoc :body-base64? true))))

(defn mkcol!
  [client url]
  (-> (send! client {:url url :method "MKCOL"})
      (p/then (fn [{:as resp}]
                (check-status! client (assoc resp :method "MKCOL") #{200 201 405})
                true))))

(defn- ensure-parents-loop
  [client base dirs created]
  (if-let [d (first dirs)]
    (if (contains? @created d)
      (ensure-parents-loop client base (rest dirs) created)
      (p/let [_ (mkcol! client (child-url base d))]
        (swap! created conj d)
        (ensure-parents-loop client base (rest dirs) created)))
    (p/resolved nil)))

(defn ensure-parents!
  "MKCOL all missing ancestor directories of a file path. `created` is an atom
   holding a set of already-created dirs to avoid repeating requests."
  [client base rel created]
  (let [dirs (->> (string/split rel #"/")
                  drop-last
                  (reductions (fn [acc x] (if (string/blank? acc) x (str acc "/" x))) "")
                  rest)]
    (ensure-parents-loop client base dirs created)))

(defn test-connection!
  [client base]
  (-> (send! client {:url base
                     :method "PROPFIND"
                     :headers {"Depth" "0"
                               "Content-Type" "application/xml; charset=utf-8"}
                     :body propfind-body})
      (p/then (fn [{:keys [status]}]
                (contains? #{200 207} status)))))
