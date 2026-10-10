(ns frontend.webdav.coexist
  "Single-sync-source policy checks, see docs/webdav-sync.md section 3.6."
  (:require [clojure.string :as string]
            [frontend.webdav.io :as io]
            [logseq.common.path :as path]
            [promesa.core :as p]))

(defn- read-git-config
  "Read the .git config, tolerating both a .git directory and a gitdir: pointer
   file (worktrees / separate git dir)."
  [repo]
  (p/let [direct (-> (io/read-abs-text (io/abs-path repo ".git/config"))
                     (p/catch (fn [_] nil)))]
    (if direct
      direct
      (p/let [dot-git (-> (io/read-abs-text (io/abs-path repo ".git"))
                          (p/catch (fn [_] nil)))
              dot-git (some-> dot-git string/trim)]
        (when (and (string? dot-git)
                   (string/starts-with? dot-git "gitdir:"))
          (let [dir (string/trim (subs dot-git (count "gitdir:")))
                dir (if (string/starts-with? dir "/")
                      dir
                      (io/abs-path repo dir))]
            (-> (io/read-abs-text (path/path-join dir "config"))
                (p/catch (fn [_] nil)))))))))

(defn check
  "Returns a promise of {:ok true} or {:ok false :reason kw :detail {...}}."
  [repo config]
  (p/let [repo-dir (io/graph-dir repo)
          icloud? (boolean (or (string/includes? repo-dir "/Library/Mobile Documents/")
                               (string/includes? repo-dir "/iCloud~")))
          git-config (read-git-config repo)
          git-remote? (boolean (and git-config (string/includes? git-config "[remote")))]
    (cond
      icloud?
      {:ok false
       :reason :icloud
       :detail {:path repo-dir
                :hint "Graphs inside iCloud cannot use WebDAV sync. Move the graph out of iCloud first."}}

      (and git-remote? (not (:allow-git-remote? config)))
      {:ok false
       :reason :git-remote
       :detail {:hint "This graph is a git remote. WebDAV + git remote is discouraged; enable the coexistence override to continue."}}

      :else
      {:ok true})))
