(ns frontend.webdav.scan
  "Local graph scanning for the sync engine."
  (:require [frontend.config :as config]
            [frontend.fs :as fs]
            [frontend.webdav.ignore :as ignore]
            [frontend.webdav.manifest :as manifest]
            [logseq.common.path :as path]
            [promesa.core :as p]))

(defn- stat-one
  [repo-dir rel]
  (-> (fs/stat repo-dir rel)
      (p/then (fn [s] [rel s]))
      (p/catch (fn [_] nil))))

(defn- stat-batches
  [repo-dir rels batch-size]
  (->> rels
       (partition-all batch-size)
       (map (fn [chunk] (p/all (map #(stat-one repo-dir %) chunk))))
       (p/all)
       (p/then (fn [chunks] (vec (apply concat chunks))))))

(defn list-local
  "Scan the local graph directory.

   Returns
   {:files {relpath {:mtime .. :size ..}}
    :external-artifacts [relpath ...]   ; produced by other folder-sync tools
    :skipped-large [relpath ...]}"
  [repo {:keys [max-file-mb]}]
  (let [repo-dir (config/get-repo-dir repo)]
    (p/let [all-paths (fs/readdir repo-dir :path-only? true)
            rels (->> all-paths
                      (map #(path/relative-path repo-dir %))
                      (remove nil?))
            external-artifacts (->> rels (filter ignore/external-artifact?) vec)
            rels (->> rels
                      (remove ignore/ignored-path?)
                      (remove ignore/external-artifact?)
                      vec)
            stats (stat-batches repo-dir rels 100)
            size-ok? (fn [s] (ignore/file-size-ok? (:size s) max-file-mb))
            files (->> stats
                       (keep (fn [[rel s]]
                               (when (and s (size-ok? s))
                                 [rel (manifest/local-meta s)])))
                       (into {}))
            skipped-large (->> stats
                               (keep (fn [[rel s]]
                                       (when (and s (not (size-ok? s))) rel)))
                               vec)]
      {:files files
       :external-artifacts external-artifacts
       :skipped-large skipped-large})))
