(ns cemerick.yonder
  (:require [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.server :as server]
            [clojure.core.match :as match]
            [clojure.java.browse :refer (browse-url)])
  (:import clojure.tools.nrepl.transport.Transport)
  (:refer-clojure :exclude (eval)))

(def ^:dynamic *repl-session*)

(defn- code
  "Returns a string representation of the given expression (using
   `clojure.tools.nrepl/code*`); if the expression is itself a string,
   returns it unmodified."
  [expr]
  (if (string? expr)
    expr
    (nrepl/code* expr)))

(defn eval*
  ([expr] (eval* *repl-session* expr))
  ([session expr]
    {:pre [(string? expr)]}
    (nrepl/message session {:op "eval" :code expr})))

(defn eval-value
  [expr responses]
  (when-let [combined (and (->> responses (mapcat :status) (some #{"eval-error"}))
                        (nrepl/combine-responses responses))]
    (throw (ex-info "Failed to evaluate" {:expr expr :combined-response combined})))
  (-> responses nrepl/response-values first))

(defmacro eval
  ([expr] `(eval ~*repl-session* ~expr))
  ([session expr]
    `(let [code# ~(code expr)]
       (eval-value code# (eval* ~session code#)))))

;; TODO need to make the returned session .close-able, especially for when new servers are started
(defn prep-session
  [repl]
  (let [md (meta repl)]
    (cond
      (::prepped md) repl 
      (-> md ::nrepl/taking-until :session) (vary-meta repl assoc ::prepped true)
      (::nrepl/transport md) (recur (nrepl/client-session repl))
      (instance? Transport repl) (recur (nrepl/client repl Long/MAX_VALUE))
      (string? repl) (recur (nrepl/url-connect repl))
      (map? repl) (let [conn (match/match [repl]
                               [{:new-server args}]
                               (let [server (apply server/start-server (mapcat identity args))]
                                 (nrepl/connect :port (:port server)))
                               
                               [{:connection conn}] conn)
                        session (prep-session conn)]
                    ((:prepare repl identity) session))
      :else (throw (IllegalArgumentException.
                     (str "don't know how to make an nREPL session out of " (type repl)))))))

(defmacro with-session
  [session & body]
  `(binding [*repl-session* ~session]
     ~@body))



;; TODO move cljs-specific stuff into piggieback eventually
(require 'cemerick.piggieback)
(defn prepare-cljs
  [session]
  (eval session (cemerick.piggieback/cljs-repl))
  session)

(require 'cljs.repl.browser)
(defn prepare-cljs-browser
  ([session] (prepare-cljs-browser "http://localhost:8080" 9000 session))
  ([browser-url browser-repl-port session]
    (eval session (cemerick.piggieback/cljs-repl
                    :repl-env (doto (cljs.repl.browser/repl-env :port 9000 #_browser-repl-port)
                                cljs.repl/-setup)))
    (browse-url browser-url)
    session))