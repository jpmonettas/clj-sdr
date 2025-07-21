(ns clj-sdr.frames
  (:require [flow-storm.runtime.values :as rt-values]))

(defrecord SamplesFrame [frame-type samp-rate samples]
  rt-values/ScopeFrameP
  (frame-samp-rate [_] samp-rate)
  (frame-samples [_] samples))

(defn make-frame [frame-type samp-rate samples]
  (->SamplesFrame frame-type samp-rate samples))

(defn frame? [x]
  (instance? SamplesFrame x))
