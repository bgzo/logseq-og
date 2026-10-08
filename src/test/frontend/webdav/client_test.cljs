(ns frontend.webdav.client-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as string]
            [frontend.test.helper :include-macros true :refer [deftest-async]]
            [frontend.webdav.client :as client]
            [frontend.webdav.http :as http]
            [promesa.core :as p]
            ["@xmldom/xmldom" :refer [DOMParser]]))

(defn- parse-xml [s]
  (.parseFromString (DOMParser.) s "text/xml"))

(defn- make-client [handler]
  (client/make-client
   (reify http/Http
     (request [_ opts]
       (p/resolved (handler opts))))
   {:parse-xml parse-xml :gap-ms 0}))

(def ^:private base "https://dav.example.com/dav/user/graph")

(defn- multistatus [responses]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>"
       "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://ns.jianguoyun.com\">"
       responses
       "</d:multistatus>"))

(defn- file-response [href etag size]
  (str "<d:response><d:href>" href "</d:href><d:propstat><d:prop>"
       "<d:resourcetype/><d:getetag>" etag "</d:getetag>"
       "<d:getcontentlength>" size "</d:getcontentlength>"
       "<d:getlastmodified>Wed, 07 Oct 2026 06:02:28 GMT</d:getlastmodified>"
       "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"))

(defn- collection-response [href]
  (str "<d:response><d:href>" href "</d:href><d:propstat><d:prop>"
       "<d:resourcetype><d:collection/></d:resourcetype>"
       "<d:getetag>collection-etag</d:getetag>"
       "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"))

(def root-xml
  (multistatus
   (str (collection-response "/dav/user/graph/")
        (collection-response "/dav/user/graph/pages/")
        (file-response "/dav/user/graph/pages/a.md" "etag-a" "10")
        (file-response "/dav/user/graph/logseq/bak/b.md" "etag-bak" "1")
        (file-response "/dav/user/graph/%e4%b8%ad%e6%96%87%20%e6%b5%8b%e8%af%95.md" "etag-cn" "2")
        (file-response "https://dav.example.com/dav/user/graph/pages/a.md" "etag-a" "10"))))

(def pages-xml
  (multistatus
   (str (collection-response "/dav/user/graph/pages/")
        (collection-response "/dav/user/graph/pages/sub/")
        (file-response "/dav/user/graph/pages/sub/b.md" "etag-b" "3"))))

(def sub-xml
  (multistatus
   (str (collection-response "/dav/user/graph/pages/sub/"))))

(deftest url-helpers
  (testing "basic auth header"
    (is (= "Basic YnVzZXI6cGFzcw==" (client/basic-auth-header "buser" "pass"))))
  (testing "child-url percent-encodes each segment"
    (is (= (str base "/%E4%B8%AD%E6%96%87%20%E6%B5%8B%E8%AF%95.md")
           (client/child-url base "中文 测试.md")))
    (is (= base (client/child-url (str base "/") ""))))
  (testing "href->rel handles server-absolute, full-url and lowercase encodings"
    (is (= "" (client/href->rel base "/dav/user/graph/")))
    (is (= "pages/a.md" (client/href->rel base "/dav/user/graph/pages/a.md")))
    (is (= "中文 测试.md" (client/href->rel base "/dav/user/graph/%e4%b8%ad%e6%96%87%20%e6%b5%8b%e8%af%95.md")))
    (is (= "pages/a.md" (client/href->rel base "https://dav.example.com/dav/user/graph/pages/a.md")))
    (is (nil? (client/href->rel base "/dav/other/graph/pages/a.md"))))
  (testing "parse-multistatus extracts props"
    (let [entries (client/parse-multistatus (make-client (fn [_] {})) root-xml)
          file (first (filter #(string/ends-with? (:href %) "pages/a.md") entries))
          dir (first (filter #(string/ends-with? (:href %) "/pages/") entries))]
      (is (= "etag-a" (get-in file [:prop :etag])))
      (is (= 10 (get-in file [:prop :size])))
      (is (false? (get-in file [:prop :collection?])))
      (is (true? (get-in dir [:prop :collection?]))))))

(deftest-async list-tree-recurses-and-filters
  (let [seen (atom [])
        c (make-client
           (fn [opts]
             (swap! seen conj [(:method opts) (:url opts)])
             (cond
               (= (:url opts) base) {:status 207 :body root-xml}
               (= (:url opts) (str base "/pages")) {:status 207 :body pages-xml}
               (= (:url opts) (str base "/pages/sub")) {:status 207 :body sub-xml}
               :else {:status 404 :body ""})))]
    (p/do!
     (p/let [files (client/list-tree c base {})]
       (is (= #{"pages/a.md" "pages/sub/b.md" "中文 测试.md"} (set (keys files))))
       (is (= "etag-b" (get-in files ["pages/sub/b.md" :etag])))
       (is (not-any? (fn [[_method url]] (string/includes? url "logseq/bak")) @seen)
           "excluded dirs must not be traversed")))))

(deftest-async stat-retries-transient-propstat-404
  (let [calls (atom 0)
        c (make-client
           (fn [_opts]
             (if (= 1 (swap! calls inc))
               {:status 207 :body (multistatus
                                   (str "<d:response><d:href>/dav/user/graph/pages/a.md</d:href>"
                                        "<d:propstat><d:prop/><d:status>HTTP/1.1 404 Not Found</d:status>"
                                        "</d:propstat></d:response>"))}
               {:status 207 :body (multistatus
                                   (file-response "/dav/user/graph/pages/a.md" "etag-after" "10"))})))]
    (p/do!
     (p/let [prop (client/stat c (str base "/pages/a.md") {:retries 3 :delay-ms 1})]
       (is (= 2 @calls))
       (is (= "etag-after" (:etag prop)))))))

(deftest-async put-and-mkcol
  (let [requests (atom [])
        c (make-client
           (fn [opts]
             (swap! requests conj opts)
             {:status 201 :body ""}))]
    (p/do!
     (client/put-file! c (str base "/assets/a.png") "aGVsbG8=" {:binary? true :content-type "image/png"})
     (client/put-file! c (str base "/pages/a.md") "# hi" {})
     (let [[put-bin put-text] @requests]
       (is (true? (:body-base64? put-bin)))
       (is (= "image/png" (get-in put-bin [:headers "Content-Type"])))
       (is (nil? (:body-base64? put-text)))
       (is (= "text/plain; charset=utf-8" (get-in put-text [:headers "Content-Type"]))))
     (p/let [created (atom #{})
             _ (client/ensure-parents! c base "pages/sub/c.md" created)]
       (is (= #{"pages" "pages/sub"} @created)))
     (p/let [count-before (count @requests)
             created (atom #{"pages" "pages/sub"})
             _ (client/ensure-parents! c base "pages/sub/c.md" created)]
       (is (= count-before (count @requests)) "already created dirs must not be re-requested")))))
