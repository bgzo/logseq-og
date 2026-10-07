(ns frontend.components.webdav-settings
  "Settings panel for WebDAV sync."
  (:require [clojure.string :as string]
            [frontend.context.i18n :refer [t]]
            [frontend.handler.notification :as notification]
            [frontend.handler.webdav :as webdav-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [promesa.core :as p]
            [rum.core :as rum]))

(defn- field-row
  [label control help]
  [:div.it.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-center
   [:label.block.text-sm.font-medium.leading-5.opacity-70 label]
   [:div.mt-1.sm:mt-0.sm:col-span-2
    control
    (when help
      [:p.text-xs.opacity-50.mt-1 help])]])

(defn- save!
  [repo config password {:keys [silent? on-done]}]
  (-> (p/let [creds (webdav-handler/load-credentials repo)
              _ (webdav-handler/save-config! repo (dissoc config :password))
              new-password (if (string/blank? password)
                             (:password creds)
                             password)
              _ (webdav-handler/save-credentials! repo {:username (or (:username config) "")
                                                        :password (or new-password "")})]
        (when-not silent?
          (notification/show! (t :webdav/save-success) :success false)))
      (p/catch (fn [e]
                 (notification/show! (str (t :webdav/save-failed) " " e) :error false)))
      (p/finally (fn []
                   (when on-done (on-done))))))

(defn- phase-text
  [status]
  (case (:phase status)
    :checking (t :webdav/status-checking)
    :scanning (t :webdav/status-scanning)
    :applying (str (t :webdav/status-applying) " " (:total status 0))
    :done (str (t :webdav/status-done)
               " — "
               (t :webdav/summary-uploads) " " (:uploads status 0) ", "
               (t :webdav/summary-downloads) " " (:downloads status 0) ", "
               (t :webdav/summary-conflicts) " " (:conflicts status 0))
    :error (str (t :webdav/status-error) ": " (:error status))
    :blocked (str (t :webdav/status-blocked)
                  ": "
                  (case (:reason status)
                    :icloud (t :webdav/blocked-icloud)
                    :git-remote (t :webdav/blocked-git-remote)
                    (t :webdav/blocked-generic)))
    :locked (t :webdav/status-locked)
    (t :webdav/status-idle)))

(rum/defc webdav-status-line < rum/reactive
  [repo]
  (let [status (state/sub [:webdav/status repo])]
    [:div.text-sm
     [:span.font-medium (str (t :webdav/status) ": ")]
     [:span (phase-text status)]
     (when-let [at (:at status)]
       [:span.opacity-50 (str " (" at ")")])]))

(rum/defc settings-content
  [repo]
  (let [[config set-config!] (rum/use-state nil)
        [password set-password!] (rum/use-state "")
        [saving? set-saving!] (rum/use-state false)
        [testing? set-testing!] (rum/use-state false)
        [syncing? set-syncing!] (rum/use-state false)
        set-field! (fn [k v] (set-config! (assoc config k v)))]
    (rum/use-effect!
     (fn []
       (-> (p/let [c (webdav-handler/get-config repo)
                   creds (webdav-handler/load-credentials repo)]
             (set-config! (assoc c :username (or (not-empty (:username c))
                                                 (:username creds)))))
           (p/catch (fn [e] (js/console.error e))))
       (fn [] nil))
     [repo])

    (if (nil? config)
      [:div.panel-wrap.is-webdav (ui/loading "WebDAV")]
      [:div.panel-wrap.is-webdav
       [:h1.text-2xl.font-bold.mb-2 (t :webdav/title)]
       [:p.text-sm.opacity-60.mb-6 (t :webdav/desc)]

       (field-row
        (t :webdav/enable)
        [:div.flex.items-center
         (ui/toggle (boolean (:enabled config))
                    (fn [] (set-field! :enabled (not (:enabled config))))
                    true)]
        nil)

       (field-row
        (t :webdav/url)
        [:input.form-input.is-small
         {:type "text"
          :value (or (:url config) "")
          :placeholder "https://dav.example.com/remote.php/dav/files/user"
          :on-change #(set-field! :url (util/evalue %))}]
        nil)

       (field-row
        (t :webdav/remote-root)
        [:input.form-input.is-small
         {:type "text"
          :value (or (:remote-root config) "")
          :placeholder "/logseq-og/my-graph"
          :on-change #(set-field! :remote-root (util/evalue %))}]
        (t :webdav/remote-root-hint))

       (field-row
        (t :webdav/username)
        [:input.form-input.is-small
         {:type "text"
          :value (or (:username config) "")
          :on-change #(set-field! :username (util/evalue %))}]
        nil)

       (field-row
        (t :webdav/password)
        [:input.form-input.is-small
         {:type "password"
          :value password
          :placeholder (t :webdav/password-hint)
          :on-change #(set-password! (util/evalue %))}]
        nil)

       (field-row
        (t :webdav/conflict-policy)
        (ui/select
         [{:label (t :webdav/conflict-local)
           :value "prefer-local"
           :selected (= :prefer-local (:conflict-policy config))}
          {:label (t :webdav/conflict-remote)
           :value "prefer-remote"
           :selected (= :prefer-remote (:conflict-policy config))}]
         (fn [_e value] (set-field! :conflict-policy (keyword value))))
        nil)

       (field-row
        (t :webdav/interval)
        (ui/select
         [{:label (t :webdav/interval-off) :value "0" :selected (= 0 (:interval config))}
          {:label (t :webdav/interval-1m) :value "60" :selected (= 60 (:interval config))}
          {:label (t :webdav/interval-5m) :value "300" :selected (= 300 (:interval config))}
          {:label (t :webdav/interval-15m) :value "900" :selected (= 900 (:interval config))}
          {:label (t :webdav/interval-30m) :value "1800" :selected (= 1800 (:interval config))}]
         (fn [_e value] (set-field! :interval (js/parseInt value 10))))
        nil)

       [:p.text-xs.opacity-50.mb-4 (t :webdav/gitignore-hint)]

       [:div.flex.items-center.gap-3.mb-6
        (ui/button (if saving? (t :webdav/saving) (t :webdav/save))
                   :intent "logseq"
                   :small? true
                   :disabled saving?
                   :on-click
                   (fn []
                     (set-saving! true)
                     (save! repo config password
                            {:on-done (fn [] (set-saving! false))})))

        (ui/button (if testing? (t :webdav/testing) (t :webdav/test))
                   :small? true
                   :disabled testing?
                   :on-click
                   (fn []
                     (set-testing! true)
                     (save! repo config password
                            {:silent? true
                             :on-done
                             (fn []
                               (-> (webdav-handler/test-connection! repo)
                                   (p/then (fn [ok?]
                                             (notification/show!
                                              (if ok?
                                                (t :webdav/test-success)
                                                (t :webdav/test-failed))
                                              (if ok? :success :error)
                                              false)))
                                   (p/catch (fn [e]
                                              (notification/show!
                                               (str (t :webdav/test-failed) " " e)
                                               :error false)))
                                   (p/finally (fn [] (set-testing! false)))))})))

        (ui/button (if syncing? (t :webdav/syncing) (t :webdav/sync-now))
                   :small? true
                   :disabled syncing?
                   :on-click
                   (fn []
                     (set-syncing! true)
                     (save! repo config password
                            {:silent? true
                             :on-done
                             (fn []
                               (-> (webdav-handler/sync-now! repo)
                                   (p/finally (fn [] (set-syncing! false)))))})))]

       (webdav-status-line repo)])))
