
;;;; prelude

(ns org.turtle.geometry.TurtleGraphics
  (:gen-class :main false
              :name org.turtle.geometry.TurtleGraphics
              :extends android.support.v4.app.FragmentActivity
              :implements [clojure.lang.IDeref
                           android.view.SurfaceHolder$Callback]
              :exposes-methods {onCreate superOnCreate
                                onCreateOptionsMenu superOnCreateOptionsMenu
                                onMenuItemSelected superOnMenuItemSelected
                                onResume superOnResume
                                onPause superOnPause
                                onStop superOnStop
                                onDestroy superOnDestroy
                                onBackPressed superOnBackPressed
                                onActivityResult superOnActivityResult
                                onSaveInstanceState superOnSaveInstanceState
                                onConfigurationChanged superOnConfigurationChanged}
              :state ^{:tag ActivityState} state
              :init init
              :constructors {[] []})
  (:import [android.support.v4.app DialogFragment]
           [android.app Activity AlertDialog AlertDialog$Builder Dialog]
           [android.content Context DialogInterface$OnClickListener
            Intent SharedPreferences]
           [android.graphics Bitmap Bitmap$Config BitmapFactory Canvas
            Color Matrix Paint Paint$Cap Paint$Style Rect]
           [android.net Uri]
           [android.os Bundle Environment]
           [android.text SpannableStringBuilder]
           [android.text.method ScrollingMovementMethod]
           [android.view
            GestureDetector GestureDetector$SimpleOnGestureListener
            ScaleGestureDetector ScaleGestureDetector$OnScaleGestureListener]
           [android.view Menu MenuInflater MenuItem]
           [android.view KeyEvent LayoutInflater MotionEvent
            SurfaceHolder SurfaceView
            View View$OnKeyListener View$OnTouchListener
            ViewGroup Window]
           [android.widget Button EditText ProgressBar
            TabHost TabHost$TabSpec TabHost$TabContentFactory TabWidget
            TextView Toast]
           [android.util AttributeSet TypedValue]
           [android.util.Log]

           [java.io BufferedWriter BufferedReader
            File FileReader FileWriter
            InputStreamReader
            OutputStream
            PrintWriter
            StringReader]
           [java.nio.charset Charset]
           [java.util.concurrent
            ConcurrentLinkedQueue
            LinkedBlockingQueue
            ThreadPoolExecutor
            TimeUnit]

           ;; [org.antlr.runtime ANTLRStringStream
           ;;  CommonTokenStream]
           ;; [org.turtle TurtleLexer TurtleParser]
           [jscheme JScheme SchemePair]
           [jsint E Evaluator$InterruptedException InputPort Pair Procedure Symbol])
  ;; (:require [neko.init]
  ;;  [neko.compilation]
  ;;  )
  (:use [clojure.math.numeric-tower :only (sqrt)]
        [org.turtle.geometry.utils]
        ;; [neko.init]
        [android.clojure.util :only (android-resource
                                     defrecord*
                                     extract-stacktrace
                                     make-double-tap-handler
                                     make-ui-dimmer)]))

;;;; tooked from android-util's graphic.clj

(defn ^Paint color->paint
  ([argb]
     (let [p (Paint.)]
       (.setColor p argb)
       p))
  ([alpha red green blue]
     (color->paint (Color/argb alpha red green blue))))

(defmacro with-saved-matrix [canvas-var & body]
  `(try
     (.save ^Canvas ~canvas-var Canvas/MATRIX_SAVE_FLAG)
     ~@body
     (finally
       (.restore ^Canvas ~canvas-var))))

;;;;

;; do nothing in release
;; (defn log-func
;;   ([msg]
;;      (android.util.Log/d "TurtleGeometry" msg))
;;   ([msg & args] (log-func (apply format msg args))))

(defmacro log
  ([msg]
     ;; do nothing in release
     ;; `(android.util.Log/d "TurtleGeometry" ~msg)
     )
  ([msg & args] `(log (format ~msg ~@args))))

(defmacro draw-with-page-transform [canvas-var transform-matrix & body]
  `(with-saved-matrix ~canvas-var
     (.concat ~canvas-var ~transform-matrix)
     ;; (.setMatrix ~canvas-var (matrix-mult (.getMatrix ~canvas-var)
     ;;                                      ~transform-matrix))
     ~@body))

(defmacro with-centered-canvas [canvas-var & body]
  `(with-saved-matrix ~canvas-var
     (.translate ~canvas-var
                 (int (/ (.getWidth ~canvas-var) 2))
                 (int (/ (.getHeight ~canvas-var) 2)))
     ~@body))

(defn make-identity-transform ^Matrix []
  (Matrix.))

(defn rotate-right-90 ^Bitmap [^Bitmap b]
  (let [height (.getHeight b)
        width (.getWidth b)
        half-width (/ width 2.0)
        half-height (/ height 2.0)
        m ^Matrix (make-identity-transform)]
    (.preTranslate m (+ half-width) (+ half-height))
    (.setRotate m 90)
    (.postTranslate m (- half-width) (- half-height))
    (Bitmap/createBitmap b 0 0 width height m false)))

(defn matrix-mult ^Matrix [^Matrix m1 ^Matrix m2]
  (let [tmp (Matrix. m1)]
    (.postConcat tmp m2)
    tmp))

(defn line-paint ^Paint [color]
  (let [p ^Paint (color->paint color)]
    (doto p
      ;; (.setStrokeWidth ,,, 2)
      (.setStyle ,,, Paint$Style/STROKE)
      (.setStrokeCap ,,, Paint$Cap/ROUND)
      (.setAntiAlias ,,, true))
    p))

(defn antialiasing-paint ^Paint []
  (let [p (Paint.)]
    (.setAntiAlias p true)
    p))

;;;; activity functions

(def ^{:dynamic true} *activity* (atom nil))
;; (def ^{:dynamic true} *task-runner* (atom nil))

;; (defn show-progress-bar [^org.turtle.geometry.TurtleGraphics activity]
;;   (.setProgressBarIndeterminateVisibility activity true))

;; (defn hide-progress-bar [^org.turtle.geometry.TurtleGraphics activity]
;;   (.setProgressBarIndeterminateVisibility activity false))

(defrecord DrawState [surface-available?
                      ^Bitmap intermediate-bitmap
                      ^Canvas draw-canvas])

(defrecord TurtleState [position      ;; [x y]
                        prev-position ;; [x y]
                        angle         ;; in degrees
                        pen-up?
                        color ;; current color

                        ;; seq of [[startx starty] [endx endy] color] line segments produced by turtle
                        lines])

(defrecord UserOptions [^boolean show-graphics-when-run
                        ^boolean track-scaling-touches])

(def ^{:const true} initial-turtle-state
  (TurtleState. [0 0] [0 0] 0 false Color/BLACK []))

(def ^{:const true} shared-preferences-tag "turtle-geometry-preferences")
(def ^{:const true} preferences-last-program-tag "last-program")

(defrecord* ActivityState [^SurfaceView draw-area
                           ^EditText program-source-editor
                           ^TextView error-output
                           ^EditText duration-entry
                           ^Menu activity-menu
                           ^TabHost activity-tab-host
                           ^PrintWriter error-writer

                           ^DrawState draw-state
                           ^UserOptions user-options
                           ^SharedPreferences global-preferences
                           ^JScheme jscheme

                           ^Matrix intermediate-transform
                           ^Thread turtle-program-thread
                           ^Matrix draw-area-view-transform
                           ^Bitmap turtle-bitmap
                           ^TurtleState turtle-state])

(defn -deref ^ActivityState [^org.turtle.geometry.TurtleGraphics this]
  @(.state this))

(defn -init []
  [[] (atom (ActivityState. nil
                            nil
                            nil
                            nil
                            nil
                            nil
                            nil

                            nil
                            nil
                            nil
                            nil

                            nil
                            nil
                            nil
                            nil
                            nil))])



(def ^{:const true} intent-save-file 0)
(def ^{:const true} intent-load-file 1)

(defn text-input->int [^EditText input-field default]
  (let [s ^String (.. input-field
                      (getText)
                      (toString))]
    (if (not (re-matches #"[0-9]+" s))
      default
      (Integer/valueOf s))))

(defn report-error
  ([activity msg]
     (report-error activity msg true))
  ([^org.turtle.geometry.TurtleGraphics activity
    ^String msg
    switch-to-errors-tab]
     (.println (error-writer @activity) msg)
     (when switch-to-errors-tab
       (.setCurrentTabByTag (activity-tab-host @activity)
                            (.getString activity
                                        (resource :string
                                                  :errors_tab_label))))))

(defn get-animation-duration [^org.turtle.geometry.TurtleGraphics activity]
  (text-input->int (duration-entry @activity) 0))

(declare eval-turtle-program draw-scene
         clear-intermed-bitmap redraw-indermed-bitmap)

(defn make-scale-transform ^Matrix
  [^org.turtle.geometry.TurtleGraphics activity
   scale-factor]
  (let [m ^Matrix (make-identity-transform)
        draw-canvas ^Canvas (get-in @activity [:draw-state :draw-canvas])
        width (.getWidth draw-canvas)
        height (.getHeight draw-canvas)]
    (.setScale m
               scale-factor scale-factor
               (float (/ width 2)) (float (/ height 2)))
    m))

(defn update-activity-view-transform
  [^org.turtle.geometry.TurtleGraphics activity
   ^Matrix new-transform]
  (let [old-view-transform (draw-area-view-transform @activity)]
    (swap! (.state activity)
           assoc
           :draw-area-view-transform
           (matrix-mult old-view-transform new-transform)
           :intermediate-transform
           (make-identity-transform)))
  (redraw-indermed-bitmap activity)
  (draw-scene activity))

(defn stop-turtle-thread [^org.turtle.geometry.TurtleGraphics activity]
  (let [turtle-thread (turtle-program-thread @activity)]
    (when turtle-thread
      (.interrupt (.getEvaluator (jscheme @activity)) turtle-thread)
      ;; (.interrupt turtle-thread)
      (swap! (.state activity) assoc :turtle-program-thread nil)
      (.join turtle-thread))))

(defn register-interaction-detectors [^org.turtle.geometry.TurtleGraphics activity
                                      ^View root]
  (let [pointer-id (atom nil)

        make-move-transform
        (fn [dx dy]
          (let [m ^Matrix (make-identity-transform)]
            (.setTranslate m dx dy)
            m))
        move-transform-from-events
        (fn ^Matrix
          ([^MotionEvent start
            ^MotionEvent end]
             (let [id @pointer-id
                   dx (- (.getX end id) (.getX start id))
                   dy (- (.getY end id) (.getY start id))]
               (make-move-transform dx dy))))

        move-listener
        (proxy [GestureDetector$SimpleOnGestureListener] []
          (onDown [^MotionEvent event]
            (reset! pointer-id (.getPointerId event 0))
            (.onScroll ^GestureDetector$SimpleOnGestureListener this
                       event
                       event
                       0
                       0)
            true)
          (onScroll [^MotionEvent start
                     ^MotionEvent current
                     dist-x
                     dist-y]
            (swap! (.state activity) assoc-in
                   [:intermediate-transform]
                   (move-transform-from-events start current))
            (draw-scene activity)
            true)
          (onFling [^MotionEvent start
                    ^MotionEvent end
                    velocity-x
                    velocity-y]
            (update-activity-view-transform activity
                                            (move-transform-from-events start end))
            true))

        scale-listener
        (proxy [ScaleGestureDetector$OnScaleGestureListener] []
          (onScaleBegin [^ScaleGestureDetector detector]
            true)
          (onScale [^ScaleGestureDetector detector]
            (log "scale-listener, onScale: (.getScaleFactor detector) = %s"
                 (.getScaleFactor detector))
            (swap! (.state activity) assoc-in
                   [:intermediate-transform]
                   (make-scale-transform activity (.getScaleFactor detector)))
            (draw-scene activity)
            false)
          (^void onScaleEnd [^ScaleGestureDetector detector]
            (log "scale-listener, onScaleEnd: (.getScaleFactor detector) = %s"
                 (.getScaleFactor detector))
            (update-activity-view-transform
             activity
             (make-scale-transform activity (.getScaleFactor detector)))))

        move-detector (GestureDetector. activity
                                        move-listener
                                        nil  ;; no special Handler provided
                                        true ;; ignore multitouch
                                        )
        scale-detector (ScaleGestureDetector. activity scale-listener)]
    (.setOnTouchListener
     root
     (reify View$OnTouchListener
       (^boolean onTouch [this ^View v ^MotionEvent event]
         (let [move-result (.onTouchEvent ^GestureDetector move-detector
                                          ^MotionEvent event)]
           (cond move-result
                 true
                 (get-in @activity [:user-options :track-scaling-touches])
                 (.onTouchEvent ^ScaleGestureDetector scale-detector
                                ^MotionEvent event)
                 :else
                 false)))))
    (.setOnKeyListener
     root
     (reify View$OnKeyListener
       (^boolean onKey [this ^View v ^int key-code ^KeyEvent event]
         ;; false
         (if (and (= (.getAction event)
                     KeyEvent/ACTION_DOWN)
                  (contains? #{KeyEvent/KEYCODE_DPAD_DOWN
                               KeyEvent/KEYCODE_DPAD_LEFT
                               KeyEvent/KEYCODE_DPAD_RIGHT
                               KeyEvent/KEYCODE_DPAD_UP}
                             key-code))
           (do
             (update-activity-view-transform
              activity
              (condp = key-code
                KeyEvent/KEYCODE_DPAD_DOWN
                (make-move-transform 0 -25)
                KeyEvent/KEYCODE_DPAD_LEFT
                (make-move-transform +25 0)
                KeyEvent/KEYCODE_DPAD_RIGHT
                (make-move-transform -25 0)
                KeyEvent/KEYCODE_DPAD_UP
                (make-move-transform 0 +25)
                :else
                (log "invalid key code: %s (%d)"
                     (get {KeyEvent/KEYCODE_DPAD_DOWN
                           "KEYCODE_DPAD_DOWN"
                           KeyEvent/KEYCODE_DPAD_LEFT
                           "KEYCODE_DPAD_LEFT"
                           KeyEvent/KEYCODE_DPAD_RIGHT
                           "KEYCODE_DPAD_RIGHT"
                           KeyEvent/KEYCODE_DPAD_UP
                           "KEYCODE_DPAD_UP"}
                          key-code
                          "UNKNOWN")
                     key-code)))
             true)
           false))))))



(def save-state-config
  (letfn [(save-matrix [activity-key
                        key
                        ^org.turtle.geometry.TurtleGraphics activity
                        ^Bundle bundle]
            (let [values (float-array 9 0)]
              (.getValues ^Matrix (get-in @activity activity-key) values)
              (.putFloatArray bundle key values)))
          (restore-matrix [activity-key
                           key
                           ^org.turtle.geometry.TurtleGraphics activity
                           ^Bundle bundle]
            (let [m (Matrix.)]
              (.setValues m (.getFloatArray bundle key))
              (swap! (.state activity) assoc-in
                     activity-key
                     m)))]
    [["intermediate-bitmap"
      (fn [key
           ^org.turtle.geometry.TurtleGraphics activity
           ^Bundle bundle]
        (let [bitmap (get-in @activity
                             [:draw-state :intermediate-bitmap])]
          (.putParcelable bundle key bitmap)))
      (fn [key
           ^org.turtle.geometry.TurtleGraphics activity
           ^Bundle bundle]
        (let [bitmap (.getParcelable bundle key)]
          (swap! (.state activity) assoc-in
                 [:draw-state :intermediate-bitmap]
                 bitmap)))]
     ["turtle-lines"
      (fn [key
           ^org.turtle.geometry.TurtleGraphics activity
           ^Bundle bundle]
        (let [lines (get-in @activity [:turtle-state :lines])]
          (.putFloatArray bundle
                          (str key "-coords")
                          (into-array Float/TYPE
                                      (flatten
                                       (map (fn [[p1 p2 _]] [p1 p2]) lines))))
          (.putIntArray bundle
                        (str key "-colors")
                        (into-array Integer/TYPE
                                    (map (fn [[_ _ color]] color) lines)))))
      (fn [key
           ^org.turtle.geometry.TurtleGraphics activity
           ^Bundle bundle]
        (let [flat-pts (.getFloatArray bundle (str key "-coords"))
              pts (vec (map vec (partition 2 (map vec (partition 2 flat-pts)))))
              colors (vec (.getIntArray bundle (str key "-colors")))
              res-lines (map (fn [pts color] (conj pts color)) pts colors)]
          (swap! (.state activity) assoc-in
                 [:turtle-state :lines]
                 res-lines)))]
     ["program-source"
      (fn [key
           ^org.turtle.geometry.TurtleGraphics activity
           ^Bundle bundle]
        (.putString bundle
                    key
                    (str (.getText (program-source-editor @activity)))))
      (fn [key
           ^org.turtle.geometry.TurtleGraphics activity
           ^Bundle bundle]
        (.setText (program-source-editor @activity)
                  (.getString bundle key)))]
     ["turtle-view-transform"
      #(save-matrix [:draw-area-view-transform] %1 %2 %3)
      #(restore-matrix [:draw-area-view-transform] %1 %2 %3)]
     ["intermediate-transform"
      #(save-matrix [:intermediate-transform] %1 %2 %3)
      #(restore-matrix [:intermediate-transform] %1 %2 %3)]]))

(defn -onSaveInstanceState [^org.turtle.geometry.TurtleGraphics this
                            ^Bundle bundle]
  (.superOnSaveInstanceState this bundle)
  (doseq [[key save _] save-state-config]
    (save key this bundle)))

(defn -onConfigurationChanged [^org.turtle.geometry.TurtleGraphics this
                               configuration]
  (.superOnConfigurationChanged this configuration))

(defn -onCreate [^org.turtle.geometry.TurtleGraphics this
                 ^Bundle bundle]
  (reset! *activity* this)
  ;; (neko.init/init this :port 10001)
  (doto this
    (. superOnCreate bundle)
    (. requestWindowFeature Window/FEATURE_INDETERMINATE_PROGRESS)
    (. setContentView (resource :layout :main))
    (. setProgressBarIndeterminateVisibility false))
  (make-ui-dimmer (.findViewById this (resource :main_layout)))

  (let [activity ^org.turtle.geometry.TurtleGraphics this
        inflater (.getLayoutInflater this)

        ^TabHost tab-host (.findViewById this (resource :main_layout))
        ^View editor-layout (.inflate inflater
                                      ^int (resource :layout :program_editor)
                                      nil)
        ^View error-output-layout (.inflate inflater
                                            ^int (resource :layout :error_output)
                                            nil)
        ^View draw-area-layout (.inflate inflater
                                         ^int (resource :layout :draw_area)
                                         nil)

        draw-area-view ^SurfaceView (.findViewById draw-area-layout
                                                   (resource :draw_area))
        source-editor-view (.findViewById editor-layout
                                          (resource :program_input))
        error-output-view ^TextView (.findViewById error-output-layout
                                                   (resource :error_output))
        duration-entry-view (.findViewById editor-layout
                                           (resource :duration_entry))

        button-run ^Button (.findViewById editor-layout
                                          (resource :button_run))
        button-stop ^Button (.findViewById editor-layout
                                           (resource :button_stop))
        button-clear ^Button (.findViewById editor-layout
                                            (resource :button_clear))
        button-save ^Button (.findViewById editor-layout
                                           (resource :button_save))
        button-load ^Button (.findViewById editor-layout
                                           (resource :button_load))

        rotated-turtle-bitmap (rotate-right-90
                               (BitmapFactory/decodeResource
                                (.getResources this)
                                (resource :drawable :turtle_marker)))



        output-accum (StringBuilder.)
        clear-error-output
        (fn []
          ;; clear error output
          (.delete output-accum
                   0
                   (.length output-accum))
          (.runOnUiThread
           activity
           (fn []
             (.setText (error-output @activity) ""))))
        error-sink
        (let [bytes?
              (fn [obj]
                (= (type obj)
                   (Class/forName "[B")))
              error-output-stream ^OutputStream
              (proxy [OutputStream] []
                (flush ^void []
                  (let [s (str output-accum)]
                    (.runOnUiThread activity
                                    (fn []
                                      (.setText (error-output @activity) s))))
                  ;; (report-error activity (str output-accum) false)
                  )
                (write
                  ([^bytes bs offset count]
                     (loop [i 0]
                       (when (< i count)
                         (.append output-accum (char (aget bs (+ offset i))))
                         (recur (inc i))))
                     ;; (.append output-accum
                     ;;          ^bytes bs
                     ;;          ;; (char-array (map char bs))
                     ;;          ^int offset
                     ;;          ^int count)
                     )
                  ([b]
                     (if (bytes? b)
                       (.append output-accum ^bytes b)
                       (.append output-accum (char b))))))]
          (PrintWriter. error-output-stream
                        ;; do auto-flush on newlines
                        true))]
    (swap! (.state this)
           assoc
           :draw-area draw-area-view
           :program-source-editor source-editor-view
           :error-output error-output-view
           :duration-entry duration-entry-view
           :activity-menu nil
           :activity-tab-host tab-host
           :error-writer error-sink

           :draw-state (DrawState. false nil nil)
           :user-options (UserOptions. true true)
           :global-preferences (.getSharedPreferences this
                                                      shared-preferences-tag
                                                      Context/MODE_PRIVATE)
           :jscheme (JScheme.)

           :intermediate-transform (make-identity-transform) ;; creates identity
           :turtle-program-thread nil
           :draw-area-view-transform (make-identity-transform) ;; creates identity
           :turtle-bitmap rotated-turtle-bitmap
           :turtle-state initial-turtle-state)

    (.. draw-area-view (getHolder) (addCallback this))
    ;; make error-output-view scrollable
    (.setMovementMethod error-output-view
                        (ScrollingMovementMethod.))

    (register-interaction-detectors activity draw-area-view)
    (let [source-tab-tag (.getString this (resource :string :source_tab_label))
          errors-tab-tag (.getString this (resource :string :errors_tab_label))
          graphics-tab-tag (.getString this (resource :string
                                                      :graphics_tab_label))]
      (doto tab-host
        (. setup)
        (. addTab (doto (.newTabSpec tab-host source-tab-tag)
                    (. setIndicator source-tab-tag)
                    (. setContent (reify TabHost$TabContentFactory
                                    (createTabContent [unused-this _]
                                      editor-layout)))))
        (. addTab (doto (.newTabSpec tab-host errors-tab-tag)
                    (. setIndicator errors-tab-tag)
                    (. setContent (reify TabHost$TabContentFactory
                                    (createTabContent [unused-this _]
                                      error-output-layout)))))
        (. addTab (doto (.newTabSpec tab-host graphics-tab-tag)
                    (. setIndicator graphics-tab-tag)
                    (. setContent (reify TabHost$TabContentFactory
                                    (createTabContent [unused-this _]
                                      draw-area-layout))))))

      (let [tab-widget ^TabWidget (.getTabWidget tab-host)
            new-height (TypedValue/applyDimension
                        TypedValue/COMPLEX_UNIT_DIP
                        30
                        (.getDisplayMetrics (.getResources this)))]
        (dotimes [i (.getChildCount tab-widget)]
          (set! (.height (.getLayoutParams (.getChildAt tab-widget i)))
                new-height))))

    (.setOnClickListener
     button-run
     (reify android.view.View$OnClickListener
       (onClick [this unused-button]
         (clear-error-output)
         (let [old-turtle-thread (turtle-program-thread @activity)]
           (when (or (not old-turtle-thread)
                     (not (.isAlive old-turtle-thread)))
             (let [turtle-thread (eval-turtle-program
                                  (str (.getText (program-source-editor @activity)))
                                  activity)]
               (report-error activity "no errors yet")
               (swap! (.state activity)
                      assoc
                      :turtle-program-thread
                      turtle-thread)))
           (when (.show-graphics-when-run (user-options @activity))
             (.setCurrentTabByTag tab-host
                                  (.getString activity
                                              (resource :string
                                                        :graphics_tab_label))))))))

    (.setOnClickListener
     button-stop
     (reify android.view.View$OnClickListener
       (onClick [this unused-button]
         (.show (Toast/makeText activity
                                "Stopping turtle"
                                Toast/LENGTH_SHORT))
         (stop-turtle-thread activity))))

    (.setOnClickListener
     button-clear
     (reify android.view.View$OnClickListener
       (onClick [this unused-button]
         (.show (Toast/makeText activity
                                "Clearing"
                                Toast/LENGTH_SHORT))
         (clear-error-output)
         (swap! (.state activity) assoc-in
                [:turtle-state]
                initial-turtle-state)
         (clear-intermed-bitmap activity)
         (draw-scene activity))))

    (.setOnClickListener
     button-save
     (reify android.view.View$OnClickListener
       (onClick [this unused-button]
         (let [get-file-intent (Intent. Intent/ACTION_PICK)]
           (doto get-file-intent
             (.setDataAndType (Uri/fromFile (File. "/mnt/sdcard"))
                              "vnd.android.cursor.dir/lysesoft.andexplorer.file")
             (.putExtra "explorer_title" "Save As...")
             (.putExtra "browser_line" "enabled")
             (.putExtra "browser_line_textfield" "file.tg"))
           (.startActivityForResult activity
                                    get-file-intent
                                    intent-save-file)))))

    (.setOnClickListener
     button-load
     (reify android.view.View$OnClickListener
       (onClick [this unused-button]
         (let [get-file-intent (Intent. Intent/ACTION_PICK)]
           (.setDataAndType get-file-intent
                            (Uri/fromFile (File. "/mnt/sdcard"))
                            "vnd.android.cursor.dir/lysesoft.andexplorer.file")
           (.putExtra get-file-intent
                      "browser_filter_extension_whitelist"
                      "*.tg")
           (.startActivityForResult activity
                                    get-file-intent
                                    intent-load-file)))))

    (.setError (.getEvaluator (jscheme @this))
               (error-writer @this))

    ;; initialize scheme by loading init file
    (log "loading jscheme init files...")
    (try
      (with-open [input (BufferedReader.
                         (InputStreamReader.
                          (.. activity (getResources)
                              (getAssets)
                              (open "jscheme.init"))))]
        (.load (jscheme @this) input))
      (log "loading jscheme init files... ok")
      (catch Exception e
        (log "Exception while initializing jscheme: %s" e))))

  (let [preferences (global-preferences @this)]
    (.setText (program-source-editor @this)
              ;; "(let ((a 100)
      ;; (b (/ a 2)))
  ;; (forward a)
  ;; (left 120)
  ;; (forward b))"

              (.getString preferences
                          preferences-last-program-tag
                          "(use-color magenta)\n(forward 100)\n(left 90)\n(forward 100)\n")))

  (when bundle
    (doseq [[key _ restore] save-state-config]
      (restore key this bundle))))

(defn ^boolean -onCreateOptionsMenu [^org.turtle.geometry.TurtleGraphics this
                                     ^Menu menu]
  (.superOnCreateOptionsMenu this menu)
  (.inflate ^MenuInflater (.getMenuInflater this)
            (resource :menu :main)
            menu)
  true)

(defn ^boolean -onMenuItemSelected [^org.turtle.geometry.TurtleGraphics this
                                    feature-id
                                    ^MenuItem item]
  (let [zoom-in-factor 1.25
        zoom-out-factor 0.75
        item-id (.getItemId item)]
    (condp = item-id
      (resource :id :menu_center_yourself)
      (do
        (when (get-in @this [:draw-state :surface-available?])
          (swap! (.state this)
                 assoc
                 :draw-area-view-transform
                 (make-identity-transform)
                 :intermediate-transform
                 (make-identity-transform))
          (redraw-indermed-bitmap this)
          (draw-scene this))
        true)
      (resource :id :menu_zoom_in)
      (do
        (update-activity-view-transform this
                                        (make-scale-transform this
                                                              zoom-in-factor))
        true)
      (resource :id :menu_zoom_out)
      (do
        (update-activity-view-transform this
                                        (make-scale-transform this
                                                              zoom-out-factor))
        true)
      (resource :id :menu_refresh)
      (do
        (when (get-in @this [:draw-state :surface-available?])
          (redraw-indermed-bitmap this)
          (draw-scene this))
        true)
      :else
      (.superOnMenuItemSelected this feature-id item))))

(defn -onResume [^org.turtle.geometry.TurtleGraphics this]
  (.superOnResume this))

(defn -onPause [^org.turtle.geometry.TurtleGraphics this]
  (.superOnPause this))

(defn -onStop [^org.turtle.geometry.TurtleGraphics this]
  (.superOnStop this)
  (let [preferences (global-preferences @this)
        editor (.edit preferences)]
    (.putString editor
                preferences-last-program-tag
                (str (.getText (program-source-editor @this))))
    (.commit editor)))

(defn -onDestroy [^org.turtle.geometry.TurtleGraphics this]
  (.superOnDestroy this)
  ;; (neko.init/deinit)
  ;; (neko.compilation/clear-cache)
  (stop-turtle-thread this))

(defn -onBackPressed [^org.turtle.geometry.TurtleGraphics this]
  (log "onBackPressed")
  (let [tab-host (activity-tab-host @this)
        source-tab-tag (.getString this
                                   (resource :string :source_tab_label))]
    (if (= (.getCurrentTabTag tab-host)
           source-tab-tag)
      (.superOnBackPressed this)
      (.setCurrentTabByTag tab-host source-tab-tag))))

(defn -onActivityResult
  [^org.turtle.geometry.TurtleGraphics this
   request-code
   result-code
   ^Intent data]
  (when (and (= result-code Activity/RESULT_OK)
             (not (nil? data))
             (not (nil? (.getData data))))
    (cond
     (= request-code intent-save-file)
     (let [uri (.getData data)
           filename (.getPath uri)
           save
           (fn []
             (spit filename (.getText (program-source-editor @this)))
             (.show (Toast/makeText this
                                    (format "Saved as %s" filename)
                                    Toast/LENGTH_SHORT)))]
       (if (.exists (File. filename))
         (let [activity this
               frag-dialog-manager
               (proxy [DialogFragment] []
                 (^Dialog onCreateDialog [^Bundle saved-state]
                   (let [builder (AlertDialog$Builder. activity)]
                     (doto builder
                       (.setTitle "Please confirm file overwrite")
                       (.setIcon (android-resource :drawable :stat_sys_warning))
                       (.setCancelable true)
                       (.setMessage (format "Do you want to overwrite %s file?"
                                            filename))
                       (.setPositiveButton (android-resource :string :ok)
                                           (reify DialogInterface$OnClickListener
                                             (onClick [unused-this dialog which]
                                               (save))))
                       (.setNegativeButton (android-resource :string :cancel)
                                           nil))
                     (.create builder))))]
           (.show frag-dialog-manager
                  (.getSupportFragmentManager this)
                  "confirm")
           true)
         (save)))
     (= request-code intent-load-file)
     (let [uri (.getData data)
           filename (.getPath uri)]
       (.setText (program-source-editor @this)
                 ^String (slurp filename))
       (.show (Toast/makeText this
                              (format "Loaded %s" filename)
                              Toast/LENGTH_SHORT)))
     :else
     ;; ignore it
     nil)))


(defn clear-intermed-bitmap [^org.turtle.geometry.TurtleGraphics this]
  (when-let [intermed-canvas (get-in @this [:draw-state :draw-canvas])]
    (.drawColor ^Canvas intermed-canvas Color/WHITE)))

(defn -surfaceChanged [^org.turtle.geometry.TurtleGraphics this
                       ^SurfaceHolder holder
                       format
                       width
                       height]
  (log "surfaceChanged")
  (let [interm-bitmap ^Bitmap (get-in @this [:draw-state :intermediate-bitmap])
        use-old-bitmap? (and (not (nil? interm-bitmap))
                             (= width (.getWidth interm-bitmap))
                             (= height (.getHeight interm-bitmap)))
        new-bitmap
        (if use-old-bitmap?
          interm-bitmap
          (Bitmap/createBitmap width height
                               Bitmap$Config/ARGB_8888))
        canvas (Canvas. new-bitmap)]
    (swap! (.state this)
           update-in
           [:draw-state]
           assoc
           :surface-available? true
           :intermediate-bitmap new-bitmap
           :draw-canvas canvas)
    (when-not use-old-bitmap?
      (redraw-indermed-bitmap this))
    (draw-scene this)))

(defn -surfaceCreated [^org.turtle.geometry.TurtleGraphics this
                       ^SurfaceHolder holder]
  (log "surfaceCreated")
  ;; albeit surface is created, our bitmap are not initialized yet
  ;; (swap! (.state this)
  ;;        update-in
  ;;        [:draw-state]
  ;;        assoc
  ;;        :surface-available? true)
  )

(defn -surfaceDestroyed [^org.turtle.geometry.TurtleGraphics this
                         ^SurfaceHolder holder]
  (log "surfaceDestroyed")
  (swap! (.state this)
         update-in
         [:draw-state]
         assoc
         :surface-available? false))

;;;; drawing functions

(defn redraw-indermed-bitmap [^org.turtle.geometry.TurtleGraphics this]
  (let [intermed-canvas ^Canvas (get-in @this [:draw-state :draw-canvas])]
    (assert intermed-canvas "error: surface view drawing-canvas is nil")
    (clear-intermed-bitmap this)
    (with-centered-canvas intermed-canvas
      (draw-with-page-transform
       intermed-canvas
       (draw-area-view-transform @this)

       ;; loop over lines and keep paint so it may be reused if
       ;; color hasn't changed for successive lines
       (let [lines (seq (get-in @this [:turtle-state :lines]))
             prev-color (when lines (nth (first lines) 2))
             prev-paint (when prev-color (color->paint prev-color))]
         (loop [lines lines
                prev-color prev-color
                prev-paint prev-paint]
           (when lines
             (let [[[startx starty] [endx endy] color] (first lines)
                   paint (if (= prev-color color)
                           prev-paint
                           (color->paint color))]
               (.drawLine intermed-canvas
                          startx starty
                          endx endy
                          paint)
               (recur (next lines)
                      color
                      paint)))))))))

(defn add-line-to-intermed-bitmap [^org.turtle.geometry.TurtleGraphics this
                                   [startx starty]
                                   [endx endy]
                                   color]
  (let [intermed-canvas ^Canvas (get-in @this [:draw-state :draw-canvas])]
    (assert intermed-canvas "error: surface view drawing-canvas is nil")

    (with-centered-canvas intermed-canvas
      (draw-with-page-transform
       intermed-canvas
       (draw-area-view-transform @this)

       (.drawLine intermed-canvas
                  startx starty
                  endx endy
                  (color->paint color))))))

(defn draw-turtle-bitmap
  ([^Canvas canvas ^org.turtle.geometry.TurtleGraphics activity]
     (let [bitmap (turtle-bitmap @activity)
           target-width 50
           target-height 27
           bitmap-center-x ^double (/ target-width 2.0)
           bitmap-center-y ^double (/ target-height 2.0)
           [x y] (get-in @activity [:turtle-state :position])
           heading (get-in @activity [:turtle-state :angle])]
       (with-saved-matrix canvas
         (doto canvas
           (.translate (- x bitmap-center-x) (- y bitmap-center-y))
           (.rotate heading
                    bitmap-center-x
                    bitmap-center-y)
           (.drawBitmap bitmap
                        nil
                        (Rect. 0 0 target-width target-height)
                        ^Paint (antialiasing-paint)))))))

(defn draw-scene [^org.turtle.geometry.TurtleGraphics this]
  ;; (log "drawing scene")
  (if (get-in @this [:draw-state :surface-available?])
    (if-let [surface-canvas ^Canvas (.. (draw-area @this)
                                        (getHolder)
                                        (lockCanvas))]
      (try
        (.drawColor surface-canvas Color/WHITE)
        (let [ ;;start-time (System/currentTimeMillis)
              ]
          ;; this draw on intermediate-bitmap in draw-state
          (draw-with-page-transform
           surface-canvas
           (intermediate-transform @this)
           (.drawBitmap surface-canvas
                        ^Bitmap (get-in @this [:draw-state :intermediate-bitmap])
                        0.0 ;; left
                        0.0 ;; top
                        nil ;; paint
                        ))

          (with-centered-canvas surface-canvas
            (draw-with-page-transform
             surface-canvas
             (matrix-mult (draw-area-view-transform @this)
                          (intermediate-transform @this))

             ;; draw line from previous position to current position
             (when-not (get-in @this [:turtle-state :pen-up?])
               (let [[startx starty] (get-in @this [:turtle-state :prev-position])
                     [currentx currenty] (get-in @this [:turtle-state :position])
                     color (get-in @this [:turtle-state :color])]
                 (.drawLine surface-canvas
                            startx starty
                            currentx currenty
                            (line-paint color))))
             (draw-turtle-bitmap surface-canvas this)))
          ;; (log "frame took %s ms" (- (System/currentTimeMillis) start-time))
          )
        (finally
          (.. (draw-area @this)
              (getHolder)
              (unlockCanvasAndPost surface-canvas))))
      ;; if it returned nil we have nothing to do
      (log "draw-scene: lockCanvas returned nil"))
    (log "attempt to draw when surface is not available")))


;;;; eval turtle program

;; (defn parse-program [^String str]
;;   (let [lexer (TurtleLexer. (ANTLRStringStream. str))
;;         tokens (CommonTokenStream. lexer)
;;         parser (TurtleParser. tokens)]
;;     (.program parser)))

(def ^{:const true} pi 3.141592653589793238462643383279502884197)

(defn ^double deg->rad [^double x]
  (* x (/ pi 180.0)))

(defn ^double rad->deg [^double x]
  (* x (/ pi 180.0)))

(defn scheme-list->clojure-list [^Pair p]
  (lazy-seq
   (when-not (.isEmpty p)
     (cons (.first p) (scheme-list->clojure-list (.rest p))))))

(defn eval-turtle-program [^String program-text
                           ^org.turtle.geometry.TurtleGraphics activity]
  (letfn [(move [dist]
            (let [theta (get-in @activity [:turtle-state :angle])
                  pos   (get-in @activity [:turtle-state :position])
                  color (get-in @activity [:turtle-state :color])
                  delta-x (* dist (StrictMath/cos (deg->rad theta)))
                  delta-y (* dist (StrictMath/sin (deg->rad theta)))
                  delta [delta-x delta-y]
                  new-pos (map + delta pos)
                  [old-x old-y] pos
                  [new-x new-y] new-pos
                  draw-line? (not (get-in @activity [:turtle-state :pen-up?]))

                  anim-duration (get-animation-duration activity)]

              ;; (stop-if-interrupted)
              (swap! (.state activity) assoc-in
                     [:turtle-state :prev-position]
                     pos)
              (let [start-time (System/currentTimeMillis)]
                (loop []
                  ;; (stop-if-interrupted)
                  (let [curr-time (System/currentTimeMillis)
                        diff (- curr-time start-time)]
                    (assert (<= 0 diff))
                    (when (< diff anim-duration)
                      (let [k (/ diff anim-duration)
                            intermed-pos [(+ old-x (* k delta-x))
                                          (+ old-y (* k delta-y))]]
                        (swap! (.state activity) assoc-in
                               [:turtle-state :position]
                               intermed-pos)
                        (draw-scene activity))
                      (recur)))))
              (swap! (.state activity) assoc-in
                     [:turtle-state :position]
                     new-pos)

              (when draw-line?
                (swap! (.state activity) update-in
                       [:turtle-state :lines]
                       conj
                       (vector pos new-pos color))
                (add-line-to-intermed-bitmap activity
                                             pos
                                             new-pos
                                             color)
                (draw-scene activity))))

          (rotate [delta]
            (let [theta (get-in @activity [:turtle-state :angle])
                  anim-duration (get-animation-duration activity)]

              (let [start-time (System/currentTimeMillis)]
                (loop []
                  ;; (stop-if-interrupted)
                  (let [curr-time (System/currentTimeMillis)
                        diff (- curr-time start-time)]
                    (assert (<= 0 diff))
                    (when (< diff anim-duration)
                      (let [k (/ diff anim-duration)
                            intermed-angle (+ theta (* k delta))]
                        (swap! (.state activity) assoc-in
                               [:turtle-state :angle]
                               intermed-angle)
                        (draw-scene activity))
                      (recur)))))
              (swap! (.state activity) assoc-in
                     [:turtle-state :angle]
                     (+ theta delta))
              (draw-scene activity)))]
    (let [procs {"forward" (proxy [Procedure] [1 1]
                             (apply [^objects args]
                               (let [dist (aget args 0)]
                                 (move dist))))
                 "fd" "forward"
                 "backward" (proxy [Procedure] [1 1]
                              (apply [^objects args]
                                (let [dist (aget args 0)]
                                  (move (- dist)))))
                 "bd" "backward"
                 "bk" "backward"
                 "left" (proxy [Procedure] [1 1]
                          (apply [^objects args]
                            (let [delta (aget args 0)]
                              (rotate (- delta)))))
                 "lt" "left"
                 "right" (proxy [Procedure] [1 1]
                           (apply [^objects args]
                             (let [delta (aget args 0)]
                               (rotate delta))))
                 "rt" "right"
                 "pen-up?" (proxy [Procedure] [0 0]
                             (apply [^objects args]
                               (get-in @activity [:turtle-state :pen-up?])))
                 "pen-up" (proxy [Procedure] [0 0]
                            (apply [^objects args]
                              (swap! (.state activity) assoc-in
                                     [:turtle-state :pen-up?]
                                     true)))
                 "pen-down" (proxy [Procedure] [0 0]
                              (apply [^objects args]
                                (swap! (.state activity) assoc-in
                                       [:turtle-state :pen-up?]
                                       false)))
                 "heading" (proxy [Procedure] [0 0]
                             (apply [^objects args]
                               (get-in @activity [:turtle-state :angle])))
                 "set-heading" (proxy [Procedure] [1 1]
                                 (apply [^objects args]
                                   (let [new-heading (aget args 0)]
                                     (swap! (.state activity) assoc-in
                                            [:turtle-state :angle]
                                            new-heading))))
                 "use-color"
                 (proxy [Procedure] [1 3]
                   (apply [^objects args]
                     (let [nargs (alength args)
                           r (aget args 0)
                           rest ^Pair (aget args 1)
                           new-color
                           (cond (.isEmpty rest)
                                 ;; here r is not red component but encoded
                                 ;; argb quadruple
                                 r
                                 (and (= 2 (.length rest))
                                      (number? (.first rest))
                                      (number? (.second rest)))
                                 (let [g (.first rest)
                                       b (.second rest)]
                                   (Color/argb 0xff r g b))
                                 :else
                                 (E/error
                                  (format "Invalid arguments to procedure %s: expected 1 (encoded color) or 3 (rgb) integers, but got: %s"
                                          (.getName ^Procedure this)
                                          (Pair. r rest))))]

                       (swap! (.state activity) assoc-in
                              [:turtle-state :color]
                              new-color))))
                 "get-color" (proxy [Procedure] [0 0]
                               (apply [^objects args]
                                 (get-in @activity [:turtle-state :color])))

                 "cos" (proxy [Procedure] [1 1]
                         (apply [^objects args]
                           (let [x (aget args 0)]
                             (StrictMath/cos (deg->rad x)))))
                 "sin" (proxy [Procedure] [1 1]
                         (apply [^objects args]
                           (let [x (aget args 0)]
                             (StrictMath/sin (deg->rad x)))))
                 "rcos" (proxy [Procedure] [1 1]
                          (apply [^objects args]
                            (let [x (aget args 0)]
                              (StrictMath/cos (double x)))))
                 "rsin" (proxy [Procedure] [1 1]
                          (apply [^objects args]
                            (let [x (aget args 0)]
                              (StrictMath/sin (double x)))))
                 "**" (proxy [Procedure] [2 2]
                        (apply [^objects args]
                          (let [a (aget args 0)
                                b (aget args 1)]
                            (clojure.math.numeric-tower/expt a b))))
                 "deg->rad" (proxy [Procedure] [1 1]
                              (apply [^objects args]
                                (let [x (aget args 0)]
                                  (deg->rad x))))
                 "rad->deg" (proxy [Procedure] [1 1]
                              (apply [^objects args]
                                (let [x (aget args 0)]
                                  (rad->deg x))))

                 ;; do nothing in release
                 ;; "alog" (proxy [Procedure] [1 Integer/MAX_VALUE]
                 ;;          (apply [^objects args]
                 ;;            ;; (let [arglist (scheme-list->clojure-list
                 ;;            ;;                (Pair. (aget args 0)
                 ;;            ;;                       (aget args 1)))]
                 ;;            ;;   ;; (log "original arglist = %s" (vec args))
                 ;;            ;;   ;; (log "converted arglist = %s" (vec arglist))
                 ;;            ;;   ;; (apply log-func arglist)
                 ;;            ;;   )
                 ;;            false))
                 }
          constants {"red" (Color/argb 0xff 0xdc 0x32 0x2f)
                     "orange" (Color/argb 0xff 0xcb 0x4b 0x16)
                     "yellow" (Color/argb 0xff 0xb5 0x89 0x00)
                     "green" (Color/argb 0xff 0x85 0x99 0x00)
                     "cyan" (Color/argb 0xff 0x2a 0xa1 0x98)
                     "blue" (Color/argb 0xff 0x26 0x8b 0xd2)
                     "violet" (Color/argb 0xff 0x6c 0x71 0xc4)
                     "magenta" (Color/argb 0xff 0xd3 0x36 0x82)
                     "pi" pi}
          scm (jscheme @activity)
          evaluator (.getEvaluator scm)
          bind-value (fn [name value]
                       (.setGlobalValue scm name value))
          bind-proc (fn [real-name resolved-name]
                      (when-let [proc ^Procedure (get procs resolved-name)]
                        (if (string? proc)
                          (recur real-name proc)
                          (do
                            (bind-value real-name proc)
                            ;; bind name only if this is original procedure
                            (when (= real-name resolved-name)
                              (.setName proc real-name))))))
          turtle-thread
          (Thread.
           (.getThreadGroup (Thread/currentThread))
           (fn []
             (try
               (doseq [proc-name (keys procs)] (bind-proc proc-name proc-name))
               (doseq [name (keys constants)] (bind-value name (get constants name)))
               ;; (log (str "running program " (read-program program-text)))
               (.load scm (StringReader. program-text))

               (catch Evaluator$InterruptedException e
                 (.runOnUiThread
                  activity
                  (fn []
                    (report-error activity
                                  "Interrupted"
                                  ;; this is expected exception, do not disturb user
                                  false))))
               (catch java.lang.StackOverflowError e
                 (.runOnUiThread
                  activity
                  (fn []
                    (report-error activity (str "Stack overflow error:\n" e)))))
               (catch InterruptedException _
                 (.runOnUiThread
                  activity
                  (fn []
                    (report-error activity "Interrupted" false))))
               (catch Exception e
                 (.runOnUiThread
                  activity
                  (fn []
                    (report-error activity
                                  (str "Error during turtle program evaluation:\n"
                                       e
                                       "\nStacktrace:\n"
                                       (extract-stacktrace e))))))))
           "turtle program thread"
           (* 16 1024 1024))]
      (let [accumulated-lines (get-in @activity [:turtle-state :lines])]
        (swap! (.state activity) assoc-in
               [:turtle-state]
               (assoc initial-turtle-state :lines accumulated-lines)))
      (draw-scene activity)
      (log "starting thread")
      (.start turtle-thread)
      turtle-thread)))


