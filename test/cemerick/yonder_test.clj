(ns cemerick.yonder-test
  (:require [cemerick.yonder :as yonder]
            [compojure.core :refer (GET defroutes)]
            [compojure.handler :refer (site)]
            [ring.adapter.jetty :as jetty]
            [cljs.closure :as closure]
            [clojure.tools.nrepl :as nrepl]
            (clojure.tools.nrepl [server :as server]))
  (:use clojure.test))

(defonce browser-repl
  (memoize (constantly (closure/build "test"
                         {:optimizations :whitespace :pretty-print true}))))

(defroutes app
  (GET "/yonder-test.js" [] (browser-repl))
  (GET "/" [] "<html>
        <head>
          <meta charset='UTF-8'>
          <title>Browser-connected REPL for yonder</title>
        </head>
        <body>
          <div id='content'>
            <script type='text/javascript' src='/yonder-test.js'></script>
            <script type='text/javascript'>
              goog.require('cemerick.yonder_browser');
            </script>
          </div>
        </body>
      </html>"))

(defn- with-webapp
  [f]
  (let [http (ring.adapter.jetty/run-jetty (site #'app) {:port 8080 :join? false})]
    (try
      (f)
      (finally (.stop http)))))

(use-fixtures :once #'with-webapp)

(defn- cljs-sanity
  [session]
  (is (= 6 (yonder/eval session (+ 1 2 3))))
  (is (= 190 (yonder/eval session (reduce + (range 20)))))
  (is (= [:a 'b ::c "d" 1 1.2 #{} () {:x :y}]
          (yonder/eval session (into [] (js/Array :a 'b ::c "d" 1 1.2 #{} () {:x :y})))))
  (yonder/eval session (ns foo))
  (yonder/eval session (defn keywordize [x] (keyword x)))
  (yonder/eval session (in-ns 'cljs.user))
  (is (= :bar (yonder/eval session (foo/keywordize "bar")))))

(deftest browser-repl-sanity
  (let [s (yonder/open-session
            {:prepare yonder/prepare-cljs-browser
             :new-server
             {:handler (clojure.tools.nrepl.server/default-handler
                         #'cemerick.piggieback/wrap-cljs-repl)}})]
    (cljs-sanity s)
    (is (= 42 (yonder/eval s (cemerick.yonder-browser/test-function 21))))))

(deftest rhino-sanity
  (yonder/with-session [s (yonder/open-session
                            {:prepare yonder/prepare-cljs
                             :new-server
                             {:handler (clojure.tools.nrepl.server/default-handler
                                         #'cemerick.piggieback/wrap-cljs-repl)}})]
    (cljs-sanity s)))

(deftest phantomjs-sanity
  (yonder/with-session [s (yonder/open-session
                            {:prepare (partial yonder/prepare-cljs-browser
                                        {:open-browser-fn yonder/phantomjs-url-open})
                             :new-server
                             {:handler (clojure.tools.nrepl.server/default-handler
                                         #'cemerick.piggieback/wrap-cljs-repl)}})]
    (cljs-sanity s)))