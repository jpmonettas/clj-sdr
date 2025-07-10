(ns clj-sdr.main
  (:require [clojure.core.async.flow :as flow]
            [clj-sdr.samplers.gnu-radio-uds :refer [gnuradio-uds-block]]
            [clj-sdr.samplers.file-replay :refer [file-replay-block]]
            [clj-sdr.frames :refer [make-frame]])
  (:import [org.apache.commons.math3.complex Complex]))


(set! *warn-on-reflection* true)

(def frame-samples-size 4096)

(def stop-all nil)
(def gr-in-ch nil)
(def gr-out-ch nil)

(defn frame-source
  ([] {:outs {:samples-frames-out "A samples frame"}})
  ([_] {::flow/in-ports  {:samples-frames-in gr-in-ch}})
  ([state _] state)
  ([state _ch-id samples-frame]
   [state [[:samples-frames-out [samples-frame]]]]))

(defn am-demod
  ([] {:ins {:samples-frames-in ["A vector of samples containing the AM modulated signal"]}
       :outs {:samples-frames-out "A vector of samples containing an amiplitude demodulated signal"}})
  ([_] {})
  ([state _] state)
  ([state _ch-id {:keys [frame/samples frame/samp-rate]}]
   (let [demod-samples (into [] (map (fn [^Complex s] (.abs s))) samples)]
     [state [[:samples-frames-out [(make-frame :real samp-rate demod-samples)]]]])))

(defn trim-burst-samples [samples zero-threshold]
  (let [last-activity-idx (loop [i (dec (count samples))]
                            (if (and (pos? i)
                                     (< (.abs ^Complex (get samples i))
                                        zero-threshold))
                              (recur (dec i))
                              i))]
    (subvec samples 0 last-activity-idx)))

(defn burst-splitter
  ([] {:ins {:samples-frames-in "A vector of samples"}
       :outs {:samples-frames-out "A vector of samples containing a burst packet"}})
  ([_] {:last-activity-nanos nil
        :last-sample-nanos 0
        :curr-burst-samples (transient [])
        :burst-frames []})
  ([state _] state)
  ([state _ch-id {:keys [frame/samples frame/samp-rate] :as frame}]
   (let [nanos-per-sample (/ 1e9 samp-rate)
         samples-amplitude (case (:frame/type frame)
                             :real    identity
                             :complex Complex/.abs)
         amplitude-level-threshold 0.1
         activity-nanos-threshold 7e6 ;; 10ms between bursts, so lets separate bursts when more than 7ms low
         {:keys [burst-frames] :as state'}
         (reduce (fn [{:keys [curr-burst-samples last-sample-nanos last-activity-nanos] :as st} sample]
                   (let [amplitude (samples-amplitude sample)
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
                           (update :burst-frames conj (make-frame (:frame/type frame)
                                                                  samp-rate
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
  ([] {:ins  {:samples-frame-in "A frame of samples"}
       :outs {:decoded-packets-out "A string of decoded Amplitude Shift Keying for the samples frame"}})
  ([_] nil)
  ([state _] state)
  ([state _ch-id samples-frame]
   (let [samples-per-symb 80 ;; must be even
         symb-middle-sample (/ samples-per-symb 2)
         signal-height 0.1
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
                 (:frame/samples samples-frame))]
     [state [[:decoded-packets-out [decoded-packet]]]])))

(defn gnu-radio-sender
  ([] {:ins {:samples-frame-in "A vector of TimedIQSamples samples"}})
  ([_] {::flow/out-ports {:samples-frames-out gr-out-ch}})
  ([state _] state)
  ([state _ch-id samples-frame]
   [state [[:samples-frames-out [samples-frame]]]]))

(defn printer
  ([] {:ins {:thing "Anything"}})
  ([_] {})
  ([state _] state)
  ([_state _ch-id thing]
   (println "->" (pr-str thing))
   nil))

(defn -main [& _args]
  (let [#_#_{:keys [in-ch out-ch stop-fn]} (gnuradio-uds-block "/tmp/clj_sdr.sock"
                                                           {:frame-samples-size frame-samples-size
                                                            :samp-rate 200e3})
        {:keys [in-ch stop-fn]} (file-replay-block "/home/jmonetta/my-projects/clj-sdr/gnu_radio/remote_200k.samples"
                                                   {:frame-samples-size frame-samples-size
                                                    :samp-rate 200e3})
        system-graph (flow/create-flow
                      {:procs
                       {:frame-source {:proc (flow/process frame-source)}
                        :am-demod {:proc (flow/process am-demod)}
                        :burst-splitter {:proc (flow/process burst-splitter)}
                        :ask-bit-decoder {:proc (flow/process ask-bit-decoder)}
                        ;; :gnu-radio-sender {:proc (flow/process gnu-radio-sender)}
                        :printer {:proc (flow/process printer)}}

                       :conns [[[:frame-source :samples-frames-out] [:burst-splitter :samples-frames-in] ]
                               [[:burst-splitter :samples-frames-out] [:am-demod :samples-frames-in]]
                               [[:am-demod :samples-frames-out] [:ask-bit-decoder :samples-frame-in]]
                               [[:ask-bit-decoder :decoded-packets-out] [:printer :thing]]

                           #_    [[:frame-source :samples-frames-out] [:gnu-radio-sender :samples-frame-in]]]})]
    (alter-var-root #'stop-all (constantly (fn []
                                             (stop-fn)
                                             (flow/stop system-graph))))

    (alter-var-root #'gr-in-ch (constantly in-ch))
    #_(alter-var-root #'gr-out-ch (constantly out-ch))

    #_(async/pipeline 1 gr-out-ch (map identity) gr-in-ch)
    (flow/start  system-graph)
    (flow/resume system-graph)

))
