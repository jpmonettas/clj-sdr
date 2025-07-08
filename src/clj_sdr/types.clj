(ns clj-sdr.types
  (:import [org.apache.commons.math3.complex Complex]))

(set! *warn-on-reflection* true)

(defprotocol TimedIQSampleP
  (timed-iq-sample-complex [_])
  (timed-iq-sample-timestamp [_]))

(deftype TimedIQSample [iq-sample ^long timestamp]
  TimedIQSampleP
  (timed-iq-sample-complex [_] iq-sample)
  (timed-iq-sample-timestamp [_] timestamp))

(defn make-timed-iq-sample [I Q timestamp]
  (TimedIQSample. (Complex. I Q) timestamp))

(defn timed-iq-sample? [x]
  (instance? TimedIQSample x))
