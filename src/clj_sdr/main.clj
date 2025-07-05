(ns clj-sdr.main
  (:require [clojure.core.async.flow :as flow]
            [clojure.core.async :as async]
            [clj-sdr.gnu-radio-uds :refer [gnuradio-uds-block]]))


(set! *warn-on-reflection* true)

(def frame-samples-size 4096)

;; (defn scaler
;;   ([] {:outs {:samples "A vector of Complex IQ samples"}})
;;   ([_] {::flow/in-ports {:samples-batch gnu-radio-samples-batch-ch}})
;;   ([state _] state)
;;   ([state ch-id samples-batch]
;;    [state (into [] (map (fn [^Complex iq-sample]
;;                           (.multiply iq-sample 2)))
;;                 samples-batch)]))

;; (defn gnu-radio-sender
;;   ([] {:ins {:samples "A vector of Complex IQ samples"}})
;;   ([_] {::flow/out-ports {:samples-batch gnu-radio-samples-batch-ch}})
;;   ([state _] state)
;;   ([state ch-id samples-batch]
;;    [state (into [] (map (fn [^Complex iq-sample]
;;                           (.multiply iq-sample 2)))
;;                 samples-batch)]))

;; (def system-graph
;;   (flow/create-flow
;;    {:procs
;;     {:scaler {:proc (flow/process scaler)}
;;      :gnu-radio-sender   {:proc (flow/process gnu-radio-sender)}}

;;     :conns
;;     [[[:scaler :samples]         [:gnu-radio-sender  :samples]]]}))

(def stop-all nil)
(def gr-in-ch nil)
(def gr-out-ch nil)

(defn -main [& args]
  (let [{:keys [in-ch out-ch stop-fn]} (gnuradio-uds-block "/tmp/clj_sdr.sock"
                                                           {:frame-samples-size frame-samples-size})]
    (alter-var-root #'stop-all (constantly stop-fn))
    (alter-var-root #'gr-in-ch (constantly in-ch))
    (alter-var-root #'gr-out-ch (constantly out-ch))
    (async/pipeline 1 gr-out-ch (map identity) gr-in-ch)))

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
