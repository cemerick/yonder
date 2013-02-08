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

;; TODO need to make the returned session .close-able, cascading to any started servers 
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

;; TODO move cljs-specific stuff into piggieback eventually
(require 'cemerick.piggieback)
(defn prepare-cljs
  [session]
  (eval session (cemerick.piggieback/cljs-repl))
  session)

;; TODO add hooks so this works with webdriver et al.
(require 'cljs.repl.browser)
(defn prepare-cljs-browser
  ([session] (prepare-cljs-browser "http://localhost:8080" 9000 session))
  ([browser-url browser-repl-port session]
    (eval session (cemerick.piggieback/cljs-repl
                    :repl-env (doto (cljs.repl.browser/repl-env :port 9000 #_browser-repl-port)
                                cljs.repl/-setup)))
    (browse-url browser-url)
    session))