(ns frontend.webdav.http.mobile
  "Mobile HTTP adapter backed by CapacitorHttp (native OkHttp / URLSession, no
   CORS). Uses the plugin API directly so the global fetch is not patched."
  (:require ["@capacitor/core" :refer [CapacitorHttp]]
            [cljs-bean.core :as bean]
            [frontend.webdav.http :as http]
            [promesa.core :as p]))

(defn- ->opts
  [{:keys [url method headers body body-base64? return-type timeout]}]
  (cond-> {:url url
           :method (or method "GET")
           :headers (or headers {})
           :connectTimeout (or timeout 30000)
           :readTimeout (or timeout 30000)
           :responseType (if (= return-type :base64) "blob" "text")}
    (some? body) (assoc :data body)
    ;; CapacitorHttp: dataType "file" means the data string is base64
    body-base64? (assoc :dataType "file")))

(defrecord MobileHttp []
  http/Http
  (request [_ opts]
    (-> (.request CapacitorHttp (clj->js (->opts opts)))
        (p/then (fn [res]
                  (let [res (bean/->clj res)]
                    {:status (:status res)
                     :headers (or (:headers res) {})
                     :body (:data res)})))
        (p/catch (fn [e]
                   (throw (ex-info (str "WebDAV HTTP request failed: "
                                        (or (.-message e) (str e)))
                                   {})))))))

(defn make []
  (->MobileHttp))
