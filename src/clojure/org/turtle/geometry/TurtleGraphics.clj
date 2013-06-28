
;;;; prelude

(ns org.turtle.geometry.TurtleGraphics
  (:gen-class :main no
              :extends android.app.Activity
              :implements [clojure.lang.IDeref]
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


           ;; [org.antlr.runtime ANTLRStringStream
           ;;  CommonTokenStream]
           ;; [org.turtle TurtleLexer TurtleParser]

           [android.clojure IndependentDrawer])
  (:require [android.clojure.util]
            [neko.init.options])
  (:use [clojure.pprint :only (with-pprint-dispatch
                                pprint
                                *print-right-margin*
                                code-dispatch)]
        [clojure.math.numeric-tower :only (sqrt)]
        [neko.init]
        [android.clojure.util :only (defrecord* make-double-tap-handler)]
        [android.clojure.IndependentDrawer :only (clear-drawing-queue!
                                                  send-drawing-command)]
        [android.clojure.graphic :only (color->paint draw-grid)]))

(defn log
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
;; (def ^{:dynamic true} *task-runner* (atom nil))

(defn show-progress-bar [^org.turtle.geometry.TurtleGraphics activity]
  (.setProgressBarIndeterminateVisibility activity true))

(defn hide-progress-bar [^org.turtle.geometry.TurtleGraphics activity]
  (.setProgressBarIndeterminateVisibility activity false))

(defrecord* ActivityState [^android.clojure.IndependentDrawer drawing-area
                           ^EditText program-source-editor
                           ^TextView error-output
                           ^EditText duration-entry
                           ^Menu menu
                           ^Thread turtle-drawing-thread])

(defn -deref [^org.turtle.geometry.TurtleGraphics this]
  @(.state this))


(defn -init []
  [[] (atom (ActivityState. nil
                            nil
                            nil
                            nil
                            nil
                            nil))])



(defn text-input->int [^EditText input-field]
  (Integer/valueOf ^String
                   (.. input-field
                       (getText)
                       (toString))))

(defn report-error [^org.turtle.geometry.TurtleGraphics activity
                    ^String msg]
  (.setText (error-output @activity) msg))

(declare make-diminishing-grids-drawer draw-line-interpolated)
(declare eval-turtle-program rotate-right-90)

(defn -onCreate [^org.turtle.geometry.TurtleGraphics this
                 ^android.os.Bundle bundle]
  (reset! *activity* this)
  ;; (reset! *task-runner* (new ThreadPoolExecutor
  ;;                            2    ;; core pool size
  ;;                            5    ;; max threads
  ;;                            1000 ;; keep alive time
  ;;                            TimeUnit/MILLISECONDS
  ;;                            (new LinkedBlockingQueue)))
  (neko.init/init this :port 10001)
  (doto this
    (. superOnCreate bundle)
    (. requestWindowFeature Window/FEATURE_INDETERMINATE_PROGRESS)
    (. setContentView (resource :layout :main))
    (. setProgressBarIndeterminateVisibility false))
  (swap! (.state this)
         assoc
         :drawing-area (.findViewById this (resource :drawing_area))
         :program-source-editor (.findViewById this (resource :program_input))
         :error-output (.findViewById this (resource :error_output))
         :duration-entry (.findViewById this (resource :duration_entry)))
  (android.clojure.util/make-ui-dimmer (.findViewById this
                                                      (resource :main_layout)))

  (.setText (program-source-editor @this)
            "(do\n  (forward 100)\n  (left 90)\n  (forward 100))\n")
  (.setOnTouchListener (program-source-editor @this)

                       (make-double-tap-handler (fn [] (log "Double tapped source editor twice"))))
  (.setOnTouchListener (error-output @this)
                       (make-double-tap-handler (fn [] (log "Double tapped error output twice"))))

  (let [activity ^org.turtle.geometry.TurtleGraphics this
        button ^Button (.findViewById this (resource :button_run))

        turtle-bitmap (rotate-right-90
                       (Bitmap/createScaledBitmap
                        (BitmapFactory/decodeResource
                         (.getResources this)
                         (resource :drawable :turtle_marker))
                        27
                        50
                        ;; do filtering
                        true))]
    (.setOnClickListener
     button
     (reify android.view.View$OnClickListener
       (onClick [this button]
         (let [old-turtle-thread (turtle-drawing-thread @activity)]
           (when (or (not old-turtle-thread)
                     (not (.isAlive old-turtle-thread)))
             (let [turtle-thread (eval-turtle-program
                                  (str (.getText (program-source-editor @activity)))
                                  turtle-bitmap
                                  activity)]
               (report-error activity "no errors yet")
               (swap! (.state activity)
                      assoc
                      :turtle-drawing-thread
                      turtle-thread))))
         (log "Clicked run button"))))

    (.setOnClickListener
     ^Button
     (.findViewById this (resource :button_stop))
     (reify android.view.View$OnClickListener
       (onClick [this button]
         (let [turtle-thread (turtle-drawing-thread @activity)]
           (when turtle-thread
             (.interrupt turtle-thread)
             (swap! (.state activity) assoc :turtle-drawing-thread nil)
             (.join turtle-thread)
             (clear-drawing-queue! (drawing-area @activity)))))))

    ;; todo: move this "button renindent" into context menu for source editor
    (.setOnClickListener
     ^Button
     (.findViewById this (resource :button_reindent))
     (reify android.view.View$OnClickListener
       (onClick [this button]
         (let [editor (program-source-editor @activity)
               out-writer ^StringWriter (StringWriter.)
               orig-contents (.getText editor)]
           (try
             (binding [*print-right-margin* 80
                       *out* out-writer]
               (with-pprint-dispatch code-dispatch
                 (pprint (read-string (str orig-contents)))
                 (.setText editor (str *out*))))
             (catch Exception e
               (.setText editor orig-contents)
               (report-error activity (str "error while indenting:\n" e))))))))))



(defn -onResume [^org.turtle.geometry.TurtleGraphics this]
  (.superOnResume this))

(defn -onPause [^org.turtle.geometry.TurtleGraphics this]
  (.superOnPause this))

;; (defn -onSaveInstanceState [^org.turtle.geometry.TurtleGraphics this
;;                             ^android.os.Bundle bundle]
;;   )
;;
;; (defn -onRestoreInstanceState [^org.turtle.geometry.TurtleGraphics this
;;                                ^android.os.Bundle bundle]
;;   )

;;;; graphics and other functions

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

;; (defn parse-program [^String str]
;;   (let [lexer (TurtleLexer. (ANTLRStringStream. str))
;;         tokens (CommonTokenStream. lexer)
;;         parser (TurtleParser. tokens)]
;;     (.program parser)))


(defrecord TurtleState [position ;; [x y]
                        angle    ;; in degrees
                        pen-up
                        color

                        ;; seq of [startx starty endx endy] line segments produced by turtle
                        lines
                        turtle-bitmap
                        parent-activity])

(def turtle-state (atom nil))

(defn ^double deg->rad [^double x]
  (* x (/ Math/PI 180.0)))

(defn drawing-animation-duration [^org.turtle.geometry.TurtleGraphics  activity]
  (text-input->int (duration-entry @activity)))

(defn draw-turtle-bitmap-at
  ([^Canvas canvas x y]
     (draw-turtle-bitmap-at canvas x y (.angle ^TurtleState @turtle-state)))
  ([^Canvas canvas x y heading]
     (let [bitmap ^Bitmap (.turtle-bitmap ^TurtleState @turtle-state)
           bitmap-center-x ^double (/ (.getWidth bitmap) 2.0)
           bitmap-center-y ^double (/ (.getHeight bitmap) 2.0)]
       (with-centered-canvas canvas
         (.translate canvas (- x bitmap-center-x) (- y bitmap-center-y))
         (.rotate canvas
                  heading
                  bitmap-center-x
                  bitmap-center-y)
         (.drawBitmap canvas
                      bitmap
                      0.0
                      0.0
                      nil)))))


(defn move [dist]
  (let [tstate ^TurtleState @turtle-state
        theta (.angle tstate)
        pos   (.position tstate)
        delta-x (* dist (Math/cos (deg->rad theta)))
        delta-y (* dist (Math/sin (deg->rad theta)))
        delta [delta-x delta-y]
        new-pos (map + delta pos)
        [old-x old-y] pos
        [new-x new-y] new-pos
        draw-line? (not (.pen-up tstate))]
    (swap! turtle-state assoc :position new-pos)
    (when draw-line?
      (swap! turtle-state
             update-in
             [:lines]
             conj
             (vec (concat pos new-pos))))
    (send-drawing-command
     (drawing-area @(.parent-activity tstate))
     {:type :animation
      :anim-actions
      (when draw-line?
        (fn [^Canvas canvas t]
          (with-centered-canvas canvas
            (draw-line-interpolated canvas
                                    t
                                    old-x
                                    old-y
                                    new-x
                                    new-y
                                    (color->paint Color/BLACK)))))
      :anim-after-actions
      (fn [^Canvas canvas t]
        (draw-turtle-bitmap-at canvas
                               (+ old-x (* t delta-x))
                               (+ old-y (* t delta-y))
                               theta))
      :duration (drawing-animation-duration (.parent-activity tstate))
      :pause-time 0})))

(defn rotate [delta]
  (let [tstate ^TurtleState @turtle-state
        theta (.angle tstate)
        [x y] (.position tstate)]
    (swap! turtle-state assoc :angle (+ delta theta))
    (send-drawing-command
     (drawing-area @(.parent-activity tstate))
     {:type :animation
      :anim-actions
      (fn [canvas t])
      :anim-after-actions
      (fn [^Canvas canvas t]
        (draw-turtle-bitmap-at canvas x y (+ (* t delta) theta)))
      :duration (int (/ (drawing-animation-duration (.parent-activity tstate)) 2))
      :pause-time 0})))


(defn stop-if-interrupted []
  (when (.isInterrupted (Thread/currentThread))
    (throw (InterruptedException.))))

(defn forward [dist]
  (stop-if-interrupted)
  (move dist))

(defn backward [dist]
  (stop-if-interrupted)
  (move (- dist)))

(defn left [delta]
  (stop-if-interrupted)
  (rotate (- delta)))

(defn right [delta]
  (stop-if-interrupted)
  (rotate delta))

(defn pen-up? []
  (stop-if-interrupted)
  (.pen-up ^TurtleState @turtle-state))

(defn pen-up []
  (stop-if-interrupted)
  (swap! turtle-state
         assoc
         :pen-up
         true))

(defn pen-down []
  (stop-if-interrupted)
  (swap! turtle-state
         assoc
         :pen-up
         false))

(defn heading []
  (stop-if-interrupted)
  (.angle ^TurtleState @turtle-state))

(defn set-heading [new-heading]
  (stop-if-interrupted)
  (swap! turtle-state assoc :theta new-heading))



(defn eval-turtle-program [program-text
                           turtle-bitmap
                           ^org.turtle.geometry.TurtleGraphics activity]
  (let [new-state (TurtleState. [0 0] 0 false Color/BLACK nil turtle-bitmap activity)
        turtle-thread
        (Thread.
         (.getThreadGroup (Thread/currentThread))
         (fn []
           (send-drawing-command
            (drawing-area @(.parent-activity ^TurtleState @turtle-state))
            {:type :plain
             :actions
             (fn [^Canvas canvas]
               (.drawColor canvas Color/WHITE))
             :after-actions
             (fn [^Canvas canvas]
               (let [[x y] (.position ^TurtleState @turtle-state)]
                 (draw-turtle-bitmap-at canvas x y)))})

           (binding [*ns* (create-ns 'org.turtle.geometry.TurtleSandbox)]
             ;; (in-ns 'org.turtle.geometry.TurtleGraphics)
             ;; (require '[org.turtle.geometry.TurtleGraphics :as TurtleGraphics])
             (use '[clojure.core])
             (use '[clojure.math.numeric-tower :only (sqrt)])
             (use '[org.turtle.geometry.TurtleGraphics
                    :only (pen-up?
                           pen-up pen-down
                           forward backward
                           left right
                           heading set-heading

                           log)])

             (try
               (eval (read-string (str "(do\n" program-text ")")))
               (catch InterruptedException _
                 (.runOnUiThread
                  activity
                  (fn []
                    (report-error activity "Interrupted"))))
               (catch Exception e
                 (.runOnUiThread
                  activity
                  (fn []
                    (report-error activity (str "We've got an error here:\n" e))))))))
         "turtle eval thread"
         (* 8 1024 1024))]
    (reset! turtle-state new-state)
    (.start turtle-thread)
    turtle-thread))


