(ns clj-sdr.main
  (:require [clojure.core.async.flow :as flow]
            [clojure.core.async :as async]
            [clj-sdr.gnu-radio-uds :refer [gnuradio-uds-block]]
            [clj-sdr.types :refer [sample-complex sample-amplitude sample-timestamp]]
            clj-sdr.fs-data-windows
            [clojure.string :as str])
  (:import [org.apache.commons.math3.complex Complex]))


(set! *warn-on-reflection* true)

(def frame-samples-size 4096)

(def stop-all nil)
(def gr-in-ch nil)
(def gr-out-ch nil)

(defn trim-burst-packet [packet zero-threshold]
  (let [last-activity-idx (loop [i (dec (count packet))]
                            (if (and (pos? i)
                                     (< (.abs ^Complex (sample-complex (get packet i)))
                                        zero-threshold))
                              (recur (dec i))
                              i))]
    (subvec packet 0 last-activity-idx)))

(defn burst-splitter
  ([] {:outs {:burst-packets "A vector of samples containing a burst packet"}})
  ([_] {:last-activity-timestamp nil
        :last-sample-timestamp nil
        :curr-burst-packet (transient [])
        :burst-packets []
        ::flow/in-ports  {:samples-in gr-in-ch}})
  ([state _] state)
  ([state _ch-id samples-frame]
   (try
     (let [amplitude-level-threshold 0.05
           activity-nanos-threshold 7e6 ;; 10ms between bursts, so lets separate bursts when more than 7ms low
           {:keys [burst-packets] :as state'}
           (reduce (fn [{:keys [curr-burst-packet last-activity-timestamp] :as st} sample]
                     (let [samp-ts (sample-timestamp sample)
                           amplitude (.abs ^Complex (sample-complex sample))]

                       (cond
                         (> amplitude amplitude-level-threshold)
                         (-> st
                             (assoc :last-activity-timestamp samp-ts)
                             (update :curr-burst-packet conj! sample))

                         (and last-activity-timestamp
                              (< (- samp-ts last-activity-timestamp) activity-nanos-threshold))
                         (-> st
                             (update :curr-burst-packet conj! sample))

                         (pos? (count curr-burst-packet))
                         (-> st
                             (update :burst-packets conj (trim-burst-packet (persistent! curr-burst-packet)
                                                                            amplitude-level-threshold))
                             (assoc :curr-burst-packet (transient [])))

                         :else
                         st)
                       ))
                   state
                   samples-frame)]
       (if (seq burst-packets)
         [(assoc state' :burst-packets []) [[:burst-packets burst-packets]]]
         [state' []]))
     (catch Exception e
       (.printStackTrace e)))))

(defn ask-bit-decoder
  ([] {:ins  {:samples-frame "A frame of samples"}
       :outs {:decoded-packets "A string of decoded Amplitude Shift Keying for the samples frame"}})
  ([_] nil)
  ([state _] state)
  ([state _ch-id samples-frame]
   (try
     (let [samples-per-symb 80 ;; must be even
           symb-middle-sample (/ samples-per-symb 2)
           signal-height 0.1
           {:keys [decoded-packet]}
           (reduce (fn [{:keys [samp-cnt decoded-packet] :as acc} sample]
                     (let [sample-level (if (< (sample-amplitude sample) (/ signal-height 2))
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
                   samples-frame)]
       [state [[:decoded-packets [decoded-packet]]]])

     (catch Exception e
       (.printStackTrace e)))))

(defn gnu-radio-sender
  ([] {:ins {:samples-frame "A vector of TimedIQSamples samples"}})
  ([_] {::flow/out-ports {:samples-out-ch gr-out-ch}})
  ([state _] state)
  ([state _ch-id samples-frame]
   [state [[:samples-out-ch [samples-frame]]]]))

(defn printer
  ([] {:ins {:thing "Anything"}})
  ([_] {})
  ([state _] state)
  ([_state _ch-id thing]
   (println "->" (pr-str thing))
   nil))

(defn -main [& _args]
  (let [{:keys [in-ch out-ch stop-fn]} (gnuradio-uds-block "/tmp/clj_sdr.sock"
                                                           {:frame-samples-size frame-samples-size})
        system-graph (flow/create-flow
                      {:procs
                       {:burst-splitter {:proc (flow/process burst-splitter)}
                        :ask-bit-decoder {:proc (flow/process ask-bit-decoder)}
                        :gnu-radio-sender {:proc (flow/process gnu-radio-sender)}
                        :printer {:proc (flow/process printer)}}

                       :conns [[[:burst-splitter :burst-packets] [:ask-bit-decoder :samples-frame]]
                               [[:burst-splitter :burst-packets] [:gnu-radio-sender :samples-frame]]
                               [[:ask-bit-decoder :decoded-packets] [:printer :thing]]]})]
    (alter-var-root #'stop-all (constantly stop-fn))
    (alter-var-root #'gr-in-ch (constantly in-ch))
    (alter-var-root #'gr-out-ch (constantly out-ch))

    #_(async/pipeline 1 gr-out-ch (map identity) gr-in-ch)
    (flow/start  system-graph)
    (flow/resume system-graph)

))

(comment
  (require '[clj-async-profiler.core :as prof])
  (prof/serve-ui 8081)

  (async/pipeline 1 gr-out-ch (map identity) gr-in-ch)
  (async/pipeline 1 gr-out-ch (map (fn [frame]
                                     (into [] (map (fn [^Complex s]
                                                     (.multiply s 2.0)))
                                           frame)))
                  gr-in-ch)
  (-main)
  (stop-all)
  )
