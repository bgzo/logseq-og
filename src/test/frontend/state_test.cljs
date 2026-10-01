(ns frontend.state-test
  (:require [clojure.test :refer [deftest is testing]]
            [frontend.state :as state]))

(deftest merge-configs
  (let [global-config
        {:shortcuts {:ui/toggle-theme "t z"}
         :hidden []
         :ui/enable-tooltip? true
         :preferred-workflow :todo
         :git-pull-secs 60}
        local-config {:hidden ["foo" "bar"]
                      :ui/enable-tooltip? false
                      :preferred-workflow :now
                      :git-pull-secs 120}]
    (is (= local-config
           (dissoc (state/merge-configs global-config local-config) :shortcuts))
        "Later config overrides all non-map values")
    (is (= {:start-of-week 6 :shortcuts {:ui/toggle-theme "t z"}}
           (select-keys (state/merge-configs {:start-of-week 6}
                                             global-config
                                             local-config)
                        [:start-of-week :shortcuts]))
        "Earlier configs set default values"))

  (is (= {:shortcuts {:ui/toggle-theme "t z"
                      :ui/toggle-brackets "t b"
                      :editor/up ["ctrl+p" "up"]}}
         (state/merge-configs {:shortcuts {:ui/toggle-theme "t z"}}
                              {:shortcuts {:ui/toggle-brackets "t b"}}
                              {:shortcuts {:editor/up ["ctrl+p" "up"]}}))
      "Map values get merged across configs"))

(deftest get-backup-enabled?
  (let [original (:electron/user-cfgs @state/state)]
    (try
      (testing "backups are enabled when the user config is unset"
        (state/set-state! :electron/user-cfgs nil)
        (is (true? (state/get-backup-enabled?))))
      (testing "an explicit user config wins over the default"
        (state/set-state! [:electron/user-cfgs :feature/enable-backup?] true)
        (is (true? (state/get-backup-enabled?)))
        (state/set-state! [:electron/user-cfgs :feature/enable-backup?] false)
        (is (false? (state/get-backup-enabled?))))
      (finally
        (state/set-state! :electron/user-cfgs original)))))
