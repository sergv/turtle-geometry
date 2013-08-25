
(use '[clojure.java.io :only (file)])

(def project-key-def-file "project-key.clj")

(def key-def-info
  (let [f (file project-key-def-file)]
    (if (.exists f)
      (read-string (slurp project-key-def-file))
      nil)))


(defproject turtle_geometry/turtle_geometry "0.9.0"
  :description "Application intepreting LOGO programs in an interactive fashion.
Intended for use with Abelson's book going under the same name."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"

  :warn-on-reflection true

  :source-paths ["src/clojure"]
  :java-source-paths ["src/java" "bin"]
  :javac-options ["-target" "1.6" "-source" "1.6"]

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
  :profiles {:dev {:dependencies [[org.clojure/tools.nrepl "0.2.1"]]
                   :android {:aot :all-with-unused
                             :start-nrepl-server true}}
             :release {:android {:aot :all}}}

  :plugins [[lein-droid "0.1.0-preview5-enhanced"]]
  :android ~(merge
             {:sdk-path "/home/sergey/projects/android/android-sdk-linux"
              :gen-path "bin"

              :enable-dynamic-compilation false
              :repl-device-port 10001
              :repl-local-port 10001

              :accept-file-func
              (fn [^String name]
                (or (and (re-find #"neko/init.*\.class$" name)
                         (not (re-find #"compilation" name)))

                    (re-find #"TurtleGraphics.*interact" name)
                    (and (re-find #"android/support/v4/app" name)
                         (not (re-find #"Notification" name))
                         (not (re-find #"NavUtils" name))
                         (not (re-find #"ShapeCompat" name)))

                    (re-find #"clojure/stacktrace__init" name)
                    (re-find #"clojure/template__init" name)
                    ;; (re-find #"clojure/uuid__init" name)
                    ;; (re-find #"clojure/uuid\$loading" name)
                    (re-find #"clojure/walk__init" name)
                    (re-find #"clojure/core\$bean\.class" name)

                    (and (re-find #".*\.class$" name)
                         (not (re-find #"clojure.*bean" name))

                         ;; (not (re-find #"clojure/asm" name))
                         (not (re-find #"clojure/data" name))
                         (not (re-find #"clojure/inspector" name))
                         ;; (not (re-find #"clojure/instant" name))
                         ;; (not (re-find #"clojure/java" name))
                         (not (re-find #"clojure/lang/.*XML" name))
                         (not (re-find #"clojure/main" name))
                         (not (re-find #"clojure/reflect" name))
                         (not (re-find #"clojure/repl" name))
                         (not (re-find #"clojure/.*pretty_writer" name))
                         (not (re-find #"clojure/.*pprint" name))
                         (not (re-find #"clojure/stacktrace" name))
                         (not (re-find #"clojure/template" name))
                         (not (re-find #"clojure/test" name))
                         ;; (not (re-find #"clojure/uuid" name))
                         (not (re-find #"clojure/walk" name))
                         (not (re-find #"clojure/xml" name))
                         (not (re-find #"clojure/zip" name))

                         ;; (not (re-find #"clojure/core\$emit_protocol" name))
                         ;; (not (re-find #"clojure/core\$emit_method_builder" name))
                         ;; (not (re-find #"clojure/core\$emit_hinted" name))
                         ;; (not (re-find #"clojure/core\$emit_impl" name))
                         ;; (not (re-find #"clojure/core\$emit_deftype" name))
                         ;; (not (re-find #"clojure/core\$emit_extend" name))

                         (not (re-find #"android/clojure/graphic/Transformable" name))
                         (not (re-find #"android/clojure/graphic/Transformation" name))
                         (not (re-find #"android/clojure/graphic/transformation" name))

                         (not (re-find #"android/support/v4" name))


                         (not (re-find #"android/annotation" name))
                         ;; (not (re-find #"android/support/v4" name))

                         (not (re-find #"com/sattvik" name))
                         (not (re-find #"com/android" name))

                         (not (re-find #"build/.*" name))
                         (not (re-find #"clojure/java/browse_ui.*" name))
                         (not (re-find #"clojure/java/javadoc.*" name))
                         (not (re-find #"clojure/java/shell.*" name))
                         (not (re-find #"clojure/lang/Repl" name))
                         (not (re-find #"clojure/lang/Script" name))
                         (not (re-find #"interact.*" name))
                         (not (re-find #"using/Active" name))
                         (not (re-find #"jsint/Listener" name))
                         (not (re-find #"jsint/Listener11" name))
                         (not (re-find #"jsint/Listener11swing" name))
                         (not (re-find #"jsint/SchemeApplet" name))
                         )))
              :proguard-opts ["-ignorewarnings"]
              :proguard-conf-path "proguard.cfg"

              :external-classes-paths ["/home/sergey/projects/android/android-sdk-linux/extras/android/support/v4/android-support-v4.jar"
                                       "/mnt/disk3/projects/scheme/jscheme/lib/jscheme.jar"]

              ;; Uncomment this if dexer fails with OutOfMemoryException
              ;; :force-dex-optimize true
              :dex-opts ["-JXmx4096M" "--num-threads=2"]
              ;; :dex-aux-opts []

              :min-version "10"
              :target-version "15"
              :aot-exclude-ns ["clojure.parallel" "clojure.core.reducers"]}
             key-def-info))


;; Local Variables:
;; clojure-compile/lein-command: "LEIN_JAVA_CMD=/usr/lib/jvm/java-7-openjdk-amd64/jre/bin/java lein with-profiles %s do droid code-gen, droid compile, droid create-dex, droid apk, droid install, droid run"
;; nrepl-server-command: "lein do droid forward-port, droid repl :headless"
;; End:

