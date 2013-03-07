(defproject turtle_geometry/turtle_geometry "0.0.1-SNAPSHOT"
  :description "Application intepreting LOGO programs in an interactive fashion.
Intended for use with Abelson's book going under the same name."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"

  :warn-on-reflection true

  :source-paths ["src/clojure"]
  :java-source-paths ["src/java" "gen"]
  ;; The following two definitions are optional. The default
  ;; target-path is "target", but you can change it to whatever you like.
  :target-path "bin"
  :compile-path "bin/classes"

  ;; :local-repo "/home/sergey/.m2/repository/"
  :repositories [["local" "file:///home/sergey/.m2/repository/"]]
  :dependencies [[android/clojure "1.4.0-android"]
                 [neko/neko "2.0.0-beta1"]
                 [android-utils/android-utils "0.1.0"]]
  :profiles {:dev {:dependencies [[org.clojure/tools.nrepl "0.2.1"]
                                  ;; [android/tools.nrepl "0.2.0-bigstack"]
                                  ]
                   :android {:aot :all-with-unused}}
             :release {:android
                       {;; Specify the path to your private
                        ;; keystore and the the alias of the
                        ;; key you want to sign APKs with.
                        ;; :keystore-path "/home/user/.android/private.keystore"
                        ;; :key-alias "mykeyalias"
                        :aot :all}}}

  ;; :repl-options {:init-ns org.accel.clojure.AccelerationTracker
  ;;                ;; This expression will run when first opening a REPL, in the
  ;;                ;; namespace from :init-ns or :main if specified.
  ;;                :init nil
  ;;                ;; Customize the socket the repl task listens on and
  ;;                ;; attaches to.
  ;;                :host "localhost"
  ;;                :port 10001}

  :plugins [[lein-droid "0.1.0-beta6-enhanced-dex"]]
  :android {:sdk-path "/home/sergey/projects/android/android-sdk-linux"

            ;; Uncomment this if dexer fails with OutOfMemoryException
            ;; :force-dex-optimize true
            :dx-aux-arguments ["--num-threads=4" "--statistics"]

            :min-version "10"
            :target-version "15"
            :aot-exclude-ns ["clojure.parallel"]})