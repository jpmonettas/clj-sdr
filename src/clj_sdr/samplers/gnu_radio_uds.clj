(ns clj-sdr.samplers.gnu-radio-uds
  (:require [clojure.core.async :as async]
            [clj-sdr.frames :refer [make-frame]])
  (:import [java.net StandardProtocolFamily]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.channels ServerSocketChannel SocketChannel]
           [java.nio.file Files Paths]
           [java.net UnixDomainSocketAddress]
           [org.apache.commons.math3.complex Complex]))

(set! *warn-on-reflection* true)

(def sample-size-bytes 8);; float32 (4 bytes) * 2 (real + imag)

(defn log [& msgs] (println "[gnuradio-uds-block]" (apply str msgs)))

(defn- on-in-conn [^SocketChannel in-ch dst-ch {:keys [frame-samples-size samp-rate _samp-type]}]
  (log "Got an IN conn")
  (let [^ByteBuffer buffer (doto (ByteBuffer/allocateDirect (* sample-size-bytes frame-samples-size))
                             (.order ByteOrder/LITTLE_ENDIAN))
        read-next-frame (fn []
                          (loop [samples (transient [])]
                            (let [read-bytes-cnt (.read in-ch buffer)]
                              (if (= -1 read-bytes-cnt)
                                (persistent! samples)

                                (do
                                  (.flip buffer)
                                  ;; transfer whatever is on the buffer to samples
                                  (let [ss (loop [ss samples]
                                             (if (< (count ss) frame-samples-size)
                                               (if (>= (.remaining buffer) sample-size-bytes)
                                                 (let [I (.getFloat buffer)
                                                       Q (.getFloat buffer)]
                                                   (recur (conj! ss (Complex. I Q))))

                                                 (do
                                                   (.clear buffer)
                                                   ss))
                                               ss))]
                                    (if (= (count ss) frame-samples-size)
                                      (persistent! ss)
                                      (recur ss))))))))]
    (doto (Thread.
           (fn []
             (try
               (while (not (Thread/interrupted))
                 (let [frame-samples (read-next-frame)]
                   (async/>!! dst-ch (make-frame :complex samp-rate frame-samples))))
               (catch Exception e
                 (.printStackTrace e)))
             (log "Reader thread stopped.")))
      (.setName "In Reader")
      (.start))))

(defn- on-out-conn [^SocketChannel ch src-ch {:keys [frame-samples-size]}]
  (log "Got an OUT conn")
  (let [^ByteBuffer buffer (doto (ByteBuffer/allocateDirect (* sample-size-bytes frame-samples-size))
                             (.order ByteOrder/LITTLE_ENDIAN))]
    (doto (Thread.
           (fn out-conn-writer []
             (while (not (Thread/interrupted))
               (when-let [{:keys [frame/samples]} (async/<!! src-ch)]
                 (doseq [^Complex sample samples]
                   (let [I (.getReal sample)
                         Q (.getImaginary sample)]
                     (.putFloat buffer I)
                     (.putFloat buffer Q)))
                 (.flip buffer)
                 (.write ch buffer)
                 (.clear buffer)))))
      (.setName "Out writer")
      (.start))))

(defn gnuradio-uds-block [socket-path opts]
  (log "Creating UDS socket at " socket-path)
  (try
    (let [in-ch (async/chan) ;; at 200k samples/s this should be able to buffer a couple of seconds of 4096 samples frame
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
                          (.setName "Connection Loop")
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
