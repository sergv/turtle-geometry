
;;;; prelude

(ns org.turtle.geometry.TurtleGraphics
  (:gen-class :main no
              :extends android.app.Activity
              :exposes-methods {onCreate superOnCreate
                                onResume superOnResume
                                onPause superOnPause}
              :state state
              :init init
              :constructors {[] []})
  (:import [org.turtle.geometry.R]
           [android.app.Activity]
           [android.os.AsyncTask]
           [android.view.View]
           [android.view.SurfaceView]
           [android.widget.Button]
           [android.widget.ProgressBar]
           [android.util.Log]

           [java.util.concurrent.LinkedBlockingQueue]
           [java.util.concurrent.ThreadPoolExecutor]
           [java.util.concurrent.TimeUnit])
  (:use [clojure.tools.nrepl.server :only (start-server stop-server)]
        [neko.init]
        [android.clojure.util :only (make-ui-dimmer)]))

(defn log [msg]
  (android.util.Log/d "TurtleGeometry" msg))

;;;; overridden methods

(def ^{:dynamic true} *activity* (atom nil))
(def ^{:dynamic true} *task-runner* (atom nil))


(defrecord ActivityState [^android.view.SurfaceView drawing-area
                          ^android.widget.ProgressBar progress-bar])

(defn -init []
  [[] (ActivityState. (atom nil)
                      (atom nil))])

(defmacro resource
  ([id] `(resource :id ~id))
  ([resource-type resource-name]
     `(. ~(cond (= :id resource-type)
                org.turtle.geometry.R$id
                (= :layout resource-type)
                org.turtle.geometry.R$layout
                :else
                (throw java.lang.Exception
                       (str "invalid resource type " resource-type)))
         ~(symbol (name resource-name)))))

(defn make-task [^android.app.Activity activity
                 ^android.widget.ProgressBar progress-bar]
  (let [run-on-ui-thread (fn [action]
                           (. activity runOnUiThread action))]
    (fn []
      (run-on-ui-thread
       (fn [] (doto progress-bar
                (. setVisibility
                   android.widget.ProgressBar/VISIBLE)
                (. setProgress 0))))
      (doseq [i (take 100 (range))]
        (. java.lang.Thread sleep 100 0)
        (run-on-ui-thread
         (fn [] (. progress-bar
                   incrementProgressBy 1))))
      (run-on-ui-thread
       (fn [] (. progress-bar
                 setVisibility
                 android.widget.ProgressBar/GONE))))))

(defn -onCreate [^org.turtle.geometry.TurtleGraphics this
                 ^android.os.Bundle bundle]
  (reset! *activity* this)
  (reset! *task-runner* (new java.util.concurrent.ThreadPoolExecutor
                             2 ;; core pool size
                             5 ;; max threads
                             1000 ;; keep alive time
                             java.util.concurrent.TimeUnit/MILLISECONDS
                             (new java.util.concurrent.LinkedBlockingQueue)))
  (neko.init/init this :port 10001)
  ;; (defonce *nrepl-server* (start-server :port 10001))
  (doto this
    (. superOnCreate bundle)
    (. setContentView (resource :layout :main)))
  (reset! (.drawing-area ^ActivityState (.state this))
          (.findViewById this (resource :drawing_area)))
  (reset! (.progress-bar ^ActivityState (.state this))
          (.findViewById this (resource :progress_bar)))
  (make-ui-dimmer this (.findViewById this (resource :main_layout)))

  (doto ^android.widget.ProgressBar @(.progress-bar ^ActivityState (.state this))
        (. setVisibility android.widget.ProgressBar/GONE)
        (. setProgress 0)
        (. setMax 100))

  (let [^org.turtle.geometry.TurtleGraphics activity this]
    (. ^android.widget.Button
       (. this findViewById (resource :button_run))
       setOnClickListener
       (proxy [android.view.View$OnClickListener] []
         (onClick [^android.view.View button]
           (. @*task-runner* execute
              (make-task activity @(.progress-bar (.state activity))))
           (log "Clicked run button"))))))

(defn -onResume [^org.turtle.geometry.TurtleGraphics this]
  (. this superOnResume))

(defn -onPause [^org.turtle.geometry.TurtleGraphics this]
  (. this superOnPause))


;; Local Variables:
;; compile-command: "lein do droid code-gen, droid compile, droid create-dex, droid apk, droid install, droid run"
;; End:
