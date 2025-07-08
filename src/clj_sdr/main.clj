(ns clj-sdr.main
  (:require [clojure.core.async.flow :as flow]
            [clojure.core.async :as async]
            [clj-sdr.gnu-radio-uds :refer [gnuradio-uds-block]]
            [clj-sdr.types :refer [make-timed-iq-sample timed-iq-sample-complex timed-iq-sample-timestamp]]
            clj-sdr.fs-data-windows)
  (:import [org.apache.commons.math3.complex Complex]
           [clj_sdr.types TimedIQSample]))


(set! *warn-on-reflection* true)

(def frame-samples-size 4096)

(def stop-all nil)
(def gr-in-ch nil)
(def gr-out-ch nil)

(defn burst-splitter
  ([] {:outs {:burst-packets "A vector of IQ samples containing a signal burst packet"}})
  ([_] {:last-activity-timestamp nil
        :last-sample-timestamp nil
        :curr-burst-packet (transient [])
        :burst-packets []
        ::flow/in-ports  {:samples-in gr-in-ch}
        ::flow/out-ports {:samples-out-ch gr-out-ch}})
  ([state _] state)
  ([state ch-id samples-frame]
   (try
     (let [amplitude-level-threshold 0.05
           activity-nanos-threshold 5e6 ;; 10ms between bursts, so lets separate bursts when more than 5ms low
           {:keys [packets] :as state'}
           (reduce (fn [{:keys [curr-burst-packet last-activity-timestamp] :as st} ^TimedIQSample t-iq-samp]
                     (let [^Complex iq-samp (timed-iq-sample-complex t-iq-samp)
                           iq-samp-ts (timed-iq-sample-timestamp t-iq-samp)
                           amplitude (.getReal iq-samp)]

                       (cond
                         (> amplitude amplitude-level-threshold)
                         (-> st
                             (assoc :last-activity-timestamp iq-samp-ts)
                             (update :curr-burst-packet conj! t-iq-samp))

                         (and last-activity-timestamp
                              (< (- iq-samp-ts last-activity-timestamp) activity-nanos-threshold))
                         (-> st
                             (update :curr-burst-packet conj! t-iq-samp))

                         (pos? (count curr-burst-packet))
                         (-> st
                             (update :burst-packets conj (persistent! curr-burst-packet))
                             (assoc :curr-burst-packet (transient [])))

                         :else
                         st)
                       ))
                   state
                   samples-frame)]
       [state' [[:samples-out-ch [samples-frame]]
                #_[:burst-packets packets]]])
     (catch Exception e
       (.printStackTrace e)
       [state [[:samples-out-ch [samples-frame]]]]))))

(defn burst-trimmer
  ([] {:ins  {:burst-packets "A vector of IQ samples containing burst packets"}
       :outs {:burst-packets "A vector of IQ samples containing a trimmed burst packets"}})
  ([_] {:last-activity-timestamp nil
        :last-sample-timestamp nil
        :curr-burst-packet (transient [])
        :burst-packets []
        ::flow/in-ports  {:samples-in gr-in-ch}
        ::flow/out-ports {:samples-out-ch gr-out-ch}})
  ([state _] state)
  ([state ch-id samples-frame]
   (try
     (let [amplitude-level-threshold 0.05
           activity-nanos-threshold 5e6 ;; 10ms between bursts, so lets separate bursts when more than 5ms low
           {:keys [packets] :as state'}
           (reduce (fn [{:keys [curr-burst-packet last-activity-timestamp] :as st} ^TimedIQSample t-iq-samp]
                     (let [^Complex iq-samp (timed-iq-sample-complex t-iq-samp)
                           iq-samp-ts (timed-iq-sample-timestamp t-iq-samp)
                           amplitude (.getReal iq-samp)]

                       (cond
                         (> amplitude amplitude-level-threshold)
                         (-> st
                             (assoc :last-activity-timestamp iq-samp-ts)
                             (update :curr-burst-packet conj! iq-samp))

                         (and last-activity-timestamp
                              (< (- iq-samp-ts last-activity-timestamp) activity-nanos-threshold))
                         (-> st
                             (update :curr-burst-packet conj! iq-samp))

                         (pos? (count curr-burst-packet))
                         (-> st
                             (update :burst-packets conj (persistent! curr-burst-packet))
                             (assoc :curr-burst-packet (transient [])))

                         :else
                         st)
                       ))
                   state
                   samples-frame)]
       [state' [[:samples-out-ch [samples-frame]]
                #_[:burst-packets packets]]])
     (catch Exception e
       (.printStackTrace e)
       [state [[:samples-out-ch [samples-frame]]]]))))

#_(defn bits-decoder
  ([] {:outs {:packets "A vector containing a contiguous stream of bits"}})
  ([_] {:last-transition-timestamp (System/nanoTime)
        :curr-level 0
        :bits []
        ::flow/in-ports  {:samples-in gr-in-ch}
        ::flow/out-ports {:samples-out-ch gr-out-ch}})
  ([state _] state)
  ([{:keys [last-transition-timestamp curr-level bits]} ch-id samples-frame]
   (loop [i 0
          prev-sample 0]
     (if )
     (let [^Complex s (get samples-frame i)
           r (.getReal s)
           ]

       ))
   [state (into [] (map (fn [^Complex iq-sample]
                          (.multiply iq-sample 2)))
                samples-batch)]))

;; (defn gnu-radio-sender
;;   ([] {:ins {:samples "A vector of Complex IQ samples"}})
;;   ([_] {::flow/out-ports {:samples-batch gnu-radio-samples-batch-ch}})
;;   ([state _] state)
;;   ([state ch-id samples-batch]
;;    [state (into [] (map (fn [^Complex iq-sample]
;;                           (.multiply iq-sample 2)))
;;                 samples-batch)]))

(defn -main [& args]
  (let [{:keys [in-ch out-ch stop-fn]} (gnuradio-uds-block "/tmp/clj_sdr.sock"
                                                           {:frame-samples-size frame-samples-size})
        system-graph (flow/create-flow
                      {:procs
                       {:burst-splitter {:proc (flow/process burst-splitter)}
                        #_#_:packet-printer  {:proc (flow/process packet-printer)}}

                       :conns []
                       #_[[[:decoder :packets] [:packet-printer :packets]]]})
        ]
    (alter-var-root #'stop-all (constantly stop-fn))
    (alter-var-root #'gr-in-ch (constantly in-ch))
    (alter-var-root #'gr-out-ch (constantly out-ch))

    (flow/start  system-graph)
    (flow/resume system-graph)

))

(comment
  (async/pipeline 1 gr-out-ch (map identity) gr-in-ch)
  (async/pipeline 1 gr-out-ch (map (fn [frame]
                                     (into [] (map (fn [^Complex s]
                                                     (.multiply s 2.0)))
                                           frame)))
                  gr-in-ch)
  (-main)
  (stop-all)
  )
