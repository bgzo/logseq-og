(ns frontend.webdav.http.desktop
  "Desktop HTTP adapter: requests run in the Electron main process (node-fetch),
   so there is no CORS restriction and the proxy agent is reused."
  (:require [electron.ipc :as ipc]
            [frontend.webdav.http :as http]))

(defrecord DesktopHttp []
  http/Http
  (request [_ opts]
    (ipc/ipc :webdavFetch opts)))

(defn make []
  (->DesktopHttp))
