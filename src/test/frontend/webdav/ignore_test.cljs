(ns frontend.webdav.ignore-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.webdav.ignore :as ignore]))

(deftest ignored-path?
  (testing "excluded"
    (doseq [p ["logseq/bak/foo.md"
               "logseq/bak/2026/x.md"
               "logseq/version-files/local/x.md"
               "logseq/webdav/conflicts/20261007/a.md"
               "logseq/.recycle/x.md"
               ".git/config"
               ".DS_Store"
               "pages/.hidden.md"
               "node_modules/foo/index.js"
               "assets/foo.icloud"
               "tmp/foo.tmp"
               "assets/image.PNG.part"]]
      (is (true? (ignore/ignored-path? p)) p)))
  (testing "kept"
    (doseq [p ["pages/a.md"
               "journals/2026_10_07.md"
               "assets/image.png"
               "logseq/config.edn"
               "logseq/custom.css"
               "whiteboards/board.excalidraw"]]
      (is (false? (ignore/ignored-path? p)) p))))

(deftest text-file?
  (doseq [p ["pages/a.md" "a.markdown" "a.org" "logseq/config.edn"
             "logseq/custom.css" "whiteboards/b.excalidraw" "x.tldr"]]
    (is (true? (ignore/text-file? p)) p))
  (doseq [p ["assets/a.png" "assets/a.jpg" "a.pdf" "a.bin" "noext"]]
    (is (false? (ignore/text-file? p)) p)))

(deftest file-size-ok?
  (is (true? (ignore/file-size-ok? 1024 1)))
  (is (false? (ignore/file-size-ok? (* 2 1024 1024) 1)))
  (is (true? (ignore/file-size-ok? nil 1))))

(deftest external-artifact?
  (is (true? (ignore/external-artifact? "pages/a.md.icloud")))
  (is (true? (ignore/external-artifact? "pages/a.sync-conflict-20260101-000000-AAAA.md")))
  (is (true? (ignore/external-artifact? "pages/a (conflicted copy).md")))
  (is (false? (ignore/external-artifact? "pages/a.md"))))
