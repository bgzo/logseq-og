(ns frontend.webdav.http
  "HTTP adapter protocol for WebDAV requests.

   Desktop: Electron main-process node-fetch (no CORS).
   Mobile:  CapacitorHttp (added in M3).
   Tests:   fake implementations.")

(defprotocol Http
  (request [this opts]
    "Send a request. opts is a clj map:
     {:url string
      :method string
      :headers {string string}
      :body string                 ; text body, or base64 when :body-base64?
      :body-base64? boolean
      :return-type :text | :base64
      :timeout number}
     Resolves to {:status number :headers {string string} :body string}."))
