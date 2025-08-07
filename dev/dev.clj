(ns dev
  (:require [fs-data-windows]
            [radio-snake.main :as main]
            [radio-snake.frames :as frames]
            [flow-storm.runtime.values :as rt-values])
  (:import [radio_snake.frames SamplesFrame]))

(extend-protocol rt-values/ScopeFrameP

  SamplesFrame
  (frame-samp-rate [fr] (:samp-rate fr))
  (frame-samples [fr] (:samples fr)))

(defmethod print-method SamplesFrame [^SamplesFrame sf ^java.io.Writer w]
  (.write w (str "Samp rate: " (rt-values/frame-samp-rate sf) ", Samples: " (count (rt-values/frame-samples sf)))))


(comment

  (do
    (let [{:keys [start-fn stop-fn]} (main/rf-snake-main
                                      {:mocked-samples "/home/jmonetta/my-projects/radio-snake/gnu_radio/remote_200k.samples"
                                       :scopes #{#_:frame-source
                                                 #_:am-demod
                                                 #_:burst-splitter
                                                 #_:normalizer}})]
      (def start start-fn)
      (def stop stop-fn ))

    (start))

  (do
    (let [{:keys [start-fn stop-fn]} (main/gnu-radio)]
      (def start start-fn)
      (def stop stop-fn ))

    (start))

  (stop)
  )

(comment
  (require '[clj-async-profiler.core :as prof])
  (prof/serve-ui 8081)

  (async/pipeline 1 gr-out-ch (map identity) gr-in-ch)
  (async/pipeline 1 gr-out-ch (map (fn [frame]
                                     (into [] (map (fn [^Complex s]
                                                     (.multiply s 2.0)))
                                           frame)))
                  gr-in-ch)

  (require '[clojure.java.io :as io])

  (io/input-stream "/home/jmonetta/my-projects/radio-snake/gnu_radio/remote_200k.samples")
  (import '[java.nio.file Files])
  (import '[java.nio ByteBuffer ByteOrder])

  (def bb (-> (io/file "/home/jmonetta/my-projects/radio-snake/gnu_radio/remote_200k.samples")
              .toPath
              Files/readAllBytes
              ByteBuffer/wrap))
  (.order bb ByteOrder/LITTLE_ENDIAN)

  (frames/make-frame)

  )
