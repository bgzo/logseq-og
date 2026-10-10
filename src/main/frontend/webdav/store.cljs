(ns frontend.webdav.store
  "Per-graph persistent state: config, manifest and credentials.
   Implementations: frontend.webdav.store.desktop / .mobile.")

(def default-config
  {:enabled false
   :url ""
   :remote-root ""
   :username ""
   :conflict-policy :prefer-local
   :interval 300
   :request-gap-ms 100
   :max-file-mb 100
   :allow-git-remote? false})

(defprotocol Store
  (graph-key [this repo])
  (load-config [this repo])
  (save-config [this repo config])
  (load-manifest [this repo])
  (save-manifest [this repo manifest])
  (load-credentials [this repo])
  (save-credentials [this repo creds]))
