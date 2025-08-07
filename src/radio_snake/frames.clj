(ns radio-snake.frames
  (:require [flow-storm.runtime.values :as rt-values])
  (:import [org.apache.commons.math3.complex Complex]))

(defprotocol AmplitudeP
  (amplitude [_]))

(extend-protocol AmplitudeP
  Complex
  (amplitude [^Complex c] (.abs c))

  Double
  (amplitude [d] d)

  Float
  (amplitude [f] f))

(defrecord SamplesFrame [samp-rate samples]
  rt-values/ScopeFrameP
  (frame-samp-rate [_] samp-rate)
  (frame-samples [_] samples))

(defn make-frame
  ([] (make-frame 1 []))
  ([samp-rate samples]
   (->SamplesFrame samp-rate samples)))

(defn frame? [x]
  (instance? SamplesFrame x))

(defmethod print-method SamplesFrame [^SamplesFrame sf ^java.io.Writer w]
  (.write w (str "Samp rate: " (rt-values/frame-samp-rate sf) ", Samples: " (count (rt-values/frame-samples sf)))))
