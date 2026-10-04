(ns frontend.components.repo
  (:require [frontend.components.widgets :as widgets]
            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.db :as db]
            [frontend.handler.repo :as repo-handler]
            [frontend.handler.web.nfs :as nfs-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [rum.core :as rum]
            [frontend.mobile.util :as mobile-util]
            [frontend.util.text :as text-util]
            [promesa.core :as p]
            [electron.ipc :as ipc]
            [goog.object :as gobj]
            [reitit.frontend.easy :as rfe]))

(rum/defc normalized-graph-label
  [{:keys [url] :as graph} on-click]
  (when graph
    (let [local? (config/local-db? url)]
      [:span.flex.items-center
       (if local?
         (let [local-dir (config/get-local-dir url)
               graph-name (text-util/get-graph-name-from-path url)]
           [:a.flex.items-center {:title    local-dir
                                  :on-click #(on-click graph)}
            [:span graph-name]])

         [:a.flex.items-center {:title    url
                                :on-click #(on-click graph)}
          (db/get-repo-path url)])])))

(rum/defc repos-inner
  "Graph list in `All graphs` page"
  [repos]
  (for [{:keys [url] :as repo} repos]
    [:div.flex.justify-between.mb-4.items-center {:key url}
     (normalized-graph-label repo #(state/pub-event! [:graph/switch url]))

     [:div.controls
      [:div.flex.flex-row.items-center
       (ui/tippy {:html [:div.text-sm.max-w-xs
                         "Removes Logseq's access to the local file path of your graph. It won't remove your local files."]
                  :class "tippy-hover"
                  :interactive true}
                 [:a.text-gray-400.ml-4.font-medium.text-sm.whitespace-nowrap
                  {:on-click (fn []
                               (let [current-repo (state/get-current-repo)]
                                 (repo-handler/remove-repo! repo)
                                 (state/pub-event! [:graph/unlinked repo current-repo])))}
                  "Unlink"])]]]))

(rum/defc repos < rum/reactive
  []
  (let [repos (state/sub [:me :repos])
        repos (util/distinct-by :url repos)
        repos (remove #(= (:url %) config/local-repo) repos)]
    (if (seq repos)
      [:div#graphs
       [:h1.title (t :graph/all-graphs)]

       [:div.pl-1.content.mt-3

        [:div
         [:h2.text-lg.font-medium.my-4 (t :graph/local-graphs)]
         (repos-inner repos)

         [:div.flex.flex-row.my-4
          (when (or (nfs-handler/supported?)
                    (mobile-util/native-platform?))
            [:div.mr-8
             (ui/button
               (t :open-a-directory)
               :on-click #(state/pub-event! [:graph/setup-a-repo]))])]]]]
      (widgets/add-graph))))

(defn- check-multiple-windows?
  [state]
  (when (util/electron?)
    (p/let [multiple-windows? (ipc/ipc "graphHasMultipleWindows" (state/get-current-repo))]
      (reset! (::electron-multiple-windows? state) multiple-windows?))))

(defn- repos-dropdown-links [repos current-repo *multiple-windows?]
  (let [switch-repos (if-not (nil? current-repo)
                       (remove (fn [repo] (= current-repo (:url repo))) repos) repos) ; exclude current repo
        repo-links (mapv
                    (fn [{:keys [url] :as _graph}]
                      (let [local? (config/local-db? url)
                            repo-url (if local? (db/get-repo-name url) url)
                            short-repo-name (if local? (text-util/get-graph-name-from-path repo-url) repo-url)]
                        (when short-repo-name
                          {:title        [:span.flex.items-center.whitespace-nowrap short-repo-name]
                           :hover-detail repo-url ;; show full path on hover
                           :options      {:on-click (fn [e]
                                                      (if (gobj/get e "shiftKey")
                                                        (state/pub-event! [:graph/open-new-window url])
                                                        (state/pub-event! [:graph/switch url])))}})))
                    switch-repos)
        refresh-link (let [nfs-repo? (config/local-db? current-repo)]
                       (when (and nfs-repo?
                                  (not= current-repo config/local-repo)
                                  (or (nfs-handler/supported?)
                                      (mobile-util/native-platform?)))
                         {:title (t :sync-from-local-files)
                          :hover-detail (t :sync-from-local-files-detail)
                          :options {:on-click #(state/pub-event! [:graph/ask-for-re-fresh])}}))
        reindex-link {:title        (t :re-index)
                      :hover-detail (t :re-index-detail)
                      :options (cond->
                                {:on-click
                                 (fn []
                                   (state/pub-event! [:graph/ask-for-re-index *multiple-windows? nil]))})}
        new-window-link (when (and (util/electron?)
                                   ;; New Window button in menu bar of macOS is available.
                                   (not util/mac?))
                          {:title        (t :open-new-window)
                           :options {:on-click #(state/pub-event! [:graph/open-new-window nil])}})]
    (->>
     (concat repo-links
             [(when (seq repo-links) {:hr true})
              (if (or (nfs-handler/supported?) (mobile-util/native-platform?))
                {:title (t :new-graph) :options {:on-click #(state/pub-event! [:graph/setup-a-repo])}}
                {:title (t :new-graph) :options {:href (rfe/href :repos)}}) ;; Brings to the repos page for showing fallback message
              {:title (t :all-graphs) :options {:href (rfe/href :repos)}}
              refresh-link
              reindex-link
              new-window-link])
     (remove nil?))))

(rum/defcs repos-dropdown < rum/reactive
  (rum/local false ::electron-multiple-windows?)
  [state]
  (let [multiple-windows? (::electron-multiple-windows? state)
        current-repo (state/sub :git/current-repo)]
    (when current-repo
      (let [repos (state/sub [:me :repos])
            links (repos-dropdown-links repos current-repo multiple-windows?)
            render-content (fn [{:keys [toggle-fn]}]
                             (let [repo-name (db/get-repo-name current-repo)
                                   short-repo-name (if repo-name
                                                     (db/get-short-repo-name repo-name)
                                                     "Select a Graph")]
                               [:a.item.group.flex.items-center.p-2.text-sm.font-medium.rounded-md

                                {:on-click (fn []
                                             (check-multiple-windows? state)
                                             (toggle-fn))
                                 :title    repo-name}       ;; show full path on hover
                                [:div.flex.flex-row.items-center
                                 [:div.flex.relative.graph-icon.rounded
                                  (let [icon "database"
                                        opts {:size 14}]
                                    (ui/icon icon opts))]

                                 [:div.graphs
                                  [:span#repo-switch.block.pr-2.whitespace-nowrap
                                   [:span [:span#repo-name.font-medium
                                           [:span.overflow-hidden.text-ellipsis (if (= config/local-repo short-repo-name) "Demo" short-repo-name)]]]
                                   [:span.dropdown-caret.ml-2 {:style {:border-top-color "#6b7280"}}]]]]]))
            links-header (cond->
                          {:z-index 1000
                           :modal-class (util/hiccup->class
                                         "origin-top-right.absolute.left-0.mt-2.rounded-md.shadow-lg")}
                           (> (count repos) 1)              ; show switch to if there are multiple repos
                           (assoc :links-header [:div.font-medium.text-sm.opacity-70.px-4.pt-2.pb-1.flex.flex-row.justify-between.items-center
                                                 [:div (t :left-side-bar/switch)]]))]
        (when (seq repos)
          (ui/dropdown-with-links render-content links links-header))))))
