(ns fs-data-windows
  (:require [flow-storm.runtime.values :as rt-values :refer [register-data-aspect-extractor]]
            [flow-storm.debugger.ui.data-windows.visualizers :refer [register-visualizer add-default-visualizer]]
            [flow-storm.debugger.ui.components :as ui]
            [radio-snake.frames :refer [frame?]])
  (:import [org.apache.commons.math3.complex Complex]
           [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.paint Color]
           [javafx.scene.control Label]
           [javafx.scene.text Font]))

(set! *warn-on-reflection* true)

(register-data-aspect-extractor
 {:id :complex
  :pred (fn [x _] (instance? Complex x))
  :extractor (fn iq-samples-frame-extractor [^Complex c _]
               {:real (.getReal c)
                :img (.getImaginary c)})})

(register-visualizer
 {:id :complex
  :pred (fn [val] (contains? (:flow-storm.runtime.values/kinds val) :complex))
  :on-create (fn complex-create [{:keys [real img]}]
               (try
                 (let [canvas-width 200
                       canvas-height 200
                       canvas (Canvas. canvas-width canvas-height)
                       ^GraphicsContext gc (.getGraphicsContext2D canvas)

                       mid-x (/ canvas-width 2)
                       mid-y (/ canvas-height 2)

                       vec-x (+ mid-x (* (/ canvas-width 2) real))
                       vec-y (- mid-y (* (/ canvas-width 2) img))]

                   (.clearRect  gc 0 0 canvas-width canvas-height)
                   (.setStroke  gc Color/GRAY)
                   (.strokeLine gc 0 mid-y canvas-width mid-y) ;; horiz
                   (.strokeLine gc mid-x 0 mid-x canvas-height) ;; vert
                   (.strokeOval gc 0 0 canvas-width canvas-height)
                   (.setStroke  gc Color/MAGENTA)
                   (.strokeLine gc mid-x mid-y vec-x vec-y) ;; complex vec
                   (.setFill  gc Color/BLUE)
                   (.fillOval gc (- vec-x 4) (- vec-y 4) 8 8)
                   {:fx/node (ui/border-pane
                              :top    (ui/label :text (format "Real: %s, Img: %s" (float real) (float img)))
                              :center canvas)})
                 (catch Exception e
                   (.printStackTrace e)
                   {:fx/node (Label. (.getMessage e))})))})

(add-default-visualizer (fn [val-data] (contains? (:flow-storm.runtime.values/kinds val-data) :complex)) :complex)

(extend-protocol rt-values/ScopeFrameSampleP
  Complex
  (sample-chan-1 [^Complex c] (.getReal c))
  (sample-chan-2 [^Complex c] (.getImaginary c))

  Double
  (sample-chan-1 [d] d)
  (sample-chan-2 [d] d))
