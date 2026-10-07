(ns frontend.webdav.store
  "Per-graph persistent state: config, manifest and credentials.
   Implementations: frontend.webdav.store.desktop (mobile in M3).")

(defprotocol Store
  (graph-key [this repo])
  (load-config [this repo])
  (save-config [this repo config])
  (load-manifest [this repo])
  (save-manifest [this repo manifest])
  (load-credentials [this repo])
  (save-credentials [this repo creds]))
