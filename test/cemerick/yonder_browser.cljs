(ns cemerick.yonder-browser
  (:require [clojure.browser.repl :as repl]))

(repl/connect "http://localhost:9000/repl")