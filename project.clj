
(use '[clojure.java.io :only (file)])

(def project-key-def-file "project-key.clj")

(def key-def-info
  (let [f (file project-key-def-file)]
    (if (.exists f)
      (read-string (slurp project-key-def-file))
      nil)))


(defproject turtle_geometry/turtle_geometry "0.0.1-SNAPSHOT"
  :description "Application intepreting LOGO programs in an interactive fashion.
Intended for use with Abelson's book going under the same name."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"

  :warn-on-reflection true

  :source-paths ["src/clojure"]
  :java-source-paths ["src/java" "bin"]
  ;; The following two definitions are optional. The default
  ;; target-path is "target", but you can change it to whatever you like.
  :target-path "bin"
  :compile-path "bin/classes"

  ;; :local-repo "/home/sergey/.m2/repository/"
  :repositories [["local" "file:///home/sergey/.m2/repository/"]]
  :dependencies [[android/clojure "1.5.0"]
                 [neko/neko "2.0.0-beta3-enhanced"]
                 [android-utils/android-utils "0.6.0-no-drawer"]
                 [org.clojure/math.numeric-tower "0.0.2"]
                 ;; [org.antlr/antlr "3.5"]
                 ]
  :profiles {:dev {:dependencies [[org.clojure/tools.nrepl "0.2.1"]
                                  ;; [android/tools.nrepl "0.2.0-bigstack"]
                                  ]
                   :android {:aot :all-with-unused}}
             :release {:android {:aot :all}}}

  :plugins [[lein-droid "0.1.0-preview5-enhanced"]]
  :android ~(merge
             {:sdk-path "/home/sergey/projects/android/android-sdk-linux"
              :gen-path "bin"

              :enable-dynamic-compilation true
              :start-nrepl-server true
              :repl-device-port 10001
              :repl-local-port 10001

              :external-classes-paths ["/home/sergey/projects/android/android-sdk-linux/extras/android/support/v4/android-support-v4.jar"]

              ;; Uncomment this if dexer fails with OutOfMemoryException
              ;; :force-dex-optimize true
              :dex-opts ["-JXmx4096M"]
              :dex-aux-opts ["--num-threads=2"]

              :min-version "10"
              :target-version "15"
              :aot-exclude-ns ["clojure.parallel" "clojure.core.reducers"]}
             key-def-info))


;; Local Variables:
;; clojure-compile/lein-command: "LEIN_JAVA_CMD=/usr/lib/jvm/java-7-openjdk-amd64/jre/bin/java lein with-profiles %s do droid code-gen, droid compile, droid create-dex, droid apk, droid install, droid run"
;; nrepl-server-command: "lein do droid forward-port, droid repl :headless"
;; End:

