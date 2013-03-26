
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
            TimeUnit])
  (:use [clojure.tools.nrepl.server :only (start-server stop-server)]
        [neko.init]
        [android.clojure.util :only (make-ui-dimmer)]))

(defn log
  ([msg]
     (android.util.Log/d "TurtleGeometry" msg))
  ([msg & args]
     (log (apply format msg args))))

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

;;;; threaded surface view

(defrecord ViewState [surface-available?
                      ^Bitmap drawing-bitmap
                      ^Canvas drawing-canvas
                      ^Thread drawing-thread
                      ^ConcurrentLinkedQueue message-queue])

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
      (ViewState. (atom nil)
                  (atom nil :meta {:tag Bitmap})
                  (atom nil :meta {:tag Canvas})
                  (atom nil :meta {:tag Thread})
                  (new ConcurrentLinkedQueue))])
  ([^Context context ^AttributeSet attrs]
     [[context attrs]
      (ViewState. (atom nil)
                  (atom nil :meta {:tag Bitmap})
                  (atom nil :meta {:tag Canvas})
                  (atom nil :meta {:tag Thread})
                  (new ConcurrentLinkedQueue))])
  ([^Context context ^AttributeSet attrs defStyle]
     [[context attrs defStyle]
      (ViewState. (atom nil)
                  (atom nil :meta {:tag Bitmap})
                  (atom nil :meta {:tag Canvas})
                  (atom nil :meta {:tag Thread})
                  (new ConcurrentLinkedQueue))]))

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
                       height]
  (reset! (.drawing-bitmap ^ViewState (.view_state this))
          (if-let [orig-bmap ^Bitmap @(.drawing-bitmap ^ViewState (.view_state this))]
            (Bitmap/createScaledBitmap orig-bmap width height true)
            (Bitmap/createBitmap width height android.graphics.Bitmap$Config/ARGB_8888)))
  (reset! (.drawing-canvas ^ViewState (.view_state this))
          (new Canvas @(.drawing-bitmap ^ViewState (.view_state this)))))

(declare make-drawing-thread)

(defn -surfaceCreated [^org.turtle.geometry.TurtleView this
                       ^SurfaceHolder holder]
  (reset! (.surface-available? ^ViewState (.view_state this)) true)
  (let [thread ^Thread
        (make-drawing-thread this)]
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

(defmacro with-canvas
  "action will be executed with canvas-var bound to drawing canvas whole
contents is stored in drawing bitmap and will be preserved between
different actions.

after-action will be executed with canvas-var bounded to the
resulting canvas with drawing-bitmap drawn on. Content of this canvas will
not be preserved"
  ([canvas-var surface-view action after-action]
     `(with-canvas ~canvas-var ~surface-view ~action ~after-action nil))
  ([canvas-var surface-view action after-action post-action]
     `(if-let [surface-canvas# ^Canvas (.. ~surface-view (getHolder) (lockCanvas))]
        (try
          (let [~canvas-var ^Canvas @(.drawing-canvas ^ViewState (.view_state ~surface-view))]
            ~action)
          (. surface-canvas#
             drawBitmap
             ^Bitmap @(.drawing-bitmap ^ViewState (.view_state ~surface-view))
             0.0
             0.0
             nil)
          (let [~canvas-var ^Canvas surface-canvas#]
            ~after-action)
          (finally
            ~post-action
            (.. ~surface-view (getHolder) (unlockCanvasAndPost surface-canvas#))))
        (log "Unexpected error: lockCanvas returned nil"))))

(defn ^Thread make-drawing-thread [^org.turtle.geometry.TurtleView this]
  (let [msg-queue ^ConcurrentLinkedQueue (.message-queue ^ViewState (.view_state this))]
    (new Thread
         (fn []
           (log "Drawing thread's born")
           (loop []
             (when @(.surface-available? ^ViewState (.view_state this))
               (if-let [msg (.peek msg-queue)]
                 (case (msg :type)
                   :plain
                   (let [{:keys [actions after-actions]} msg]
                     (with-canvas canvas this
                       (dorun
                        (map #(% canvas) actions))
                       (dorun
                        (map #(% canvas) after-actions))
                       (.poll msg-queue)))
                   :animation
                   ;; if type is :animation then actions is a seq of functions of
                   ;; two argumetns: canvas and $$ t \in \left[ 0, 1 \right] $$
                   (let [{:keys [actions after-actions duration pause-time]} msg
                         start-time (. java.lang.System currentTimeMillis)]
                     (with-canvas canvas this
                       (dorun
                        (map #(% canvas 0) actions))
                       (dorun
                        (map #(% canvas 0) after-actions)))
                     (Thread/sleep pause-time 0)
                     (loop []
                       (let [curr-time (. java.lang.System currentTimeMillis)
                             diff (Math/abs (- curr-time start-time))]
                         (when (< diff duration)
                           (with-canvas canvas this
                             (dorun
                              (map #(% canvas (/ diff duration)) actions))
                             (dorun
                              (map #(% canvas (/ diff duration)) after-actions)))
                           (Thread/sleep pause-time 0)
                           (recur))))
                     (with-canvas canvas this
                       (dorun
                        (map #(% canvas 1) actions))
                       (dorun
                        (map #(% canvas 1) after-actions)))
                     (Thread/sleep pause-time 0)
                     ;; completed action or not - remove it anyway
                     (.poll msg-queue)))
                 (java.lang.Thread/sleep 100 0))
               (recur)))
           (log "Drawing thread dies")))))

;;;; activity functions

(def ^{:dynamic true} *activity* (atom nil))
(def ^{:dynamic true} *task-runner* (atom nil))

(defn show-progress-bar [^org.turtle.geometry.TurtleGraphics activity]
  (. activity setProgressBarIndeterminateVisibility true))

(defn hide-progress-bar [^org.turtle.geometry.TurtleGraphics activity]
  (. activity setProgressBarIndeterminateVisibility false))

(defrecord ActivityState [ ;;^org.turtle.geometry.TurtleView
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

(declare make-diminishing-grids-drawer draw-line-interpolated color->paint)

(defn send-drawing-command [^org.turtle.geometry.TurtleGraphics activity
                            msg]
  (if (and (contains? msg :type)
           (contains? #{:plain :animation} (msg :type)))
    (.add ^ConcurrentLinkedQueue
          (.message-queue ^ViewState
                          (.view_state ^org.turtle.geometry.TurtleView
                                       @(.drawing-area ^ActivityState
                                                       (.state activity))))
          msg)
    (log "Error: attempt to send invalid message: %s" msg)))

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
     (make-double-tap-handler (fn [] (log "Tapped source editor twice"))))
  (. ^TextView @(.error-output ^ActivityState (.state this))
     setOnTouchListener
     (make-double-tap-handler (fn [] (log "Tapped error output twice"))))

  (let [activity ^org.turtle.geometry.TurtleGraphics this
        button ^Button (. this findViewById (resource :button_run))
        turtle-bitmap ^Bitmap (BitmapFactory/decodeResource
                               (. activity getResources)
                               (resource :drawable :turtle_marker))]
    (. button
       setOnClickListener
       (proxy [android.view.View$OnClickListener] []
         (onClick [^View button]
           (let [delay (text-input->int
                        @(.delay-entry ^ActivityState (.state activity)))]
             (send-drawing-command activity
                                   {:type :plain
                                    :actions
                                    [(fn [^Canvas canvas]
                                       (. canvas drawColor Color/WHITE)
                                       (Thread/sleep delay 0))]})
             (let [turtle-center-x ^double (/ (.getWidth turtle-bitmap) 2)
                   turtle-center-y ^double (/ (.getHeight turtle-bitmap) 2)]
               (send-drawing-command
                activity
                {:type :animation
                 :actions
                 [(fn [^Canvas canvas t]
                    (draw-line-interpolated canvas
                                            t
                                            0
                                            0
                                            (.getWidth canvas)
                                            (.getHeight canvas)
                                            (color->paint Color/BLACK)))]
                 :after-actions
                 [(fn [^Canvas canvas t]
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
                    (. canvas restore))]
                 :duration 10000
                 :pause-time delay})))
           (log "Clicked run button")))))

  ;; (. @*task-runner* execute
  ;;    (make-async-task activity
  ;;                     (fn []
  ;;                       (show-progress-bar activity)
  ;;                       (. button setEnabled false))
  ;;                     (fn [])
  ;;                     (fn []
  ;;                       (hide-progress-bar activity)
  ;;                       (. button setEnabled true))))
  )



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


;; Local Variables:
;; compile-command: "LEIN_JAVA_CMD=/usr/lib/jvm/java-7-openjdk-amd64/jre/bin/java lein do droid code-gen, droid compile, droid create-dex, droid apk, droid install, droid run"
;; End:
