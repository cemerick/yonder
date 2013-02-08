(ns cemerick.yonder
  (:require [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.server :as server]
            [clojure.core.match :as match]
            [clojure.java.browse :refer (browse-url)]
            [clojure.walk :refer (prewalk postwalk)])
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
    ;; essentially a limited, ad-hoc unhygenic (that part is necessary) syntax-quote
    ;; could probably make this one walk
    (let [expr (postwalk (fn [x]
                           (if-let [unquote (and (seq? x)
                                              (= 'clojure.core/unquote (first x))
                                              (second x))]
                             (with-meta unquote {::unquote true})
                             x))
                 expr)
          expr (postwalk (fn [x]
                           (cond
                             (-> x meta ::unquote) x
                             (seq? x) (list* 'list x)
                             (symbol? x) (list 'quote x)
                             :else x))
                 expr)]
      `(let [code# ~expr]
         (eval-value code# (eval* ~session code#))))))
 
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

;; TODO move cljs-specific stuff into piggieback eventually
(require 'cemerick.piggieback)
(defn prepare-cljs
  [session]
  (eval session (cemerick.piggieback/cljs-repl))
  (vary-meta session update-in [::close-fns]
    conj (fn shutdown-cljs-repl [] (eval session :cljs/quit))))

(require 'cljs.repl.browser)
(defn prepare-cljs-browser
  ([session] (prepare-cljs-browser nil session))
  ([{:keys [open-browser-fn url browser-repl-port]
     :or {open-browser-fn browse-url
          url "http://localhost:8080"
          browser-repl-port 9000}}
    session]
    (eval session (cemerick.piggieback/cljs-repl
                    :repl-env (doto (cljs.repl.browser/repl-env :port ~browser-repl-port)
                                cljs.repl/-setup)))
    (let [maybe-process (open-browser-fn url)]
      (vary-meta session update-in
        [::close-fns]
        into (concat
               (when (instance? java.lang.Process maybe-process)
                 [(fn destroy-phantomjs-process [] (.destroy maybe-process))])
               [(fn shutdown-cljs-repl [] (eval session :cljs/quit))])))))

(defn phantomjs-url-open
  ([url] (phantomjs-url-open "phantomjs" url))
  ([path-to-phantomjs url]
    {:pre [(string? url) (string? path-to-phantomjs)]}
    (let [f (java.io.File/createTempFile "yonder_phantomjs" ".js")]
      (spit f (format "var page = require('webpage').create(); page.open(%s);" (pr-str url)))
      (.. Runtime getRuntime (exec (into-array String [path-to-phantomjs (.getAbsolutePath f)]))))))