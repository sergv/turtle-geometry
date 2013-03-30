
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
           [android.graphics Bitmap BitmapFactory Canvas Color Paint Rect]
           [android.os AsyncTask]
           [android.view Menu MenuInflater MenuItem]
           [android.view MotionEvent SurfaceHolder SurfaceView View Window]
           [android.widget Button EditText ProgressBar TextView]
           [android.util AttributeSet]
           [android.util.Log]

           [java.util.concurrent
            ConcurrentLinkedQueue
            LinkedBlockingQueue
            ThreadPoolExecutor
            TimeUnit]

           [android.clojure IndependentDrawer])
  (:require [neko.init.options])
  (:use [clojure.tools.nrepl.server :only (start-server stop-server)]
        [neko.init]
        [android.clojure.util :only (make-ui-dimmer make-double-tap-handler)]
        [android.clojure.IndependentDrawer :only (send-drawing-command)]
        ;; [org.turtle.geometry.Eval]
        ))

(defn- log
  ([msg] (android.util.Log/d "TurtleGeometry" msg))
  ([msg & args] (log (apply format msg args))))

(defmacro resource
  ([id] `(resource :id ~id))
  ([resource-type resource-name]
     `(. ~(cond (= :id resource-type)
                org.turtle.geometry.R$id
                (= :layout resource-type)
                org.turtle.geometry.R$layout
                (= :menu resource-type)
                org.turtle.geometry.R$menu
                (= :drawable resource-type)
                org.turtle.geometry.R$drawable
                :else
                (throw java.lang.RuntimeException
                       (str "invalid resource type " resource-type)))
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


(defn make-async-task [^Activity activity
                       on-start
                       update
                       on-end]
  (let [run-on-ui-thread (fn [action]
                           (. activity runOnUiThread action))]
    (fn []
      (run-on-ui-thread on-start)
      (doseq [i (take 10 (range))]
        (Thread/sleep 25 0)
        (run-on-ui-thread update))
      (run-on-ui-thread on-end))))



(defn text-input->int [^EditText input-field]
  (Integer/valueOf ^String
                   (.. input-field
                       (getText)
                       (toString))))

(declare make-diminishing-grids-drawer draw-line-interpolated color->paint)
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
  (make-ui-dimmer this (.findViewById this (resource :main_layout)))

  (. ^EditText @(.program-source-editor ^ActivityState (.state this))
     setOnTouchListener
     (make-double-tap-handler (fn [] (log "Double tapped source editor twice"))))
  (. ^TextView @(.error-output ^ActivityState (.state this))
     setOnTouchListener
     (make-double-tap-handler (fn [] (log "Double tapped error output twice"))))

  ;; (let [activity ^org.turtle.geometry.TurtleGraphics this
  ;;       button ^Button (.findViewById this (resource :button_run))]
  ;;   (. button
  ;;      setOnClickListener
  ;;      (reify android.view.View$OnClickListener
  ;;        (onClick [this button]
  ;;          (let [delay (text-input->int
  ;;                       @(.delay-entry ^ActivityState (.state activity)))]
  ;;            (send-drawing-command @(.drawing-area ^ActivityState
  ;;                                                  (.state activity))
  ;;                                  {:type :plain
  ;;                                   :actions
  ;;                                   [(fn [^Canvas canvas]
  ;;                                      (.drawColor  canvas Color/WHITE))]})
  ;;            (eval-turtle-program (.getText @(.program-source-editor ^ActivityState (.state activity)))
  ;;                                 delay
  ;;                                 activity))
  ;;          (log "Clicked run button")))))

  (let [activity ^org.turtle.geometry.TurtleGraphics this
        button ^Button (.findViewById this (resource :button_run))
        turtle-bitmap ^Bitmap (Bitmap/createScaledBitmap
                               (BitmapFactory/decodeResource
                                (. activity getResources)
                                (resource :drawable :turtle_marker))
                               27
                               50
                               true)]
    (. button
       setOnClickListener
       (proxy [android.view.View$OnClickListener] []
         (onClick [^View button]
           (let [delay (text-input->int
                        @(.delay-entry ^ActivityState (.state activity)))]
             (send-drawing-command @(.drawing-area ^ActivityState
                                                   (.state activity))
                                   {:type :plain
                                    :actions
                                    (fn [^Canvas canvas]
                                      (. canvas drawColor Color/WHITE)
                                      (Thread/sleep delay 0))})

             (let [turtle-center-x ^double (/ (.getWidth turtle-bitmap) 2)
                   turtle-center-y ^double (/ (.getHeight turtle-bitmap) 2)]
               (send-drawing-command
                @(.drawing-area ^ActivityState
                                (.state activity))
                {:type :animation
                 :anim-actions
                 (fn [^Canvas canvas t]
                   (draw-line-interpolated canvas
                                           t
                                           0
                                           0
                                           (.getWidth canvas)
                                           (.getHeight canvas)
                                           (color->paint Color/BLACK)))
                 :anim-after-actions
                 (fn [^Canvas canvas t]
                   (. canvas save)
                   (. canvas translate
                      ^double (- (* t (.getWidth canvas)) turtle-center-x)
                      ^double (- (* t (.getHeight canvas)) turtle-center-y))
                   (. canvas rotate (* t 360) turtle-center-x turtle-center-y)
                   (. canvas drawBitmap
                      ^Bitmap turtle-bitmap
                      0.0
                      0.0
                      nil)
                   (. canvas restore))
                 :duration 10000
                 :pause-time delay})))
           (log "Clicked run button"))))))



(defn -onResume [^org.turtle.geometry.TurtleGraphics this]
  (. this superOnResume))

(defn -onPause [^org.turtle.geometry.TurtleGraphics this]
  (. this superOnPause))

;;;; graphics and other functions

(defn color->paint
  ([argb]
     (let [p (new Paint)]
       (. p setColor argb)
       p))
  ([alpha red green blue]
     (color->paint (Color/argb alpha red green blue))))

(defn draw-grid [^Canvas canvas
                 n
                 ^Paint paint]
  (let [width (. canvas getWidth)
        height (. canvas getHeight)
        dx (/ width n)
        dy (/ height n)]
    (loop [i 0]
      (. canvas drawLine (* i dx) 0 (* i dx) height paint)
      (. canvas drawLine 0 (* i dy) width (* i dy) paint)
      (when (not= i n)
        (recur (+ i 1))))))


(defn square [x] (* x x))

(defn divisible? "Return true if a is divisible by b without remainder."
  [a b]
  (= 0 (rem a b)))

(declare primes)

(defn prime? [x]
  (let [check-divisors
        (fn [prime-divisors]
          (if (<= (square (first prime-divisors)) x)
            (or (divisible? x (first prime-divisors))
                (recur (next prime-divisors)))
            false))]
    (check-divisors primes)))

(def primes
  (cons 2 (cons 3 (cons 5 (cons 7 (filter prime? (iterate #(+ 2 %) 9)))))))

(defn random-color []
  (Color/rgb (rand-int 256) (rand-int 256) (rand-int 256)))

(defn make-diminishing-grids-drawer []
  (let [ps (atom primes)]
    (fn [^Canvas canvas]
      (draw-grid canvas (first @ps) (color->paint Color/BLACK))
      (reset! ps (rest @ps)))))

;; $$ t \in \left[ 0, 1 \right] $$
(defn draw-line-interpolated [^Canvas canvas t startx starty endx endy paint]
  (let [dx (- endx startx)
        dy (- endy starty)]
    (. canvas drawLine
       startx
       starty
       (+ startx (* dx t))
       (+ starty (* dy t))
       paint)))

;;;; eval turtle program

(defrecord TurtleState [position
                        angle ;; in degrees
                        pen-up
                        color])

(def ^{:dynamic true} *turtle-activity* nil)
(def ^{:dynamic true} *turtle-state* (atom nil))


(defn eval-turtle-program [program-text
                           delay
                           ^org.turtle.geometry.TurtleGraphics activity]
  ;; (reset!  )
  (let [turtle-bitmap ^Bitmap (Bitmap/createScaledBitmap
                               (BitmapFactory/decodeResource
                                (. activity getResources)
                                (resource :drawable :turtle_marker))
                               27
                               50
                               true)]
    (.start
     (new Thread
          (let [*turtle-activity* activity
                *turtle-state* (atom (TurtleState. [0 0] 0 false Color/BLACK))]
            (fn []
              (in-ns 'org.turtle.geometry.TurtleEval)
              (require '[org.turtle.geometry.TurtleGraphics :as TurtleGraphics])
              (use '[org.turtle.geometry.TurtleGraphics :only [*turtle-state*]])
              (defn forward [x]
                )
              (defn pen-up? []
                (.pen-up @*turtle-state*))
              (try
                (eval
                 (read program-text))
                (catch Exception e
                  (.setText @(.program-source-editor ^ActivityState (.state activity)))))))))))

;; Local Variables:
;; compile-command: "LEIN_JAVA_CMD=/usr/lib/jvm/java-7-openjdk-amd64/jre/bin/java lein do droid code-gen, droid compile, droid create-dex, droid apk, droid install, droid run"
;; End:
