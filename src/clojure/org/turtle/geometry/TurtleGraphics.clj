
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
           [android.graphics Canvas Color Paint]
           [android.os AsyncTask]
           [android.view Menu MenuInflater MenuItem]
           [android.view MotionEvent SurfaceHolder SurfaceView View Window]
           [android.widget Button EditText ProgressBar TextView]
           [android.util AttributeSet]
           [android.util.Log]

           [java.util.concurrent LinkedBlockingQueue ThreadPoolExecutor TimeUnit])
  (:use [clojure.tools.nrepl.server :only (start-server stop-server)]
        [neko.init]
        [android.clojure.util :only (make-ui-dimmer)]))

(defn log [msg]
  (android.util.Log/d "TurtleGeometry" msg))

(defmacro resource
  ([id] `(resource :id ~id))
  ([resource-type resource-name]
     `(. ~(cond (= :id resource-type)
                org.turtle.geometry.R$id
                (= :layout resource-type)
                org.turtle.geometry.R$layout
                (= :menu resource-type)
                org.turtle.geometry.R$menu
                :else
                (throw java.lang.RuntimeException
                       (str "invalid resource type " resource-type)))
         ~(symbol (name resource-name)))))

;;;; threaded surface view

(defrecord ViewState [drawing-func
                      surface-available?
                      drawing-thread])

(gen-class :name org.turtle.geometry.TurtleView
           :main false
           :extends android.view.SurfaceView
           :implements [android.view.SurfaceHolder$Callback]
           :state view_state
           :init view-init
           :post-init view-post-init)

(defn -view-init
  ([^Context context]
     [[context]
      (ViewState. (atom nil) (atom nil) (atom nil :meta {:tag Thread}))])
  ([^Context context ^AttributeSet attrs]
     [[context attrs]
      (ViewState. (atom nil) (atom nil) (atom nil :meta {:tag Thread}))])
  ([^Context context ^AttributeSet attrs defStyle]
     [[context attrs defStyle]
      (ViewState. (atom nil) (atom nil) (atom nil :meta {:tag Thread}))]))

(defn -view-post-init
  ([^org.turtle.geometry.TurtleView this
    ^Context context]
     (.. this (getHolder) (addCallback this)))
  ([^org.turtle.geometry.TurtleView this
    ^Context context ^AttributeSet attrs]
     (-view-post-init this context))
  ([^org.turtle.geometry.TurtleView this
    ^Context context ^AttributeSet attrs defStyle]
     (-view-post-init this context)))


(defn -surfaceChanged [^org.turtle.geometry.TurtleView this
                       ^SurfaceHolder holder
                       format
                       width
                       height])

(defn -surfaceCreated [^org.turtle.geometry.TurtleView this
                       ^SurfaceHolder holder]
  (reset! (.surface-available? ^ViewState (.view_state this)) true)
  (let [thread ^Thread
        (new Thread
             (fn []
               (log "Drawing thread borns")
               (loop []
                 (when @(.surface-available? ^ViewState (.view_state this))
                   (if-let [f @(.drawing-func ^ViewState (.view_state this))]
                     (when-let [^Canvas canvas
                                (.. this (getHolder) (lockCanvas nil))]
                       (try
                         (f canvas)
                         (finally
                           (.. this (getHolder) (unlockCanvasAndPost canvas)))))
                     (java.lang.Thread/sleep 100 0))
                   (recur)))
               (log "Drawing thread dies")))]
    (reset! (.drawing-thread ^ViewState (.view_state this))
            thread)
    (.start thread)))

(defn -surfaceDestroyed [^org.turtle.geometry.TurtleView this
                         ^SurfaceHolder holder]
  (reset! (.surface-available? ^ViewState (.view_state this)) false)
  (loop []
    (when (not (try
                 (.join ^Thread @(.drawing-thread ^ViewState (.view_state this)))
                 true
                 (catch InterruptedException e
                   false)))
      (recur))))

;;;; activity functions

(def ^{:dynamic true} *activity* (atom nil))
(def ^{:dynamic true} *task-runner* (atom nil))

(defn show-progress-bar [^org.turtle.geometry.TurtleGraphics activity]
  (. activity setProgressBarIndeterminateVisibility true))

(defn hide-progress-bar [^org.turtle.geometry.TurtleGraphics activity]
  (. activity setProgressBarIndeterminateVisibility false))

(defrecord ActivityState [;;^org.turtle.geometry.TurtleView
                          drawing-area
                          ^EditText program-source-editor
                          ^TextView error-output
                          ^EditText delay-entry
                          ^Menu menu])

(defn -init []
  [[] (ActivityState. (atom nil)
                      (atom nil :meta {:tag EditText})
                      (atom nil :meta {:tag TextView})
                      (atom nil :meta {:tag EditText})
                      (atom nil :meta {:tag Menu}))])


(defn make-test-task [^Activity activity
                      on-start
                      update
                      on-end]
  (let [run-on-ui-thread (fn [action]
                           (. activity runOnUiThread action))]
    (fn []
      (run-on-ui-thread on-start)
      (doseq [i (take 100 (range))]
        (Thread/sleep 100 0)
        (run-on-ui-thread update))
      (run-on-ui-thread on-end))))

(defn make-double-tap-handler
  ([f]
     (make-double-tap-handler f 500))
  ([f recognition-time]
     (let [last-time (atom nil)]
       (proxy [android.view.View$OnTouchListener] []
         (onTouch [^View view
                   ^MotionEvent event]
           (cond (= MotionEvent/ACTION_DOWN (. event getActionMasked))
                 (let [current-time (. java.lang.System currentTimeMillis)]
                   (if @last-time
                     (if (< (java.lang.Math/abs ^long (- @last-time
                                                         current-time))
                            recognition-time)
                       (do (f)
                           (reset! last-time nil)
                           true)
                       (do (reset! last-time current-time)
                           false))
                     (do (reset! last-time current-time)
                         false)))
                 :else
                 false))))))

(defn text-input->int [^EditText input-field]
  (Integer/valueOf ^String
                   (.. input-field
                       (getText)
                       (toString))))

(declare make-diminishing-grids-drawer)

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
  ;; (defonce *nrepl-server* (start-server :port 10001))
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
     (make-double-tap-handler (fn [] (log "Touched source editor"))))
  (. ^TextView @(.error-output ^ActivityState (.state this))
     setOnTouchListener
     (make-double-tap-handler (fn [] (log "Touched error output"))))


  (let [^org.turtle.geometry.TurtleGraphics activity this
        ^Button button (. this findViewById (resource :button_run))]
    (. button
       setOnClickListener
       (proxy [android.view.View$OnClickListener] []
         (onClick [^View button]
           ;; (. @(.drawing-area ^ActivityState (.state activity)) invalidate)
           (let [drawer (make-diminishing-grids-drawer)]
             (reset! (.drawing-func (.view_state
                                     ^org.turtle.geometry.TurtleView
                                     @(.drawing-area ^ActivityState
                                                     (.state activity))))
                     (fn [^Canvas canvas]
                       (let [delay (text-input->int
                                    @(.delay-entry ^ActivityState (.state activity)))]
                         (. canvas drawColor Color/WHITE)
                         (drawer canvas)
                         (Thread/sleep delay 0)))))
           (. @*task-runner* execute
              (make-test-task activity
                              (fn []
                                (show-progress-bar activity)
                                (. button setEnabled false))
                              (fn [])
                              (fn []
                                (hide-progress-bar activity)
                                (. button setEnabled true))))
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



;; Local Variables:
;; compile-command: "lein do droid code-gen, droid compile, droid create-dex, droid apk, droid install, droid run"
;; End:
