(ns frontend.plugin.preferences
  "Migration helpers for plugin user preferences.

   Lives outside logseq.api on purpose: the API namespace pulls in the
   window/react stack (react-tippy needs a DOM Element at load), so a unit test
   that required it cannot run under bare Node. These helpers only need
   frontend.util, which the test suite already loads."
  (:require [clojure.string :as string]
            [frontend.util :as util]))

(defn migrate-theme-url
  "Rewrite an old file:// theme URL to assets://.

   1.0.x persisted the theme URL AFTER the host rewrote it with
   assets-theme-to-file (file:// was the only scheme the file:// renderer could
   load). The renderer now runs at lsp://logseq.com, which refuses file://
   subresources, so on upgrade a persisted theme silently stops applying unless
   the scheme is rewritten. assets:// serves the same path
   (resolveAssetsSchemeUrl decodes and contains it). Bracket access is
   deliberate: Closure :advanced renames unquoted foreign properties in release
   builds, and this object is parsed JSON with no extern to write against."
  [theme]
  (let [url (and theme (aget theme "url"))]
    (if (and (string? url) (string/starts-with? url "file://"))
      (doto theme (aset "url" (str "assets:" (subs url 5))))
      theme)))

(defn migrate-user-preferences
  "Migrate theme URLs in a user-preferences object in place; returns it.

   Called on load AND save: an old persisted file:// theme is repaired when it
   is read, and a stale in-memory copy cannot write file:// back over it. No-op
   off Electron -- the web/mobile builds have no assets:// handler."
  [^js prefs]
  (when (and (util/electron?) prefs)
    (when-let [themes (aget prefs "themes")]
      (aset themes "light" (migrate-theme-url (aget themes "light")))
      (aset themes "dark" (migrate-theme-url (aget themes "dark"))))
    (aset prefs "theme" (migrate-theme-url (aget prefs "theme"))))
  prefs)
