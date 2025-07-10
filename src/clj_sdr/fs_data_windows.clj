(ns clj-sdr.fs-data-windows
  (:require [flow-storm.runtime.values :refer [register-data-aspect-extractor]]
            [flow-storm.debugger.ui.data-windows.visualizers :refer [register-visualizer add-default-visualizer]]
            [clj-sdr.types :refer [timed-iq-sample? sample-timestamp sample-complex]])
  (:import [org.apache.commons.math3.complex Complex]
           [javafx.scene.canvas Canvas GraphicsContext]
           [clj_sdr.types TimedIQSample]
           [javafx.scene.paint Color]
           [javafx.scene.control Label]
           [javafx.scene.text Font]))

(set! *warn-on-reflection* true)

(register-data-aspect-extractor
 {:id :iq-samples-frame
  :pred (fn [x _] (and (vector? x)
                       (-> x first timed-iq-sample?)))
  :extractor (fn [v _]
               {:iq-samples/frame (mapv (fn [^TimedIQSample tiqs]
                                          (let [^Complex c (sample-complex tiqs)]
                                            {:I (.getReal c)
                                             :Q (.getImaginary c)
                                             :ts (sample-timestamp tiqs)}))
                                        v)})})

(register-visualizer
 {:id :iq-samples-frame
  :pred (fn [val] (contains? (:flow-storm.runtime.values/kinds val) :iq-samples-frame))
  :on-create (fn iq-samples-frame-create [{:keys [iq-samples/frame]}]
               (try
                 (let [top-bottom-margins 25
                       samples-cnt (count frame)
                       canvas-width 400
                       canvas-height 200
                       canvas (Canvas. canvas-width canvas-height)
                       ^GraphicsContext gc (.getGraphicsContext2D canvas)
                       x-step (double (/ canvas-width samples-cnt))
                       mid-y (/ canvas-height 2)
                       mins (->> (into [] (mapcat (juxt :I :Q)) frame) (apply min))
                       maxs (->> (into [] (mapcat (juxt :I :Q)) frame) (apply max))
                       sample-range (* 2 (max (Math/abs ^double maxs) (Math/abs ^double mins)))
                       v-scale (/ (- canvas-height (* 2 top-bottom-margins))
                                  (if (zero? sample-range) 1 sample-range))
                       _ (.setFont gc (Font. "Arial" 20))
                       _ (.setFill gc Color/MAGENTA)]

                   (loop [i 0
                          x 0.0]
                     (when (< i samples-cnt)
                       (let [{:keys [I Q]} (get frame i)
                             Iy (* v-scale I)
                             Qy (* v-scale Q)]
                         (.setFill  gc Color/BLUE)
                         (.fillOval ^GraphicsContext gc x (- mid-y Iy) 2 2)
                         (.setFill  gc Color/GREEN)
                         (.fillOval ^GraphicsContext gc x (- mid-y Qy) 2 2)
                         (recur (inc i)
                                (+ x x-step)))))

                   {:fx/node canvas})
                 (catch Exception e
                   (.printStackTrace e)
                   {:fx/node (Label. (.getMessage e))})))})

(add-default-visualizer (fn [val-data] (contains? (:flow-storm.runtime.values/kinds val-data) :iq-samples-frame)) :iq-samples-frame)
