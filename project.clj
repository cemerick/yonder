(defproject com.cemerick/yonder "0.0.2-SNAPSHOT"
  :description "Go eval this Clojure[Script] over there."
  :url "http://github.com/cemerick/yonder"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.5.0-RC4"]
                 
                 [org.clojure/tools.nrepl "0.2.2-SNAPSHOT"]
                 
                 ;; TODO this should be made optional eventually, move all the cljs-specific
                 ;; stuff into...piggieback?
                 [com.cemerick/piggieback "0.0.2" #_#_:optional true]
                 
                 [org.clojure/core.match "0.2.0-alpha12"]
                 [backtick "0.3.0-SNAPSHOT"]
                 
                 ;; TODO remove before deploying
                 [org.clojure/clojurescript "0.0-1586"]]
  
  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
  
  ; ensures that `lein test` runs will always exit, no AWT thread opening a browser  
  :jvm-opts ["-Djava.awt.headless=true"]
  
  :profiles {:dev {:dependencies [[compojure "1.1.0"]
                                  [ring/ring-jetty-adapter "1.1.0"]]}}
  
  :repositories {"snapshots" {:url "https://oss.sonatype.org/content/repositories/snapshots/"}}
  :deploy-repositories {"releases" {:url "https://oss.sonatype.org/service/local/staging/deploy/maven2/" :creds :gpg}
                        "snapshots" {:url "https://oss.sonatype.org/content/repositories/snapshots/" :creds :gpg}}
  
  :aliases  {"all" ["with-profile" "dev"]}
  
  ;;maven central requirements
  :scm {:url "git@github.com:cemerick/yonder.git"}
  :pom-addition [:developers [:developer
                              [:name "Chas Emerick"]
                              [:url "http://cemerick.com"]
                              [:email "chas@cemerick.com"]
                              [:timezone "-5"]]])
