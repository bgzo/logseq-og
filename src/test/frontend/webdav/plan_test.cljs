(ns frontend.webdav.plan-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.webdav.manifest :as manifest]
            [frontend.webdav.plan :as plan]))

(defn- local [mtime size] {:mtime mtime :size size})
(defn- remote [etag] {:etag etag :size 1})
(defn- ops-set [{:keys [ops]}] (set (map (juxt :op :path) ops)))
(defn- plan*
  ([m l r] (plan/plan {:manifest m :local l :remote r}))
  ([m l r policy] (plan/plan {:manifest m :local l :remote r :conflict-policy policy})))

(deftest first-sync
  (testing "local only → upload"
    (is (= #{[:upload "a.md"]} (ops-set (plan* (manifest/blank) {"a.md" (local 1 1)} {})))))
  (testing "remote only → download"
    (is (= #{[:download "b.md"]} (ops-set (plan* (manifest/blank) {} {"b.md" (remote "e1")})))))
  (testing "both without manifest → conflict"
    (let [p (plan* (manifest/blank) {"a.md" (local 1 1)} {"a.md" (remote "e1")})]
      (is (= #{[:conflict "a.md"]} (ops-set p)))
      (is (= :local (:resolution (first (:ops p)))))
      (is (= :remote (:resolution (first (:ops (plan* (manifest/blank)
                                                       {"a.md" (local 1 1)}
                                                       {"a.md" (remote "e1")}
                                                       :prefer-remote)))))))))

(deftest synced-state
  (let [m (manifest/put-entry (manifest/blank) "a.md"
                              (manifest/synced-entry (local 1 1) (remote "e1")))]
    (testing "no changes → no ops"
      (is (empty? (:ops (plan* m {"a.md" (local 1 1)} {"a.md" (remote "e1")})))))
    (testing "local changed → upload"
      (is (= #{[:upload "a.md"]} (ops-set (plan* m {"a.md" (local 2 1)} {"a.md" (remote "e1")})))))
    (testing "remote changed → download"
      (is (= #{[:download "a.md"]} (ops-set (plan* m {"a.md" (local 1 1)} {"a.md" (remote "e2")})))))
    (testing "both changed → conflict, policy respected"
      (is (= :local (:resolution (first (:ops (plan* m {"a.md" (local 2 2)} {"a.md" (remote "e2")}))))))
      (is (= :remote (:resolution (first (:ops (plan* m {"a.md" (local 2 2)} {"a.md" (remote "e2")}
                                                        :prefer-remote)))))))))

(deftest local-missing
  (let [m (manifest/put-entry (manifest/blank) "a.md"
                              (manifest/synced-entry (local 1 1) (remote "e1")))]
    (testing "remote unchanged → local-deleted tombstone, no op"
      (let [p (plan* m {} {"a.md" (remote "e1")})]
        (is (empty? (:ops p)))
        (is (= :local-deleted (get-in p [:state-updates "a.md" :state])))))
    (testing "remote changed → download (remote edit wins)"
      (is (= #{[:download "a.md"]} (ops-set (plan* m {} {"a.md" (remote "e2")})))))
    (testing "already tombstoned → no-op"
      (let [m' (manifest/put-entry (manifest/blank) "a.md"
                                   (manifest/with-state
                                    (manifest/synced-entry (local 1 1) (remote "e1")) :local-deleted))]
        (is (empty? (:ops (plan* m' {} {"a.md" (remote "e1")}))))))
    (testing "remote deleted too → remove entry"
      (is (= #{"a.md"} (:remove (plan* m {} {})))))))

(deftest remote-missing
  (let [m (manifest/put-entry (manifest/blank) "a.md"
                              (manifest/synced-entry (local 1 1) (remote "e1")))]
    (testing "local unchanged → remote-deleted tombstone, no op"
      (let [p (plan* m {"a.md" (local 1 1)} {})]
        (is (empty? (:ops p)))
        (is (= :remote-deleted (get-in p [:state-updates "a.md" :state])))))
    (testing "local changed → upload (recreate remote)"
      (is (= #{[:upload "a.md"]} (ops-set (plan* m {"a.md" (local 2 2)} {})))))
    (testing "already tombstoned and unchanged → no-op"
      (let [m' (manifest/put-entry (manifest/blank) "a.md"
                                   (manifest/with-state
                                    (manifest/synced-entry (local 1 1) (remote "e1")) :remote-deleted))]
        (is (empty? (:ops (plan* m' {"a.md" (local 1 1)} {}))))))
    (testing "local reappeared after local-deleted → upload"
      (let [m' (manifest/put-entry (manifest/blank) "a.md"
                                   (manifest/with-state
                                    (manifest/synced-entry (local 1 1) (remote "e1")) :local-deleted))]
        (is (= #{[:upload "a.md"]} (ops-set (plan* m' {"a.md" (local 1 1)} {}))))))
    (testing "remote reappeared after remote-deleted → download"
      (let [m' (manifest/put-entry (manifest/blank) "a.md"
                                   (manifest/with-state
                                    (manifest/synced-entry (local 1 1) (remote "e1")) :remote-deleted))]
        (is (= #{[:download "a.md"]} (ops-set (plan* m' {} {"a.md" (remote "e1")}))))))))
