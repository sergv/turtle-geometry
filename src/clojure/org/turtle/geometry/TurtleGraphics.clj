
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
           [android.app Activity]
           [android.content Context]
           [android.graphics Bitmap BitmapFactory Canvas Color Matrix Paint Rect]
           [android.os AsyncTask]
           [android.text SpannableStringBuilder]
           [android.view Menu MenuInflater MenuItem]
           [android.view MotionEvent SurfaceHolder SurfaceView View Window]
           [android.widget Button EditText ProgressBar TextView]
           [android.util AttributeSet]
           [android.util.Log]

           [java.io BufferedWriter StringWriter]
           [java.util.concurrent
            ConcurrentLinkedQueue
            LinkedBlockingQueue
            ThreadPoolExecutor
            TimeUnit]

           [android.clojure IndependentDrawer])
  (:require [android.clojure.util]
            [neko.init.options])
  (:use [clojure.tools.nrepl.server :only (start-server stop-server)]
        [clojure.pprint :only (with-pprint-dispatch
                                pprint
                                *print-right-margin*
                                code-dispatch)]
        [neko.init]
        [android.clojure.util :only (make-double-tap-handler)]
        [android.clojure.IndependentDrawer :only (send-drawing-command)]
        [android.clojure.graphic :only (color->paint draw-grid)]
        ;; [org.turtle.geometry.Eval]
        ))

(defn- log
  ([msg] (android.util.Log/d "TurtleGeometry" msg))
  ([msg & args] (log (apply format msg args))))

(defmacro resource
  ([id] `(resource :id ~id))
  ([resource-type resource-name]
     `(. ~(case resource-type
            :id       org.turtle.geometry.R$id
            :layout   org.turtle.geometry.R$layout
            :menu     org.turtle.geometry.R$menu
            :drawable org.turtle.geometry.R$drawable
            (throw java.lang.RuntimeException
                   (str "invalid resource type: " resource-type)))
         ~(symbol (name resource-name)))))

;;;; activity functions

(def ^{:dynamic true} *activity* (atom nil))
(def ^{:dynamic true} *task-runner* (atom nil))

(defn show-progress-bar [^org.turtle.geometry.TurtleGraphics activity]
  (. activity setProgressBarIndeterminateVisibility true))

(defn hide-progress-bar [^org.turtle.geometry.TurtleGraphics activity]
  (. activity setProgressBarIndeterminateVisibility false))

(defrecord ActivityState [^android.clojure.IndependentDrawer drawing-area
                          ^EditText program-source-editor
                          ^TextView error-output
                          ^EditText delay-entry
                          ^Menu menu])

(defn -init []
  [[] (ActivityState. (atom nil :meta {:tag android.clojure.IndependentDrawer})
                      (atom nil :meta {:tag EditText})
                      (atom nil :meta {:tag TextView})
                      (atom nil :meta {:tag EditText})
                      (atom nil :meta {:tag Menu}))])



(defn text-input->int [^EditText input-field]
  (Integer/valueOf ^String
                   (.. input-field
                       (getText)
                       (toString))))

(defn report-error [^org.turtle.geometry.TurtleGraphics activity
                    ^String msg]
  (.setText ^TextView @(.error-output ^ActivityState
                                      (.state activity))
            msg))

(declare make-diminishing-grids-drawer draw-line-interpolated)
(declare eval-turtle-program)

(defn -onCreate [^org.turtle.geometry.TurtleGraphics this
                 ^android.os.Bundle bundle]
  (reset! *activity* this)
  (reset! *task-runner* (new ThreadPoolExecutor
                             2    ;; core pool size
                             5    ;; max threads
                             1000 ;; keep alive time
                             TimeUnit/MILLISECONDS
                             (new LinkedBlockingQueue)))
  (neko.init/init this :port 10001)
  (doto this
    (. superOnCreate bundle)
    (. requestWindowFeature Window/FEATURE_INDETERMINATE_PROGRESS)
    (. setContentView (resource :layout :main))
    (. setProgressBarIndeterminateVisibility false))
  (reset! (.drawing-area ^ActivityState (.state this))
          (.findViewById this (resource :drawing_area)))
  (reset! (.program-source-editor ^ActivityState (.state this))
          (.findViewById this (resource :program_input)))
  (reset! (.error-output ^ActivityState (.state this))
          (.findViewById this (resource :error_output)))
  (reset! (.delay-entry ^ActivityState (.state this))
          (.findViewById this (resource :delay_entry)))
  (android.clojure.util/make-ui-dimmer (.findViewById this
                                                      (resource :main_layout)))

  (.setText @(.program-source-editor ^ActivityState (.state this))
            "(do\n  (forward 100)\n  (left 90)\n  (forward 100))\n")
  (.setOnTouchListener
   ^EditText @(.program-source-editor ^ActivityState (.state this))

   (make-double-tap-handler (fn [] (log "Double tapped source editor twice"))))
  (.setOnTouchListener
   ^TextView @(.error-output ^ActivityState (.state this))
   (make-double-tap-handler (fn [] (log "Double tapped error output twice"))))

  (let [activity ^org.turtle.geometry.TurtleGraphics this
        button ^Button (.findViewById this (resource :button_run))]
    (.setOnClickListener
     button
     (reify android.view.View$OnClickListener
       (onClick [this button]
         (let [delay (text-input->int
                      @(.delay-entry ^ActivityState (.state activity)))]
           (report-error activity "no errors yet")
           (eval-turtle-program (str (.getText @(.program-source-editor ^ActivityState
                                                                        (.state activity))))
                                delay
                                activity))
         (log "Clicked run button"))))

    (.setOnClickListener
     (.findViewById this (resource :button_reindent))
     (reify android.view.View$OnClickListener
       (onClick [this button]
         (let [editor ^TextView @(.program-source-editor ^ActivityState
                                                         (.state activity))
               out-writer ^StringWriter (StringWriter.)
               orig-contents (.getText editor)]
           (try
             (binding [*print-right-margin* 80
                       *out* out-writer]
               (with-pprint-dispatch code-dispatch
                 (pprint (read-string (str orig-contents)))
                 (log "out-writer: %s\n"
                      out-writer)
                 (.setText editor (str *out*))))
             (catch Exception e
               (.setText editor orig-contents)
               (report-error activity (str "error while indenting:\n" e)))))))))

  ;; (let [activity ^org.turtle.geometry.TurtleGraphics this
  ;;       button ^Button (.findViewById this (resource :button_run))
  ;;       turtle-bitmap ^Bitmap (Bitmap/createScaledBitmap
  ;;                              (BitmapFactory/decodeResource
  ;;                               (. activity getResources)
  ;;                               (resource :drawable :turtle_marker))
  ;;                              27
  ;;                              50
  ;;                              true)]
  ;;   (.setOnClickListener
  ;;    button
  ;;    (proxy [android.view.View$OnClickListener] []
  ;;      (onClick [^View button]
  ;;        (let [delay (text-input->int
  ;;                     @(.delay-entry ^ActivityState (.state activity)))]
  ;;          (send-drawing-command @(.drawing-area ^ActivityState
  ;;                                                (.state activity))
  ;;                                {:type :plain
  ;;                                 :actions
  ;;                                 (fn [^Canvas canvas]
  ;;                                   (.drawColor canvas Color/WHITE)
  ;;                                   (Thread/sleep delay 0))})
  ;;
  ;;          (let [turtle-center-x ^double (/ (.getWidth turtle-bitmap) 2)
  ;;                turtle-center-y ^double (/ (.getHeight turtle-bitmap) 2)]
  ;;            (send-drawing-command
  ;;             @(.drawing-area ^ActivityState
  ;;                             (.state activity))
  ;;             {:type :animation
  ;;              :anim-actions
  ;;              (fn [^Canvas canvas t]
  ;;                (draw-line-interpolated canvas
  ;;                                        t
  ;;                                        0
  ;;                                        0
  ;;                                        (.getWidth canvas)
  ;;                                        (.getHeight canvas)
  ;;                                        (color->paint Color/BLACK)))
  ;;              :anim-after-actions
  ;;              (fn [^Canvas canvas t]
  ;;                (. canvas save)
  ;;                (. canvas translate
  ;;                   ^double (- (* t (.getWidth canvas)) turtle-center-x)
  ;;                   ^double (- (* t (.getHeight canvas)) turtle-center-y))
  ;;                (. canvas rotate (* t 360) turtle-center-x turtle-center-y)
  ;;                (. canvas drawBitmap
  ;;                   ^Bitmap turtle-bitmap
  ;;                   0.0
  ;;                   0.0
  ;;                   nil)
  ;;                (. canvas restore))
  ;;              :duration 10000
  ;;              :pause-time delay})))
  ;;        (log "Clicked run button")))))
  )



(defn -onResume [^org.turtle.geometry.TurtleGraphics this]
  (. this superOnResume))

(defn -onPause [^org.turtle.geometry.TurtleGraphics this]
  (. this superOnPause))

;;;; graphics and other functions


(defn square [x] (* x x))

(defn divisible? "Return true if a is divisible by b without remainder."
  [a b]
  (= 0 (rem a b)))

(defn random-color []
  (Color/rgb (rand-int 256) (rand-int 256) (rand-int 256)))

;; $$ t \in \left[ 0, 1 \right] $$
(defn draw-line-interpolated [^Canvas canvas t startx starty endx endy paint]
  (let [dx (- endx startx)
        dy (- endy starty)]
    (.drawLine canvas
               startx
               starty
               (+ startx (* dx t))
               (+ starty (* dy t))
               paint)))

(defmacro with-centered-canvas [canvas-var & body]
  `(try
     (.save ^Canvas ~canvas-var Canvas/MATRIX_SAVE_FLAG)
     (.translate ~canvas-var
                 (int (/ (.getWidth ~canvas-var) 2))
                 (int (/ (.getHeight ~canvas-var) 2)))
     ~@body
     (finally
       (.restore ^Canvas ~canvas-var))))

(defn draw-bitmap-with-transform [^Canvas canvas
                                  ^Bitmap bitmap
                                  transform]
  (let [bitmap-center-x ^double (/ (.getWidth bitmap) 2.0)
        bitmap-center-y ^double (/ (.getHeight bitmap) 2.0)]
    (with-centered-canvas canvas
      (.translate canvas
                  (- bitmap-center-x)
                  (- bitmap-center-y))
      (transform canvas [bitmap-center-x bitmap-center-y])
      (.drawBitmap canvas
                   bitmap
                   0.0
                   0.0
                   nil))))

(defn ^Bitmap rotate-right-90 [^Bitmap b]
  (let [height (.getHeight b)
        width (.getWidth b)
        ;; new-b ^Bitmap (Bitmap/createBitmap height
        ;;                                    width
        ;;                                    android.graphics.Bitmap$Config/ARGB_8888)
        ;; canvas ^Canvas (Canvas. new-b)
        m (Matrix.)]
    (.preTranslate m (+ (/ width 2.0)) (+ (/ height 2.0)))
    (.setRotate m 90)
    (.postTranslate m (- (/ width 2.0)) (- (/ height 2.0)))
    (Bitmap/createBitmap b 0 0 width height m false)

    ;; (.translate canvas (- (/ width 2.0)) (- (/ height 2.0)))
    ;; (.rotate canvas 90 (/ width 2.0) (/ height 2.0))
    ;; ;; (.drawColor canvas Color/RED)
    ;; (.drawBitmap canvas b 0.0 0.0 nil)
    ;; ;; b
    ;; new-b
    ))

;;;; eval turtle program

(defrecord TurtleState [position ;; [x y]
                        angle    ;; in degrees
                        pen-up
                        color])

(def turtle-draw-area (atom nil))
;; seq of [startx starty endx endy] line segments produced by turtle
(def turtle-lines (atom nil))
(def turtle-state (atom nil :meta {:tag IndependentDrawer}))

(defn ^double deg->rad [^double x]
  (* x (/ Math/PI 180.0)))

(defn eval-turtle-program [program-text
                           delay
                           ^org.turtle.geometry.TurtleGraphics activity]
  (let [turtle-bitmap ^Bitmap (rotate-right-90
                               (Bitmap/createScaledBitmap
                                (BitmapFactory/decodeResource
                                 (.getResources activity)
                                 (resource :drawable :turtle_marker))
                                27
                                50
                                true))]
    (reset! turtle-lines nil)
    (reset! turtle-state (TurtleState. [0 0] 0 false Color/BLACK))
    (reset! turtle-draw-area @(.drawing-area ^ActivityState
                                             (.state activity)))

    (send-drawing-command @turtle-draw-area
                          {:type :plain
                           :actions
                           (fn [^Canvas canvas]
                             (.drawColor canvas Color/WHITE))
                           :after-actions
                           (fn [^Canvas canvas]
                             (let [[x y] (.position ^TurtleState @turtle-state)]
                               (draw-bitmap-with-transform
                                canvas
                                turtle-bitmap
                                (fn [^Canvas c [turtle-center-x turtle-center-y]]
                                  (.translate canvas
                                              (+ x turtle-center-x)
                                              (+ y turtle-center-y))
                                  (.rotate canvas
                                           (.angle ^TurtleState @turtle-state)
                                           0
                                           0)))))})
    (.start
     (new Thread
          (fn []
            (binding [*ns* (create-ns 'org.turtle.geometry.TurtleGraphics)]
              ;; (in-ns 'org.turtle.geometry.TurtleGraphics)
              ;; (require '[org.turtle.geometry.TurtleGraphics :as TurtleGraphics])
              ;; (use '[org.turtle.geometry.TurtleGraphics :only [turtle-state
              ;;                                                  turtle-lines]])
              (defn forward [dist]
                (let [theta (.angle ^TurtleState @turtle-state)
                      pos   (.position ^TurtleState @turtle-state)
                      delta [(* dist (Math/cos (deg->rad theta)))
                             (* dist (Math/sin (deg->rad theta)))]
                      new-pos (map + delta pos)
                      [old-x old-y] pos
                      [new-x new-y] new-pos]
                  (swap! turtle-state
                         assoc :position new-pos)
                  (swap! turtle-lines conj (vec (concat pos new-pos)))
                  (send-drawing-command
                   @turtle-draw-area
                   {:type :animation
                    :anim-actions
                    (fn [^Canvas canvas t]
                      (with-centered-canvas canvas
                        (draw-line-interpolated canvas
                                                t
                                                old-x
                                                old-y
                                                new-x
                                                new-y
                                                (color->paint Color/BLACK))))
                    :anim-after-actions
                    (fn [^Canvas canvas t]
                      (draw-bitmap-with-transform
                       canvas
                       turtle-bitmap
                       (fn [^Canvas c [turtle-center-x turtle-center-y]]
                         (.translate c
                                     (+ old-x (* t (- new-x old-x)))
                                     (+ old-y (* t (- new-y old-y))))
                         (.rotate c
                                  theta
                                  turtle-center-x
                                  turtle-center-y))))
                    :duration 500
                    :pause-time delay})))
              (defn left [delta]
                (let [theta (.angle ^TurtleState @turtle-state)
                      [x y] (.position ^TurtleState @turtle-state)]
                  (swap! turtle-state
                         assoc :angle (+ delta theta))
                  (send-drawing-command
                   @turtle-draw-area
                   {:type :animation
                    :anim-actions
                    (fn [canvas t])
                    :anim-after-actions
                    (fn [^Canvas canvas t]
                      (draw-bitmap-with-transform
                       canvas
                       turtle-bitmap
                       (fn [^Canvas c [turtle-center-x turtle-center-y]]
                         (.translate c
                                     x
                                     y)
                         (.rotate c
                                  (+ (* t delta) theta)
                                  turtle-center-x
                                  turtle-center-y))))
                    :duration 250
                    :pause-time delay})))
              (defn pen-up? []
                (.pen-up ^TurtleState @turtle-state))
              (try
                (eval
                 (read-string program-text))
                (catch Exception e
                  (.runOnUiThread
                   activity
                   (fn []
                     (report-error activity
                                   (str "We've got an error here:\n" e))))))))))))


