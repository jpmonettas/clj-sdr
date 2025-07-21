(ns clj-sdr.samplers.file-replay
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async]
            [clj-sdr.frames :refer [make-frame]])
  (:import [java.nio.file Files]
           [java.nio ByteBuffer ByteOrder]
           [org.apache.commons.math3.complex Complex]))

(set! *warn-on-reflection* true)

(def sample-size-bytes 8);; float32 (4 bytes) * 2 (real + imag)

(defn log [& msgs] (println "[file-replay-block]" (apply str msgs)))

(defn file-replay-block [file-path {:keys [frame-samples-size samp-rate]}]
  (try
    (let [in-ch (async/chan) ;; at 200k samples/s this should be able to buffer a couple of seconds of 4096 samples frame
          buffer (-> (io/file file-path)
                     .toPath
                     Files/readAllBytes
                     ByteBuffer/wrap)
          _ (.order buffer ByteOrder/LITTLE_ENDIAN)
          replay-thread (doto (Thread.
                               (fn []
                                 (try
                                   (while (not (Thread/interrupted))
                                     (loop [samples (transient [])]
                                       (if (= frame-samples-size (count samples))
                                         (let [frame (make-frame samp-rate
                                                                 (persistent! samples))]
                                           (async/>!! in-ch frame)
                                           (recur (transient [])))
                                         (when (>= (.remaining buffer) sample-size-bytes)
                                           (let [I (.getFloat buffer)
                                                 Q (.getFloat buffer)
                                                 c (Complex. I Q)]
                                             (recur (conj! samples c))))))
                                     (.rewind buffer))
                                   (log "Replaying done.")
                                   (catch Exception e
                                     (.printStackTrace e)))
                                 (log "File replay thread stopped")))
                          (.setName "File Replay")
                          (.start))]
      {:in-ch in-ch
       :stop-fn (fn []
                  (async/close! in-ch)
                  (.interrupt replay-thread))})
    (catch Exception e
      (.printStackTrace e))))
