
;;;; prelude

(ns org.turtle.geometry.TurtleGraphics
  (:gen-class :main false
              :name org.turtle.geometry.TurtleGraphics
              :extends android.app.Activity
              :implements [clojure.lang.IDeref
                           android.view.SurfaceHolder$Callback]
              :exposes-methods {onCreate superOnCreate
                                onCreateOptionsMenu superOnCreateOptionsMenu
                                onMenuItemSelected superOnMenuItemSelected
                                onResume superOnResume
                                onPause superOnPause
                                onDestroy superOnDestroy
                                onBackPressed superOnBackPressed}
              :state ^{:tag ActivityState} state
              :init init
              :constructors {[] []})
  (:import [android.app Activity]
           [android.content Context]
           [android.graphics Bitmap Bitmap$Config BitmapFactory Canvas
            Color Matrix Paint Paint$Cap Paint$Style Rect]
           [android.text SpannableStringBuilder]
           [android.view
            GestureDetector GestureDetector$SimpleOnGestureListener
            ScaleGestureDetector ScaleGestureDetector$OnScaleGestureListener]
           [android.view Menu MenuInflater MenuItem]
           [android.view KeyEvent LayoutInflater MotionEvent
            SurfaceHolder SurfaceView
            View View$OnKeyListener View$OnTouchListener
            ViewGroup Window]
           [android.widget Button EditText ProgressBar
            TabHost TabHost$TabSpec TabHost$TabContentFactory TextView]
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
           )
  (:require [neko.init]
            [neko.compilation])
  (:use [clojure.pprint :only (with-pprint-dispatch
                                pprint
                                *print-right-margin*
                                code-dispatch)]
        [clojure.test :only (function?)]
        [clojure.math.numeric-tower :only (sqrt)]
        [org.turtle.geometry.utils]
        [neko.init]
        [android.clojure.util :only (defrecord*
                                      make-double-tap-handler
                                      make-ui-dimmer)]
        [android.clojure.graphic :only (color->paint
                                        ;; draw-grid
                                        with-saved-matrix)]))

(defn log
  ([msg] (android.util.Log/d "TurtleGeometry" msg))
  ([msg & args] (log (apply format msg args))))

(defmacro draw-with-page-transform [canvas-var transform-matrix & body]
  `(with-saved-matrix ~canvas-var
     (.concat ~canvas-var ~transform-matrix)
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

(defn ^Paint line-paint [color]
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


(defrecord* ActivityState [^SurfaceView draw-area
                           ^EditText program-source-editor
                           ^TextView error-output
                           ^EditText duration-entry
                           ^Menu activity-menu
                           ^TabHost activity-tab-host

                           ^DrawState draw-state
                           ^UserOptions user-options

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
                            nil))])



(defn text-input->int [^EditText input-field]
  (Integer/valueOf ^String
                   (.. input-field
                       (getText)
                       (toString))))

(defn report-error
  ([activity msg]
     (report-error activity msg true))
  ([^org.turtle.geometry.TurtleGraphics activity
     ^String msg
    switch-to-errors-tab]
     (.setText (error-output @activity) msg)
     (when switch-to-errors-tab
       (.setCurrentTabByTag (activity-tab-host @activity)
                            (.getString activity
                                        (resource :string
                                                  :errors_tab_label))))))

(defn get-animation-duration [^org.turtle.geometry.TurtleGraphics activity]
  (text-input->int (duration-entry @activity)))

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
         (let [code      (.getKeyCode event)]
           (if (contains? #{KeyEvent/KEYCODE_DPAD_DOWN
                            KeyEvent/KEYCODE_DPAD_LEFT
                            KeyEvent/KEYCODE_DPAD_RIGHT
                            KeyEvent/KEYCODE_DPAD_UP}
                          code)
             (do
               (update-activity-view-transform
                activity
                (condp = code
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
                            code
                            "UNKNOWN")
                       code)))
               true)
             false)))))))



(defn -onCreate [^org.turtle.geometry.TurtleGraphics this
                 ^android.os.Bundle bundle]
  (reset! *activity* this)
  (neko.init/init this :port 10001)
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
        error-output-view (.findViewById error-output-layout
                                         (resource :error_output))
        duration-entry-view (.findViewById editor-layout
                                           (resource :duration_entry))

        button-run ^Button (.findViewById editor-layout
                                          (resource :button_run))
        button-stop ^Button (.findViewById editor-layout
                                           (resource :button_stop))
        button-clear ^Button (.findViewById editor-layout
                                            (resource :button_clear))
        button-reindent ^Button (.findViewById editor-layout
                                               (resource :button_reindent))

        rotated-turtle-bitmap (rotate-right-90
                               (BitmapFactory/decodeResource
                                (.getResources this)
                                (resource :drawable :turtle_marker)))]
    (swap! (.state this)
           assoc
           :draw-area draw-area-view
           :program-source-editor source-editor-view
           :error-output error-output-view
           :duration-entry duration-entry-view
           :activity-menu nil
           :activity-tab-host tab-host

           :draw-state (DrawState. false nil nil)
           :user-options (UserOptions. true true)

           :intermediate-transform (make-identity-transform) ;; creates indentity
           :turtle-program-thread nil
           :draw-area-view-transform (make-identity-transform) ;; creates indentity
           :turtle-bitmap rotated-turtle-bitmap
           :turtle-state initial-turtle-state)

    (.. draw-area-view (getHolder) (addCallback this))

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
                                      draw-area-layout)))))))

    (.setOnClickListener
     button-run
     (reify android.view.View$OnClickListener
       (onClick [this unused-button]
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
                                                        :graphics_tab_label)))))
         (log "Clicked run button"))))

    (.setOnClickListener
     button-stop
     (reify android.view.View$OnClickListener
       (onClick [this unused-button]
         (let [turtle-thread (turtle-program-thread @activity)]
           (when turtle-thread
             (.interrupt turtle-thread)
             (swap! (.state activity) assoc :turtle-program-thread nil)
             (.join turtle-thread))))))

    (.setOnClickListener
     button-clear
     (reify android.view.View$OnClickListener
       (onClick [this unused-button]
         (swap! (.state activity) assoc-in
                [:turtle-state]
                initial-turtle-state)
         (clear-intermed-bitmap activity)
         (draw-scene activity))))

    ;; todo: move this "button renindent" into context menu for source editor
    (.setOnClickListener
     button-reindent
     (reify android.view.View$OnClickListener
       (onClick [this unused-button]
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
               (report-error activity (str "error while indenting:\n" e)))))))))

  (.setText (program-source-editor @this)
            "(doseq [r [1 1.5 2 2.5 3]]\n  (dotimes [_ 360]\n    (forward r)\n    (left 1)))"
            ;; "(forward 100)\n(left 90)\n(forward 100)\n"
            )
  ;; (.setOnTouchListener (program-source-editor @this)
  ;;
  ;;                      (make-double-tap-handler
  ;;                       (fn [] (log "Double tapped source editor twice"))))
  ;; (.setOnTouchListener (error-output @this)
  ;;                      (make-double-tap-handler
  ;;                       (fn [] (log "Double tapped error output twice"))))
  )

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
    (cond
     (= item-id (resource :id :menu_center_yourself))
      (do
        (swap! (.state this)
               assoc
               :draw-area-view-transform
               (make-identity-transform)
               :intermediate-transform
               (make-identity-transform))
        (redraw-indermed-bitmap this)
        (draw-scene this)
        true)
      (= item-id (resource :id :menu_zoom_in))
      (do
        (update-activity-view-transform this
                                        (make-scale-transform this
                                                              zoom-in-factor))
        true)
      (= item-id (resource :id :menu_zoom_out))
      (do
        (update-activity-view-transform this
                                        (make-scale-transform this
                                                              zoom-out-factor))
        true)
      :else
      (.superOnMenuItemSelected this feature-id item))))

(defn -onResume [^org.turtle.geometry.TurtleGraphics this]
  (.superOnResume this))

(defn -onPause [^org.turtle.geometry.TurtleGraphics this]
  (.superOnPause this))

(defn -onDestroy [^org.turtle.geometry.TurtleGraphics this]
  (.superOnDestroy this)
  (neko.init/deinit)
  (neko.compilation/clear-cache))

(defn -onBackPressed [^org.turtle.geometry.TurtleGraphics this]
  (log "onBackPressed")
  (let [tab-host (activity-tab-host @this)
        source-tab-tag (.getString this
                                   (resource :string :source_tab_label))]
    (if (= (.getCurrentTabTag tab-host)
           source-tab-tag)
      (.superOnBackPressed this)
      (.setCurrentTabByTag tab-host source-tab-tag))))

;; (defn -onSaveInstanceState [^org.turtle.geometry.TurtleGraphics this
;;                             ^android.os.Bundle bundle]
;;   )
;;
;; (defn -onRestoreInstanceState [^org.turtle.geometry.TurtleGraphics this
;;                                ^android.os.Bundle bundle]
;;   )


(defn clear-intermed-bitmap [^org.turtle.geometry.TurtleGraphics this]
  (.drawColor ^Canvas (get-in @this [:draw-state :draw-canvas]) Color/WHITE))

(defn -surfaceChanged [^org.turtle.geometry.TurtleGraphics this
                       ^SurfaceHolder holder
                       format
                       width
                       height]
  (log "surfaceChanged")
  (let [new-bitmap
        (if-let [interm-bitmap (.intermediate-bitmap (draw-state @this))]
          (Bitmap/createScaledBitmap interm-bitmap
                                     width
                                     height
                                     true)
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
    (.drawColor canvas Color/WHITE)
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
  (if (get-in @this [:draw-state :surface-available?])
    (if-let [surface-canvas ^Canvas (.. (draw-area @this)
                                        (getHolder)
                                        (lockCanvas))]
      (try
        (let [start-time (System/currentTimeMillis)]
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
          ;; draw on surface-canvas directly if you wish

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
          (log "frame took %s ms" (- (System/currentTimeMillis) start-time)))
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


(defn ^double deg->rad [^double x]
  (* x (/ Math/PI 180.0)))


(defn eval-turtle-program [program-text
                           ^org.turtle.geometry.TurtleGraphics activity]
  (letfn [(stop-if-interrupted []
            (when (.isInterrupted (Thread/currentThread))
              (throw (InterruptedException.))))

          (move [dist]
            (let [theta (get-in @activity [:turtle-state :angle])
                  pos   (get-in @activity [:turtle-state :position])
                  color (get-in @activity [:turtle-state :color])
                  delta-x (* dist (Math/cos (deg->rad theta)))
                  delta-y (* dist (Math/sin (deg->rad theta)))
                  delta [delta-x delta-y]
                  new-pos (map + delta pos)
                  [old-x old-y] pos
                  [new-x new-y] new-pos
                  draw-line? (not (get-in @activity [:turtle-state :pen-up?]))

                  anim-duration (get-animation-duration activity)]

              (stop-if-interrupted)
              (swap! (.state activity) assoc-in
                     [:turtle-state :prev-position]
                     pos)
              (let [start-time (System/currentTimeMillis)]
                (loop []
                  (stop-if-interrupted)
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
                  (stop-if-interrupted)
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
              (draw-scene activity)))

          (forward [dist]
            (stop-if-interrupted)
            (move dist))

          (backward [dist]
            (stop-if-interrupted)
            (move (- dist)))

          (left [delta]
            (stop-if-interrupted)
            (rotate (- delta)))

          (right [delta]
            (stop-if-interrupted)
            (rotate delta))

          (pen-up? []
            (stop-if-interrupted)
            (get-in @activity [:turtle-state :pen-up?]))

          (pen-up []
            (stop-if-interrupted)
            (swap! (.state activity) assoc-in
                   [:turtle-state :pen-up?]
                   true))

          (pen-down []
            (stop-if-interrupted)
            (swap! (.state activity) assoc-in
                   [:turtle-state :pen-up?]
                   false))

          (heading []
            (stop-if-interrupted)
            (get-in @activity [:turtle-state :angle]))

          (set-heading [new-heading]
            (stop-if-interrupted)
            (swap! (.state activity) assoc-in
                   [:turtle-state :angle]
                   new-heading))]
    (let [turtle-thread
          (Thread.
           (.getThreadGroup (Thread/currentThread))
           (fn []
             (try
               (let [sandbox-ns (create-ns 'org.turtle.geometry.TurtleSandbox)]
                 (intern sandbox-ns 'forward forward)
                 (intern sandbox-ns 'backward backward)
                 (intern sandbox-ns 'left left)
                 (intern sandbox-ns 'right right)
                 (intern sandbox-ns 'heading heading)
                 (intern sandbox-ns 'set-heading set-heading)

                 (intern sandbox-ns 'pen-up? pen-up?)
                 (intern sandbox-ns 'pen-up pen-up)
                 (intern sandbox-ns 'pen-down pen-down)
                 (intern sandbox-ns 'log log)

                 (binding [*ns* sandbox-ns]
                   (use '[clojure.core])
                   (use '[clojure.math.numeric-tower :only (sqrt)])

                   (load-string program-text)))
               (catch InterruptedException _
                 (.runOnUiThread
                  activity
                  (fn []
                    (report-error activity "Interrupted"))))
               (catch Exception e
                 (.runOnUiThread
                  activity
                  (fn []
                    (report-error activity (str "We've got an error here:\n" e)))))))
           "turtle program thread"
           (* 8 1024 1024))]
      (let [accumulated-lines (get-in @activity [:turtle-state :lines])]
        (swap! (.state activity) assoc-in
               [:turtle-state]
               (assoc initial-turtle-state :lines accumulated-lines)))
      (draw-scene activity)
      (.start turtle-thread)
      turtle-thread)))


