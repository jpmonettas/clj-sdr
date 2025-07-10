(ns clj-sdr.gnu-radio-uds
  (:require [clojure.core.async :as async]
            [clj-sdr.types :refer [make-timed-iq-sample sample-complex]])
  (:import [java.net StandardProtocolFamily]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.channels ServerSocketChannel SocketChannel]
           [java.nio.file Files Paths]
           [java.lang System]
           [java.net UnixDomainSocketAddress]
           [clj_sdr.types TimedIQSample]
           [org.apache.commons.math3.complex Complex]))

(set! *warn-on-reflection* true)

(def sample-size-bytes 8);; float32 (4 bytes) * 2 (real + imag)

(defn log [& msgs] (println "[gnuradio-uds-block]" (apply str msgs)))

(defn- on-in-conn [^SocketChannel ch dst-ch {:keys [frame-samples-size]}]
  (log "Got an IN conn")
  (let [^ByteBuffer buffer (doto (ByteBuffer/allocateDirect (* 2 frame-samples-size))
                             (.order ByteOrder/LITTLE_ENDIAN))
        dispatch-complex-samples (fn dispatch-complex-samples [^ByteBuffer buf]
                                   (loop [samples (transient [])]
                                     (if (>= (.remaining buf) sample-size-bytes)
                                       (let [I (.getFloat buf)
                                             Q (.getFloat buf)]
                                         (recur (conj! samples (make-timed-iq-sample I Q (System/nanoTime)))))
                                       (async/>!! dst-ch (persistent! samples)))))]
    (doto (Thread.
           (fn connection-loop []
             (try
               (while
                   (let [read-bytes-cnt (.read ch buffer)]
                     (when (and (not (Thread/interrupted))
                                (not (= -1 read-bytes-cnt))) ;; -1 means the conexion was closed
                       (when (pos? read-bytes-cnt)
                         (.flip buffer)
                         (dispatch-complex-samples buffer)
                         (.clear buffer)))))
               (catch Exception e
                 (.printStackTrace e)))
             (log "Reader thread stopped.")))
      (.start))))

(defn- on-out-conn [^SocketChannel ch src-ch {:keys [frame-samples-size]}]
  (log "Got an OUT conn")
  (let [buff-size (* 2 frame-samples-size)
        ^ByteBuffer buffer (doto (ByteBuffer/allocateDirect buff-size)
                             (.order ByteOrder/LITTLE_ENDIAN))]
    (doto (Thread.
           (fn out-conn-writer []
             (loop []
               (when (not (Thread/interrupted))
                 (when-let [samples-frame (async/<!! src-ch)]
                   (doseq [^TimedIQSample t-iq-sample samples-frame]
                     (let [^Complex iq-sample (sample-complex t-iq-sample)
                           I (.getReal iq-sample)
                           Q (.getImaginary iq-sample)]
                       (.putFloat buffer I)
                       (.putFloat buffer Q)

                       (when (zero? (.remaining buffer))
                         (.flip buffer)
                         (.write ch buffer)
                         (.clear buffer))))
                   (recur))))))
      (.start))))

(defn gnuradio-uds-block [socket-path opts]
  (log "Creating UDS socket at " socket-path)
  (try
    (let [in-ch (async/chan)
          out-ch (async/chan)
          sock-path (Paths/get socket-path (make-array String 0))
          ssch (ServerSocketChannel/open StandardProtocolFamily/UNIX)
          _ (Files/deleteIfExists sock-path)
          _ (.bind ssch (UnixDomainSocketAddress/of sock-path))
          *threads (atom ())
          server-thread (doto (Thread.
                               (fn []
                                 (try
                                   (while (not (Thread/interrupted))
                                     (log "Waiting for connections ...")
                                     (let [ch (.accept ssch)
                                           first-byte-buffer (ByteBuffer/allocateDirect 1)
                                           read-bytes (.read ch first-byte-buffer)]
                                       (if (= read-bytes 1)
                                         (case (.get first-byte-buffer 0)
                                           1 (let [in-thread (on-in-conn ch in-ch opts)]
                                               (swap! *threads conj in-thread))
                                           2 (let [out-writer-thread (on-out-conn ch out-ch opts)]
                                               (swap! *threads conj out-writer-thread)))
                                         (log "Error reading first byte"))))
                                   (catch Exception e
                                     (.printStackTrace e)))
                                 (log "UDS conn thread stopped")))
                          (.start))]
      (swap! *threads conj server-thread)
      {:in-ch in-ch
       :out-ch out-ch
       :stop-fn (fn []
                  (doseq [^Thread th @*threads]
                    (.interrupt th))
                  (async/close! in-ch)
                  (async/close! out-ch)
                  (.close ssch)
                  (.interrupt server-thread))})
    (catch Exception e
      (.printStackTrace e))))
