(ns clj-sdr.types
  (:import [org.apache.commons.math3.complex Complex]))

(set! *warn-on-reflection* true)

(defmethod print-method Complex [^Complex c ^java.io.Writer w]
  (.write w ^String (format "(I)Real: %s, (Q)Img: %s"
                            (.getReal c)
                            (.getImaginary c))))

(defprotocol TimedSampleP
  (sample-timestamp [_]))

(defprotocol RealSampleP
  (sample-amplitude [_]))

(defprotocol IQSampleP
  (sample-complex [_]))

;;;;;;;;;;;;;;;;;;;
;; TimedIQSample ;;
;;;;;;;;;;;;;;;;;;;

(deftype TimedIQSample [iq-sample ^long timestamp]
  IQSampleP
  (sample-complex [_] iq-sample)

  RealSampleP
  (sample-amplitude [_] (.getReal ^Complex iq-sample))

  TimedSampleP
  (sample-timestamp [_] timestamp))

(defn make-timed-iq-sample [I Q timestamp]
  (TimedIQSample. (Complex. I Q) timestamp))

(defn timed-iq-sample? [x]
  (instance? TimedIQSample x))

(defmethod print-method TimedIQSample [^TimedIQSample sample ^java.io.Writer w]
  (let [^Complex c (sample-complex sample)]
    (.write w ^String (format "I: %s, Q: %s, TS: %sns"
                              (.getReal c)
                              (.getImaginary c)
                              (sample-timestamp sample)))))
