
;;;; prelude

(ns org.turtle.geometry.TurtleGraphics
  (:gen-class :main no
              :extends android.app.Activity
              :exposes-methods {onCreate superOnCreate
                                onResume superOnResume
                                onPause superOnPause}
              :state ^{:tag ActivityState} state
              :init init
              :constructors {[] []})
  (:import [org.turtle.geometry.R]
           [android.app.Activity]
           [android.content.Context]
           [android.graphics.Canvas]
           [android.graphics.Color]
           [android.graphics.Paint]
           [android.os.AsyncTask]
           [android.view.MotionEvent]
           [android.view.View]
           [android.view.SurfaceView]
           [android.widget.Button]
           [android.widget.EditText]
           [android.widget.ProgressBar]
           [android.util.AttributeSet]
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
                          ^android.widget.ProgressBar progress-bar
                          ^android.widget.EditText program-source-editor])

(defn -init []
  [[] (ActivityState. (atom nil)
                      (atom nil)
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
                (. setProgress 0)
                (. setMax 100))))
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
                             2    ;; core pool size
                             5    ;; max threads
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
  (reset! (.program-source-editor ^ActivityState (.state this))
          (.findViewById this (resource :program_input)))
  (make-ui-dimmer this (.findViewById this (resource :main_layout)))

  (doto ^android.widget.ProgressBar @(.progress-bar ^ActivityState (.state this))
        (. setVisibility android.widget.ProgressBar/GONE))
  (. ^android.widget.EditText @(.program-source-editor ^ActivityState (.state this))
     setOnTouchListener
     (let [last-time (atom nil)]
       (proxy [android.view.View$OnTouchListener] []
         (onTouch [^android.view.View view
                   ^android.view.MotionEvent event]
           (cond (= android.view.MotionEvent/ACTION_DOWN (. event getActionMasked))
                 (let [current-time (. java.lang.System currentTimeMillis)]
                   (log "Touched source editor")
                   (if @last-time
                     (if (< (java.lang.Math/abs ^long (- @last-time
                                                         current-time))
                            500)
                       (do (log "Double tap on source editor")
                           (reset! last-time nil)
                           true)
                       (do (reset! last-time current-time)
                           false))
                     (do (reset! last-time current-time)
                         false)))
                 :else
                 false)))))

  (let [^org.turtle.geometry.TurtleGraphics activity this]
    (. ^android.widget.Button (. this findViewById (resource :button_run))
       setOnClickListener
       (proxy [android.view.View$OnClickListener] []
         (onClick [^android.view.View button]
           (. @*task-runner* execute
              (make-task activity @(.progress-bar ^ActivityState (.state activity))))
           (log "Clicked run button"))))))

(defn -onResume [^org.turtle.geometry.TurtleGraphics this]
  (. this superOnResume))

(defn -onPause [^org.turtle.geometry.TurtleGraphics this]
  (. this superOnPause))

;;;;

(defrecord ViewState [drawing-func])

(gen-class :name org.turtle.geometry.TurtleView
           :main false
           :extends android.view.View
           :state view_state
           :init view-init
           :exposes-methods {onDraw superOnDraw})

(defn -view-init
  ([^android.content.Context context]
     [[context] (ViewState. (atom nil))])
  ([^android.content.Context context ^android.util.AttributeSet attrs]
     [[context attrs] (ViewState. (atom nil))])
  ([^android.content.Context context ^android.util.AttributeSet attrs defStyle]
     [[context attrs defStyle] (ViewState. (atom nil))]))

(defn color->paint
  ([argb]
     (let [p (new android.graphics.Paint)]
       (. p setColor argb)
       p))
  ([alpha red green blue]
     (color->paint (android.graphics.Color/argb alpha red green blue))))

(defn draw-grid [^android.graphics.Canvas canvas
                 n
                 ^android.graphics.Paint paint]
  (let [width (. canvas getWidth)
        height (. canvas getHeight)
        dx (/ width n)
        dy (/ height n)]
    (loop [i 0]
      (. canvas drawLine (* i dx) 0 (* i dx) height paint)
      (. canvas drawLine 0 (* i dy) width (* i dy) paint)
      (when (not= i n)
        (recur (+ i 1))))))

(defn -onDraw [^org.turtle.geometry.TurtleView this
               ^android.graphics.Canvas canvas]
  (log "onDraw")
  (. canvas drawColor android.graphics.Color/WHITE)
  (draw-grid canvas 10 (color->paint android.graphics.Color/BLACK))
  (when-let [func @(.drawing-func ^ViewState (.view_state this))]
    (func canvas)))


;; Local Variables:
;; compile-command: "lein do droid code-gen, droid compile, droid create-dex, droid apk, droid install, droid run"
;; End:
