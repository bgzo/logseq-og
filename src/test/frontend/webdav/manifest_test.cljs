(ns frontend.webdav.manifest-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.webdav.manifest :as manifest]))

(deftest parse-serialize
  (let [m (-> (manifest/blank)
              (manifest/put-entry "pages/a.md"
                                  (manifest/synced-entry {:mtime 1 :size 2}
                                                         {:etag "abc" :size 2})))
        parsed (manifest/parse (manifest/serialize m))]
    (is (= "abc" (get-in parsed [:files "pages/a.md" :remote :etag])))
    (is (= :synced (get-in parsed [:files "pages/a.md" :state])))
    (is (= 1 (get-in parsed [:files "pages/a.md" :local :mtime])))))

(deftest parse-invalid
  (is (nil? (manifest/parse "not edn {")))
  (is (nil? (manifest/parse "42")))
  (is (nil? (manifest/parse "{:version 1}"))))

(deftest local-meta-normalization
  (testing "node backend returns JS Date for mtime"
    (is (= {:mtime 1000 :size 5}
           (manifest/local-meta {:mtime (js/Date. 1000) :size 5}))))
  (testing "nfs backend returns :last-modified-at"
    (is (= {:mtime 42 :size 5}
           (manifest/local-meta {:last-modified-at 42 :size 5})))))

(deftest changed-predicates
  (let [entry (manifest/synced-entry {:mtime 100 :size 10} {:etag "e1" :size 10})]
    (is (false? (manifest/local-changed? {:mtime 100 :size 10} entry)))
    (is (true? (manifest/local-changed? {:mtime 101 :size 10} entry)))
    (is (true? (manifest/local-changed? {:mtime 100 :size 11} entry)))
    (is (false? (manifest/remote-changed? {:etag "e1"} entry)))
    (is (true? (manifest/remote-changed? {:etag "e2"} entry)))
    (is (true? (manifest/remote-changed? {:etag nil} entry)))))

(deftest transitions
  (let [m (manifest/blank)
        m' (manifest/put-entry m "a.md" (manifest/synced-entry {:mtime 1 :size 1} {:etag "e" :size 1}))
        m'' (manifest/remove-entry m' "a.md")]
    (is (empty? (:files m'')))
    (is (= :local-deleted
           (:state (manifest/with-state (manifest/entry m' "a.md") :local-deleted))))))
