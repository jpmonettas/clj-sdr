(ns clj-sdr.main
  (:import [java.net Socket SocketAddress StandardProtocolFamily]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.channels ServerSocketChannel SocketChannel]
           [java.nio.file Files Paths]
           [java.time Instant Duration]
           [java.lang System]
           [java.net UnixDomainSocketAddress]
           [org.apache.commons.math3.complex Complex]))


(set! *warn-on-reflection* true)

(defn start-uds-socket-server [socket-path {:keys [on-in-conn on-out-conn]}]
  (println "Creating UDS socket at " socket-path)
  (let [sock-path (Paths/get socket-path (make-array String 0))
        ssch (ServerSocketChannel/open StandardProtocolFamily/UNIX)
        _ (Files/deleteIfExists sock-path)
        _ (.bind ssch (UnixDomainSocketAddress/of sock-path))
        server-thread (doto (Thread.
                             (fn []
                               (try
                                 (while (not (Thread/interrupted))
                                   (println "Waiting for connections ...")
                                   (let [ch (.accept ssch)
                                         first-byte-buffer (ByteBuffer/allocateDirect 1)
                                         read-bytes (.read ch first-byte-buffer)]
                                     (if (= read-bytes 1)
                                       (case (.get first-byte-buffer 0)
                                         1 (on-in-conn ch)
                                         2 (on-out-conn ch))
                                       (println "Error reading first byte"))))
                                 (catch Exception e
                                   (.printStackTrace e)))
                               (println "UDS conn thread stopped")))
                        (.start))
        stop-server (fn []
                      (.close ssch)
                      (.interrupt server-thread))]
    stop-server))

(def sample-size-bytes 8) ;; float32 (4 bytes) * 2 (real + imag)
(def stop-reader-thread nil)
(def stop-server nil)
(def write-sample nil)

(defn process-sample [^Complex iq-sample]
  (when write-sample
    (write-sample (.getReal iq-sample) (.getImaginary iq-sample)))
  #_(println "@@@ GOT " iq-sample))

(defn on-in-conn [^SocketChannel ch]
  (println "Got an IN conn")
  (let [^ByteBuffer buffer (doto (ByteBuffer/allocateDirect 4096)
                             (.order ByteOrder/LITTLE_ENDIAN))
        dispatch-complex-samples (fn [^ByteBuffer buf]
                                   (loop []
                                     (when (>= (.remaining buf) sample-size-bytes)
                                       (let [real (.getFloat buf)
                                             imag (.getFloat buf)]
                                         (process-sample (Complex. real imag))
                                         (recur)))))
        reader-thread (Thread.
                       (fn []

                         (try
                           (while (not (Thread/interrupted))
                            (let [read-bytes (.read ch buffer)]
                              (when (pos? read-bytes)
                                (.flip buffer)
                                (dispatch-complex-samples buffer)
                                (.clear buffer))))
                           (catch Exception e
                             (.printStackTrace e)))
                         (println "Reader thread stopped.")))]
    (alter-var-root #'stop-reader-thread
                    (constantly (fn [] (.interrupt reader-thread))) )
    (.start reader-thread)))

(defn on-out-conn [^SocketChannel ch]
  (println "Got an OUT conn")
  (let [^ByteBuffer buffer (doto (ByteBuffer/allocateDirect 4096)
                             (.order ByteOrder/LITTLE_ENDIAN))
        write-buf-to-ch (fn []
                          (locking buffer
                            (.flip buffer)
                            (.write ch buffer)
                            (.clear buffer)))]
    (alter-var-root #'write-sample
                    (constantly
                     (fn [I Q]
                       (locking buffer
                         (if (>= (.remaining buffer) 2)
                           (do
                             (.putFloat buffer I)
                             (.putFloat buffer Q))
                           (do
                             (write-buf-to-ch)
                             (.putFloat buffer I)
                             (.putFloat buffer Q)))))))))

(defn stop-all []
  (stop-server)
  (when stop-reader-thread
    (stop-reader-thread))
  (alter-var-root #'write-sample (constantly nil)))

(defn -main [& args]
  (let [stop-fn (start-uds-socket-server "/tmp/clj_sdr.sock"
                                         {:on-in-conn on-in-conn
                                          :on-out-conn on-out-conn})]
    (alter-var-root #'stop-server (constantly stop-fn))))

(comment

  (-main)
  (stop-all)
  )
