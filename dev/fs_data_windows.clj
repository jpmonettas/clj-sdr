(ns fs-data-windows
  (:require [flow-storm.runtime.values :as rt-values :refer [register-data-aspect-extractor]]
            [flow-storm.debugger.ui.data-windows.visualizers :refer [register-visualizer add-default-visualizer]]
            [radio-snake.frames :refer [frame?]])
  (:import [org.apache.commons.math3.complex Complex]
           [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.paint Color]
           [javafx.scene.control Label]
           [javafx.scene.text Font]))

(set! *warn-on-reflection* true)

#_(register-data-aspect-extractor
 {:id :iq-samples-frame
  :pred (fn [x _] (frame? x))
  :extractor (fn iq-samples-frame-extractor [frame _]
               frame)})

#_(register-visualizer
 {:id :iq-samples-frame
  :pred (fn [val] (contains? (:flow-storm.runtime.values/kinds val) :iq-samples-frame))
  :on-create (fn iq-samples-frame-create [{:keys [frame-type samples]}]
               (try
                 (let [top-bottom-margins 25
                       samples-cnt (count samples)
                       canvas-width 600
                       canvas-height 200
                       canvas (Canvas. canvas-width canvas-height)
                       ^GraphicsContext gc (.getGraphicsContext2D canvas)
                       x-step (double (/ canvas-width samples-cnt))
                       mid-y (/ canvas-height 2)
                       sample-parts (fn [s]
                                      (case frame-type
                                        :complex [(Complex/.getReal s) (Complex/.getImaginary s)]
                                        :real    [s 0]))
                       mins (->> (into [] (mapcat sample-parts) samples) (apply min))
                       _ (tap> [:mins mins])
                       maxs (->> (into [] (mapcat sample-parts) samples) (apply max))
                       _ (tap> [:maxs maxs])
                       sample-range (* 2 (max (Math/abs ^double maxs) (Math/abs ^double mins)))
                       v-scale (/ (- canvas-height (* 2 top-bottom-margins))
                                  (if (zero? sample-range) 1 sample-range))
                       _ (.setFont gc (Font. "Arial" 20))
                       _ (.setFill gc Color/MAGENTA)]

                   (loop [i 0
                          x 0.0]
                     (when (< i (dec samples-cnt))
                       (let [x-next (+ x x-step)
                             [I Q]  (sample-parts (get samples i))
                             [I-next Q-next] (sample-parts (get samples (inc i)))
                             I-y (- mid-y (* v-scale I))
                             Q-y (- mid-y (* v-scale Q))
                             I-next-y (- mid-y (* v-scale I-next))
                             Q-next-y (- mid-y (* v-scale Q-next))]
                         (.setStroke  gc Color/BLUE)
                         (.strokeLine gc x I-y x-next I-next-y)
                         (.setStroke gc Color/GREEN)
                         (.strokeLine gc x Q-y x-next Q-next-y)
                         (recur (inc i)
                                x-next))))

                   {:fx/node canvas})
                 (catch Exception e
                   (.printStackTrace e)
                   {:fx/node (Label. (.getMessage e))})))})

#_(add-default-visualizer (fn [val-data] (contains? (:flow-storm.runtime.values/kinds val-data) :iq-samples-frame)) :iq-samples-frame)
#_(add-default-visualizer (fn [val-data] (contains? (:flow-storm.runtime.values/kinds val-data) :iq-samples-frame)) :iq-samples-frame)

(extend-protocol rt-values/ScopeFrameSampleP
  Complex
  (sample-chan-1 [^Complex c] (.getReal c))
  (sample-chan-2 [^Complex c] (.getImaginary c))

  Double
  (sample-chan-1 [d] d)
  (sample-chan-2 [d] d))
