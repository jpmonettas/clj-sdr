(ns dev
  (:require [fs-data-windows]
            [radio-snake.main :as main]))

(comment

  (main/rf-snake-main)
  (main/stop-all)

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

  )
