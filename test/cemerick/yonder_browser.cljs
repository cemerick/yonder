(ns cemerick.yonder-browser
  (:require [clojure.browser.repl :as repl]))

(repl/connect "http://localhost:9000/repl")

(defn ^:export test-function [x] (* x 2))