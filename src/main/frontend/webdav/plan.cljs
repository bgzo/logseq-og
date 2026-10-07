(ns frontend.webdav.plan
  "Pure sync planning: three-way state comparison (manifest, local tree, remote
   tree) to a list of operations. See docs/webdav-sync.md sections 3.2/3.3."
  (:require [frontend.webdav.manifest :as manifest]))

(defn- conflict-op
  [path policy]
  {:op :conflict
   :path path
   ;; :local  = keep local content, back up remote, upload
   ;; :remote = keep remote content, back up local, download
   :resolution (if (= policy :prefer-remote) :remote :local)})

(defn plan
  "Build a sync plan.

   manifest: manifest map (see frontend.webdav.manifest)
   local:    {\"pages/a.md\" {:mtime number :size number} ...}
   remote:   {\"pages/a.md\" {:etag string :size number} ...}
   opts:     {:conflict-policy :prefer-local | :prefer-remote}

   Returns:
   {:ops [{:op :upload :path p}
          {:op :download :path p :remote {...}}
          {:op :conflict :path p :resolution :local|:remote}]
    :state-updates {path {:state :local-deleted|:remote-deleted}}
    :remove #{path}}"
  [{:keys [manifest local remote conflict-policy]}]
  (let [policy (or conflict-policy :prefer-local)
        paths (into #{} (concat (keys local) (keys remote) (keys (:files manifest))))]
    (reduce
     (fn [acc path]
       (let [l (get local path)
             r (get remote path)
             m (manifest/entry manifest path)]
         (cond
           (and l r)
           (cond
             (nil? m)
             (update acc :ops conj (conflict-op path policy))

             (and (manifest/local-changed? l m)
                  (manifest/remote-changed? r m))
             (update acc :ops conj (conflict-op path policy))

             (manifest/local-changed? l m)
             (update acc :ops conj {:op :upload :path path})

             (manifest/remote-changed? r m)
             (update acc :ops conj {:op :download :path path :remote r})

             :else acc)

           (and l (not r))
           (cond
             (nil? m)
             (update acc :ops conj {:op :upload :path path})

             ;; local reappeared after a local-deleted tombstone
             (= :local-deleted (:state m))
             (update acc :ops conj {:op :upload :path path})

             (= :remote-deleted (:state m))
             (if (manifest/local-changed? l m)
               (update acc :ops conj {:op :upload :path path})
               acc)

             :else
             (if (manifest/local-changed? l m)
               (update acc :ops conj {:op :upload :path path})
               (assoc-in acc [:state-updates path] {:state :remote-deleted})))

           (and (not l) r)
           (cond
             (nil? m)
             (update acc :ops conj {:op :download :path path :remote r})

             ;; remote reappeared after a remote-deleted tombstone
             (= :remote-deleted (:state m))
             (update acc :ops conj {:op :download :path path :remote r})

             (= :local-deleted (:state m))
             (if (manifest/remote-changed? r m)
               (update acc :ops conj {:op :download :path path :remote r})
               acc)

             :else
             (if (manifest/remote-changed? r m)
               (update acc :ops conj {:op :download :path path :remote r})
               (assoc-in acc [:state-updates path] {:state :local-deleted})))

           (and (not l) (not r) m)
           (update acc :remove conj path)

           :else acc)))
     {:ops [] :state-updates {} :remove #{}}
     paths)))
