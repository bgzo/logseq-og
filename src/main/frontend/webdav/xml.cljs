(ns frontend.webdav.xml
  "Thin DOM helpers for WebDAV multistatus parsing.

   The actual DOMParser is injected (see frontend.webdav.client); tests use
   @xmldom/xmldom, the app uses the host DOMParser."
  (:require [clojure.string :as string]))

(defn parse
  "Default parser for the app (Electron/mobile webviews)."
  [s]
  (.parseFromString (js/DOMParser.) s "text/xml"))

(defn child-elements
  "Direct child elements with the given local name (namespace-agnostic)."
  [node tag]
  (when node
    (->> (array-seq (.-childNodes node))
         (filter #(= 1 (.-nodeType ^js %)))
         (filter #(= tag (.-localName ^js %))))))

(defn first-child
  [node tag]
  (first (child-elements node tag)))

(defn text-content
  [node]
  (some-> node (.-textContent) string/trim))
