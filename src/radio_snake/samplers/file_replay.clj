(ns radio-snake.samplers.file-replay
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async]
            [radio-snake.frames :refer [make-frame]])
  (:import [java.nio.file Files]
           [java.time Duration]
           [java.nio ByteBuffer ByteOrder]
           [org.apache.commons.math3.complex Complex]))

(set! *warn-on-reflection* true)

(def sample-size-bytes 8);; float32 (4 bytes) * 2 (real + imag)

(defn log [& msgs] (println "[file-replay-block]" (apply str msgs)))

(defn file-replay-block [file-path {:keys [frame-samples-size samp-rate loop?]}]
  (try
    (let [in-ch (async/chan) ;; at 200k samples/s this should be able to buffer a couple of seconds of 4096 samples frame
          buffer (-> (io/file file-path)
                     .toPath
                     Files/readAllBytes
                     ByteBuffer/wrap)
          _ (.order buffer ByteOrder/LITTLE_ENDIAN)
          nanos-per-sample (quot 1e9 samp-rate)
          file-samples (loop [samples (transient [])]
                         (if (>= (.remaining buffer) sample-size-bytes)
                           (let [I (.getFloat buffer)
                                 Q (.getFloat buffer)
                                 c (Complex. I Q)]
                             (recur (conj! samples c)))
                           (persistent! samples)))
          file-samples-cnt (count file-samples)
          _ (log (format "Loaded %d samples from %s. Replaying ..." (count file-samples) file-path))
          replay-thread (doto (Thread.
                               (fn samples-replay []
                                 (try
                                   (loop [samp-idx 0
                                          last-sample-nanos (System/nanoTime)
                                          frame-samples (transient [])]
                                     (if (= frame-samples-size (count frame-samples))
                                       ;; collected enough for a frame, make the frame and send it
                                       (let [frame (make-frame samp-rate
                                                               (persistent! frame-samples))]
                                         (async/>!! in-ch frame)

                                         (when-not (Thread/interrupted)
                                           (recur (inc samp-idx)
                                                  last-sample-nanos
                                                  (transient []))))

                                       ;; keep collecting samples for the current frame
                                       (if (< samp-idx file-samples-cnt)
                                         (let [next-sample (get file-samples samp-idx)
                                               now (System/nanoTime)
                                               delta-nanos (- now last-sample-nanos)
                                               sync-to-samp-rate-nanos (- nanos-per-sample delta-nanos)]
                                           (when (pos? delta-nanos)
                                             (Thread/sleep (Duration/ofNanos sync-to-samp-rate-nanos)))
                                           (recur (inc samp-idx)
                                                  now
                                                  (conj! frame-samples next-sample)))

                                         ;; else we reached the end of the file
                                         (when loop?
                                           (recur 0 (System/nanoTime) (transient []))))))
                                   (catch Exception e
                                     (.printStackTrace e)))
                                 (log "File replay thread stopped")))
                          (.setName "File Replay"))]
      {:in-ch in-ch
       :stop-fn (fn []
                  (async/close! in-ch)
                  (.interrupt replay-thread))
       :start-fn (fn []
                   (.start replay-thread))})
    (catch Exception e
      (.printStackTrace e))))
