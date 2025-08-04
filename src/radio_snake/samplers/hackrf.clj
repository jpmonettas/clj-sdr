(ns radio-snake.samplers.hackrf
  (:require [clojure.core.async :as async]
            [radio-snake.frames :refer [make-frame]]
            [coffi.mem :as mem :refer [defalias]]
            [coffi.ffi :as ffi :refer [defcfn]])
  (:import [org.apache.commons.math3.complex Complex]
           [java.lang.foreign MemorySegment]))

(set! *warn-on-reflection* true)

(ffi/load-system-library "hackrf")

(defalias ::mem/uint64_t ::mem/long)
(defalias ::mem/uint32_t ::mem/int)

(defalias ::hackrf_transfer
  [::mem/struct
   [[:device        ::mem/pointer]
    [:buffer        ::mem/pointer]
    [:buffer_length ::mem/int]
    [:valid_length  ::mem/int]
    [:rx_ctx        ::mem/pointer]
    [:tx_ctx        ::mem/pointer]]])

(def hackrf-transfer-size (mem/size-of ::hackrf_transfer))

;; hackrf reads buffers of 256k bytes of samples
;; which are 131072 samples, and after downsampling we
;; get a tenth of that
(def frame-size (/ (* 256 1024) 2))

(def dst-ch (async/chan))
(def curr-samp-rate nil)

(defn dispatch-frame [^bytes bytes]
  (let [num-samples (/ (count bytes) 2)
        frame-samples (loop [i 0
                             samples (transient [])]
                        (if (< i num-samples)
                          (let [I (inc (/ (- (aget bytes (* 2 i))       128) 128.0))
                                Q (inc (/ (- (aget bytes (inc (* 2 i))) 128) 128.0))]
                            (recur (inc i)
                                   (conj! samples (Complex. I Q))))
                          (persistent! samples)))]
    (async/>!! dst-ch (make-frame curr-samp-rate frame-samples))))

(defn rx-callback [transfer-ptr]
  (with-open [stack (mem/confined-arena)]
    (let [hrf-transfer (mem/deserialize-from (mem/reinterpret transfer-ptr hackrf-transfer-size stack) ::hackrf_transfer)
          buffer-ptr (:buffer hrf-transfer)
          ^long valid-length (:valid_length hrf-transfer)
          buffer-seg (mem/reinterpret buffer-ptr valid-length stack)
          bytes (mem/read-bytes ^MemorySegment buffer-seg valid-length)]
      (dispatch-frame bytes)
      (int 0))))

#_:clj-kondo/ignore
(defcfn hackrf-init
  ""
  hackrf_init [] ::mem/int)

#_:clj-kondo/ignore
(defcfn hackrf-open
  ""
  hackrf_open [::mem/pointer] ::mem/int)

#_:clj-kondo/ignore
(defcfn hackrf-set-freq
  ""
  hackrf_set_freq [::mem/pointer ::mem/uint64_t] ::mem/int)

#_:clj-kondo/ignore
(defcfn hackrf-set-sample-rate
  ""
  hackrf_set_sample_rate [::mem/pointer ::mem/double] ::mem/int
  )

#_:clj-kondo/ignore
(defcfn hackrf-set-lna-gain
  ""
  hackrf_set_lna_gain [::mem/pointer ::mem/uint32_t] ::mem/int)

#_:clj-kondo/ignore
(defcfn hackrf-set-vga-gain
  ""
  hackrf_set_vga_gain [::mem/pointer ::mem/uint32_t] ::mem/int)

#_:clj-kondo/ignore
(defcfn hackrf-set-baseband-filter-bandwidth
  ""
  hackrf_set_baseband_filter_bandwidth [::mem/pointer ::mem/uint32_t] ::mem/int)

#_:clj-kondo/ignore
(defcfn hackrf-compute-baseband-filter-bw
  ""
  hackrf_compute_baseband_filter_bw [::mem/uint32_t] ::mem/uint32_t)

#_:clj-kondo/ignore
(defcfn hackrf-version-string-read
  ""
  hackrf_version_string_read [::mem/pointer ::mem/pointer ::mem/byte] ::mem/int)

#_:clj-kondo/ignore
(defcfn hackrf-start-rx
  ""
  hackrf_start_rx [::mem/pointer ::mem/pointer ::mem/pointer] ::mem/int)



#_:clj-kondo/ignore
(defcfn hackrf-stop-rx
  ""
  hackrf_stop_rx [::mem/pointer] ::mem/int)

#_:clj-kondo/ignore
(defcfn hackrf-close
  ""
  hackrf_close [::mem/pointer] ::mem/int)

#_:clj-kondo/ignore
(defcfn hackrf-exit
  ""
  hackrf_exit [] ::mem/int)

(defn hackrf-block [{:keys [freq-hz samp-rate lna-gain vga-gain]}]
  (hackrf-init)

  (let [device-buf (mem/alloc-instance ::mem/pointer)
        _ (hackrf-open device-buf)
        device (mem/read-address device-buf)
        bbf (hackrf-compute-baseband-filter-bw samp-rate)
        _ (hackrf-set-baseband-filter-bandwidth device bbf)
        _ (hackrf-set-freq device freq-hz)
        _ (hackrf-set-sample-rate device samp-rate)
        _ (hackrf-set-lna-gain device lna-gain)
        _ (hackrf-set-vga-gain device vga-gain)
        f-ptr (mem/serialize rx-callback
                             [::ffi/fn [::mem/pointer] ::mem/int]
                             (mem/global-arena))]
    (alter-var-root #'curr-samp-rate (constantly samp-rate))

    {:in-ch dst-ch
     :stop-fn (fn []
                (hackrf-stop-rx device)
                (hackrf-close device)
                (hackrf-exit))
     :start-fn (fn []
                 (hackrf-start-rx device f-ptr mem/null)
                 (println (format "Hackrf RX started. Freq %s Hz, Samp rate: %s Hz" freq-hz samp-rate)))}))
