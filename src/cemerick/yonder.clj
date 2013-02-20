(ns cemerick.yonder
  (:require [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.server :as server]
            [clojure.core.match :as match]
            [backtick :refer (template)]
            [clojure.java.browse :refer (browse-url)]
            [clojure.walk :refer (prewalk postwalk walk)])
  (:import clojure.tools.nrepl.transport.Transport)
  (:refer-clojure :exclude (eval)))

(def ^:dynamic *repl-session*)

(defn eval*
  ([expr] (eval* *repl-session* expr))
  ([session expr]
    (nrepl/message session
      {:op "eval" :code (if (string? expr) expr (nrepl/code* expr))})))

;; TODO should be private (macro usage)
(defn eval-value
  [expr responses]
  (when-let [combined (and (->> responses (mapcat :status) (some #{"eval-error"}))
                        (nrepl/combine-responses responses))]
    (throw (ex-info "Failed to evaluate" {:expr expr :combined-response combined})))
  (-> responses nrepl/response-values first))

(defmacro eval
  ([expr] `(eval *repl-session* ~expr))
  ([session expr]
    `(let [code# (template ~expr)]
       (eval-value code# (eval* ~session code#)))))

(defn open-session
  [repl]
  (let [md (meta repl)]
    (cond
      (::prepped md) repl 
      (-> md ::nrepl/taking-until :session) (vary-meta repl assoc ::prepped true)
      (::nrepl/transport md) (recur (nrepl/client-session repl))
      (instance? Transport repl) (recur (nrepl/client repl Long/MAX_VALUE))
      (string? repl) (let [conn (nrepl/url-connect repl)]
                       (recur (vary-meta update-in [::close-fns]
                                cons (fn close-new-connection [] (.close conn)))))
      (map? repl) (let [[conn stop-server]
                        (match/match [repl]
                          [{:new-server args}]
                          (let [server (apply server/start-server (mapcat identity args))]
                            [(nrepl/connect :port (:port server))
                             (fn stop-new-server [] (server/stop-server server))])
                          
                          [{:connection conn}] [conn (fn no-op-connection [])])]
                    (-> (open-session conn)
                      ((:prepare repl identity))
                      (vary-meta update-in [::close-fns] concat [stop-server])))
      :else (throw (IllegalArgumentException.
                     (str "don't know how to make an nREPL session out of " (type repl)))))))

(defn close-session
  [session]
  (doseq [f (-> session meta ::close-fns)]
    (f))
  session)

(defmacro with-session
  [[name repl] & body]
  `(let [~name (open-session ~repl)]
     (binding [*repl-session* ~name]
       (try
         ~@body
         (finally
           (close-session ~name))))))

#_(defn- reset-browser-repl-server
  []
  (-> @cljs.repl.server/state :socket .close))

;; TODO move cljs-specific stuff into piggieback eventually
(require 'cemerick.piggieback)
(require 'cljs.repl.browser)
(defn prepare-rhino
  [session]
  (eval session (cemerick.piggieback/cljs-repl))
  (vary-meta session update-in [::close-fns]
    conj (fn shutdown-cljs-repl [] (eval session :cljs/quit))))

(defn phantomjs-url-open
  ([url] (phantomjs-url-open "phantomjs" url))
  ([path-to-phantomjs url]
    {:pre [(string? url) (string? path-to-phantomjs)]}
    (let [f (java.io.File/createTempFile "yonder_phantomjs" ".js")]
      ;; TODO bother cleaning up the temp script?
      (spit f (format "var page = require('webpage').create(); page.open(%s);" (pr-str url)))
      (.. Runtime getRuntime (exec (into-array String [path-to-phantomjs (.getAbsolutePath f)]))))))

(defrecord PhantomjsEnv [browser-env phantomjs-process]
  cljs.repl/IJavaScriptEnv
  (-setup [this] (cljs.repl/-setup browser-env))
  (-evaluate [this a b c] (cljs.repl/-evaluate browser-env a b c))
  (-load [this ns url] (cljs.repl/-load browser-env ns url))
  (-tear-down [_]
    (cljs.repl/-tear-down browser-env)
    (.destroy phantomjs-process)))

(defn phantomjs-env
  [& {:keys [phantomjs-path browser-repl-port url]
      :or {phantomjs-path "phantomjs"
           browser-repl-port 9000
           url "http://localhost:8080"}}]
  (let [browser-env (cljs.repl.browser/repl-env :port browser-repl-port)
        phantom-env (PhantomjsEnv. browser-env nil)]
    (cljs.repl/-setup phantom-env)
    (assoc phantom-env
      :phantomjs-process (phantomjs-url-open phantomjs-path url))))

(defn prepare-phantomjs
  ([session] (prepare-phantomjs nil session))
  ([phantomjs-env-options session]
    (eval session (cemerick.piggieback/cljs-repl
                    :repl-env (cemerick.yonder/phantomjs-env
                                ~@(mapcat identity phantomjs-env-options))))
    (vary-meta session update-in
        [::close-fns]
        conj (fn shutdown-phantomjs-repl [] (eval session :cljs/quit)))))
