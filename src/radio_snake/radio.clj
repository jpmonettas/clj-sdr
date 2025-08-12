(ns radio-snake.radio
  (:require [radio-snake.sdr-utils :as sdr-utils]
            [clojure.core.async.flow :as flow]
            [clojure.core.async :as async]
            [radio-snake.frames :as frames]
            [radio-snake.samplers.hackrf :as hackrf]
            [radio-snake.radio :as radio]
            [radio-snake.samplers.gnu-radio-uds :refer [gnuradio-uds-block]])
  (:import [clojure.lang PersistentVector]))

(set! *warn-on-reflection* true)

(defn frame-source
  ([] {:outs {:samples-frames-out "SamplesFrame objects containing IQ samples readed from the frames-ch input chanel"}})
  ([{:keys [frames-ch]}] {::flow/in-ports  {:samples-frames-in frames-ch}})
  ([state _] state)
  ([state _ch-id samples-frame]
   [state [[:samples-frames-out [samples-frame]]]]))

(defn fir-filter
  ([] {:ins {:samples-frames-in ["SamplesFrame objects containing IQ samples"]}
       :outs {:samples-frames-out "SamplesFrame objects containing IQ samples with the signal filtered"}})
  ([_] {})
  ([state _] state)
  ([state _ch-id {:keys [samples samp-rate]}]
   (let [filtered-samples (sdr-utils/fir-filter sdr-utils/low-pass-200k-coeffs samples)]
     [state [[:samples-frames-out [(frames/make-frame samp-rate filtered-samples)]]]])))

(defn downsampler
  ([] {:ins {:samples-frames-in ["SamplesFrame objects containing IQ samples"]}
       :outs {:samples-frames-out "Downsampled SamplesFrame objects"}})
  ([{:keys [src-samp-rate dst-samp-rate]}] {:dst-samp-rate dst-samp-rate
                                            :src-samp-rate src-samp-rate})
  ([state _] state)
  ([{:keys [src-samp-rate dst-samp-rate] :as state} _ch-id {:keys [samples]}]
   (let [skip (quot src-samp-rate dst-samp-rate)
         samples-cnt (count samples)
         downsampled-samples (loop [ss (transient [])
                                    i 0]
                               (if (< i samples-cnt)
                                 (recur (conj! ss (.get ^PersistentVector samples i))
                                        (long (unchecked-add i skip)))
                                 (persistent! ss)))]
     [state [[:samples-frames-out [(frames/make-frame dst-samp-rate downsampled-samples)]]]])))

(defn am-demod
  ([] {:ins {:samples-frames-in ["SamplesFrame objects containing the AM modulated IQ signal"]}
       :outs {:samples-frames-out "SamplesFrame objects containing an amiplitude demodulated signal"}})
  ([_] {})
  ([state _] state)
  ([state _ch-id {:keys [samp-rate] :as frame}]
   (let [demod-samples (into [] (map (fn [s] (frames/amplitude s))) (:samples frame))]
     [state [[:samples-frames-out [(frames/make-frame samp-rate demod-samples)]]]])))

(defn normalizer
  ([] {:ins {:samples-frames-in ["SamplesFrame objects containing an amiplitude demodulated signal"]}
       :outs {:samples-frames-out "SamplesFrame objects containing the signal normalized to 0-1 range"}})
  ([_] {})
  ([state _] state)
  ([state _ch-id {:keys [samples samp-rate]}]
   (let [maxs (apply max samples)
         mins (apply min samples)
         normalize (fn [s] (/ (- s mins) ;; lerp to 0..1
                              (- maxs mins)))
         norm-samples (into [] (map normalize) samples)]
     [state [[:samples-frames-out [(frames/make-frame samp-rate norm-samples)]]]])))

(defn trim-burst-samples [samples zero-threshold]
  (let [last-activity-idx (loop [i (dec (count samples))]
                            (if (and (pos? i)
                                     (< (frames/amplitude (get samples i))
                                        zero-threshold))
                              (recur (dec i))
                              i))]
    (subvec samples 0 last-activity-idx)))

(defn burst-splitter
  ([] {:ins {:samples-frames-in "SamplesFrame objects"}
       :outs {:samples-frames-out "SamplesFrame objects of signal bursts"}})
  ([_] {:last-activity-nanos nil
        :last-sample-nanos 0
        :curr-burst-samples (transient [])
        :burst-frames []})
  ([state _] state)
  ([state _ch-id {:keys [samples samp-rate]}]
   (let [nanos-per-sample (/ 1e9 samp-rate)
         amplitude-level-threshold 0.07
         activity-nanos-threshold 7e6 ;; 10ms between bursts, so lets separate bursts when more than 7ms low
         {:keys [burst-frames] :as state'}
         (reduce (fn [{:keys [curr-burst-samples last-sample-nanos last-activity-nanos] :as st} sample]
                   (let [amplitude (frames/amplitude sample)
                         sample-nanos (+ last-sample-nanos nanos-per-sample)]

                     (cond
                       ;; wave is high
                       (> amplitude amplitude-level-threshold)
                       (-> st
                           (assoc :last-activity-nanos sample-nanos)
                           (assoc :last-sample-nanos sample-nanos)
                           (update :curr-burst-samples conj! sample))

                       ;; wave is low but still on burst threshold
                       (and last-activity-nanos
                            (< (- sample-nanos last-activity-nanos) activity-nanos-threshold))
                       (-> st
                           (update :curr-burst-samples conj! sample)
                           (assoc :last-sample-nanos sample-nanos))

                       ;; if we reach here and we have a burst with some samples, emit it
                       (pos? (count curr-burst-samples))
                       (-> st
                           (update :burst-frames conj (frames/make-frame samp-rate
                                                                  (trim-burst-samples (persistent! curr-burst-samples)
                                                                                          amplitude-level-threshold)))
                           (assoc :curr-burst-samples (transient []))
                           (assoc :last-activity-nanos nil)
                           (assoc :last-sample-nanos 0))

                       ;; this should be long low waves
                       :else
                       st)
                     ))
                 state
                 samples)]
     (if (seq burst-frames)
       [(assoc state' :burst-frames []) [[:samples-frames-out burst-frames]]]
       [state' []]))))

(defn ask-bit-decoder
  ([] {:ins  {:samples-frames-in "SamplesFrame objects of demodulated signal bursts"}
       :outs {:bin-strings-out "Strings of decoded ASK (Amplitude Shift Keying) for the frame"}})
  ([_] nil)
  ([state _] state)
  ([state _ch-id samples-frame]
   (let [samples-per-symb 92 ;; determined by trial error + a starting point from URH
         symb-middle-sample (/ samples-per-symb 2)
         signal-height 0.5
         {:keys [decoded-packet]}
         (reduce (fn [{:keys [samp-cnt decoded-packet] :as acc} sample]
                   (let [sample-level (if (< sample (/ signal-height 2))
                                        0
                                        1)]
                     (if (and (nil? decoded-packet)
                              (= sample-level :low))
                       acc


                       (cond-> acc
                         (= samp-cnt symb-middle-sample) (assoc :decoded-packet (str decoded-packet sample-level))
                         true (assoc :samp-cnt (mod (inc samp-cnt) samples-per-symb))))))
                 {:samp-cnt 0
                  :decoded-packet nil}
                 (:samples samples-frame))
         ]
     [state {:bin-strings-out [decoded-packet]}])))

(defn remote-button-decoder
  ([] {:ins  {:bin-strings-in "Strings representing a binary reading"}
       :outs {:decoded-button-out "Keywords representing the pressed buttons"}})
  ([_] nil)
  ([state _] state)
  ([state _ch-id bin-str]
   (let [header-length 59
         tail-length 11]
     (if (and (<= (- (count bin-str) header-length tail-length) 63)
              (>= (count bin-str) (+ header-length tail-length 1)))
       (let [decoded-long (Long/parseUnsignedLong bin-str header-length (- (count bin-str) tail-length) 2)]
         (case decoded-long
           323010 [state [[:decoded-button-out [:button-a]]]]
           322842 [state [[:decoded-button-out [:button-b]]]]
           [state []]))
       [state []]))))

(defn gnu-radio-sender
  ([] {:ins {:samples-frames-in "SamplesFrame objects containing IQ samples"}})
  ([{:keys [grc-out-ch]}] {::flow/out-ports {:samples-frames-out grc-out-ch}})
  ([state _] state)
  ([state _ch-id samples-frame]
   [state [[:samples-frames-out [samples-frame]]]]))

(defn gnu-radio []
  (let [hrf-samp-rate 2e6

        {_grc-in-ch :in-ch grc-out-ch :out-ch grc-stop :stop-fn}
        (gnuradio-uds-block "/tmp/clj_sdr.sock"
                            {:frame-samples-size hackrf/frame-size
                             :samp-rate hrf-samp-rate})

        {hackrf-in-ch :in-ch, hackrf-stop :stop-fn, hackrf-start :start-fn}
        (hackrf/hackrf-block {:freq-hz 629700000
                              :samp-rate hrf-samp-rate
                              :lna-gain 24
                              :vga-gain 20})]

    (async/pipeline 1
                    grc-out-ch
                    (map (fn [frame]
                           (update frame :samples #(sdr-utils/fir-filter sdr-utils/low-pass-200k-coeffs %))))
                    hackrf-in-ch)

    {:stop-fn (fn []
                (when hackrf-stop (hackrf-stop))
                (when grc-stop (grc-stop)))
     :start-fn (fn []
                 (hackrf-start))}))
