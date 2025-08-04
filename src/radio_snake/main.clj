(ns radio-snake.main
  (:require [clojure.core.async.flow :as flow]
            [clojure.core.async :as async]
            [flow-storm.api :as fsa]
            [radio-snake.samplers.gnu-radio-uds :refer [gnuradio-uds-block]]
            [radio-snake.samplers.hackrf :as hackrf]
            [radio-snake.samplers.file-replay :as file-replay]
            [radio-snake.frames :refer [make-frame]]
            [radio-snake.frames :as frames]
            [radio-snake.sdr-utils :as sdr-utils])
  (:import [org.apache.commons.math3.complex Complex]
           [clojure.lang PersistentVector PersistentVector$TransientVector]
           [javafx.stage Stage]
           [javafx.scene Scene Node]
           [javafx.scene.layout Pane]
           [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.application Platform]
           [javafx.scene.paint Color]))


(set! *warn-on-reflection* true)

;; (def in-ch nil)
;; (def out-ch nil)

(defn frame-source
  ([] {:outs {:samples-frames-out "A samples frame"}})
  ([{:keys [frames-ch]}] {::flow/in-ports  {:samples-frames-in frames-ch}})
  ([state _] state)
  ([state _ch-id samples-frame]
   [state [[:samples-frames-out [samples-frame]]]]))

(defn fir-filter
  ([] {:ins {:samples-frames-in ["A vector of samples containing an IQ signal"]}
       :outs {:samples-frames-out "A vector of samples containing the filtered IQ signal"}})
  ([_] {})
  ([state _] state)
  ([state _ch-id {:keys [samples samp-rate]}]
   (let [filtered-samples (sdr-utils/fir-filter sdr-utils/low-pass-200k-coeffs samples)]
     [state [[:samples-frames-out [(make-frame samp-rate filtered-samples)]]]])))

(defn downsampler
  ([] {:ins {:samples-frames-in ["A vector of samples containing an IQ signal"]}
       :outs {:samples-frames-out "A vector of samples containing the downsampled IQ signal"}})
  ([{:keys [src-samp-rate dst-samp-rate]}] {:dst-samp-rate dst-samp-rate
                                            :src-samp-rate src-samp-rate})
  ([state _] state)
  ([{:keys [src-samp-rate dst-samp-rate] :as state} _ch-id {:keys [samples samp-rate]}]
   (let [skip (quot src-samp-rate dst-samp-rate)
         samples-cnt (count samples)
         downsampled-samples (loop [ss (transient [])
                                    i 0]
                               (if (< i samples-cnt)
                                 (recur (conj! ss (.get ^PersistentVector samples i))
                                        (long (unchecked-add i skip)))
                                 (persistent! ss)))]
     [state [[:samples-frames-out [(make-frame dst-samp-rate downsampled-samples)]]]])))

(defn am-demod
  ([] {:ins {:samples-frames-in ["A vector of samples containing the AM modulated signal"]}
       :outs {:samples-frames-out "A vector of samples containing an amiplitude demodulated signal"}})
  ([_] {})
  ([state _] state)
  ([state _ch-id {:keys [samples samp-rate]}]
   (let [demod-samples (into [] (map frames/amplitude) samples)]
     [state [[:samples-frames-out [(make-frame samp-rate demod-samples)]]]])))

(defn normalizer
  ([] {:ins {:samples-frames-in ["A vector double of samples "]}
       :outs {:samples-frames-out "A vector doubof samples normalized to 0-1"}})
  ([_] {})
  ([state _] state)
  ([state _ch-id {:keys [samples samp-rate]}]
   (let [maxs (apply max samples)
         mins (apply min samples)
         normalize (fn [s] (/ (- s mins) ;; lerp to 0..1
                              (- maxs mins)))
         norm-samples (into [] (map normalize) samples)]
     [state [[:samples-frames-out [(make-frame samp-rate norm-samples)]]]])))

(defn trim-burst-samples [samples zero-threshold]
  (let [last-activity-idx (loop [i (dec (count samples))]
                            (if (and (pos? i)
                                     (< (frames/amplitude (get samples i))
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
  ([state _ch-id {:keys [samples samp-rate] :as frame}]
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
                           (update :burst-frames conj (make-frame samp-rate
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
  ([] {:ins  {:samples-frames-in "A frame of samples"}
       :outs {:decoded-button-out "A string of decoded Amplitude Shift Keying for the samples frame"}})
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
         header-length 59
         tail-length 11]
     (if (and (<= (- (count decoded-packet) header-length tail-length) 63)
              (>= (count decoded-packet) (+ header-length tail-length 1)))
       (let [decoded-long (Long/parseUnsignedLong decoded-packet header-length (- (count decoded-packet) tail-length) 2)]
         (case decoded-long
           323010 [state [[:decoded-button-out [:button-a]]]]
           322842 [state [[:decoded-button-out [:button-b]]]]
           [state []]))
       [state []]))))

(defn gnu-radio-sender
  ([] {:ins {:samples-frames-in "A vector of TimedIQSamples samples"}})
  ([{:keys [grc-out-ch]}] {::flow/out-ports {:samples-frames-out grc-out-ch}})
  ([state _] state)
  ([state _ch-id samples-frame]
   [state [[:samples-frames-out [samples-frame]]]]))

(defn printer
  ([] {:ins {:thing "Anything"}})
  ([_] {})
  ([state _] state)
  ([_state _ch-id thing]
   (println thing)
   nil))

(defn world-renderer
  ([] {:ins {:snake-worlds-in ""}})
  ([{:keys [redraw-game-fn]}] {:redraw-game redraw-game-fn})
  ([state _] state)
  ([{:keys [redraw-game] :as state} _ch-id snake-world]
   (redraw-game snake-world)
   state))

(defn oscilloscope-sender
  ([] {:ins {:samples-frames-in "A samples frame"}})
  ([{:keys [scope-id]}] {:scope-id scope-id})
  ([{:keys [scope-id] :as state} transition]
   (when (= ::flow/resume transition)
     (fsa/data-window-push-val scope-id (frames/make-frame)))
   state)
  ([{:keys [scope-id] :as state} _ch-id samples-frame]
   (fsa/data-window-val-update scope-id samples-frame)
   state))

(defn init-toolkit []
  (let [p (promise)]
    (try
      (Platform/startup (fn [] (deliver p true)))
      (catch Exception _ (deliver p false)))
    (if @p
      (println "JavaFX toolkit initialized")
      (println "JavaFX toolkit already initialized"))))

(def window-size 1000)
(def cell-size 20)
(def world-size (/ window-size cell-size))

(defn redraw-game-state [^GraphicsContext gc {:keys [food snake]}]
  ;; draw snake
  (.setFill gc Color/GREEN)

  (doseq [[s-cell-x s-cell-y] snake]
    (let [x (* cell-size s-cell-x)
          y (* cell-size s-cell-y)]
      (.fillRect gc x y cell-size cell-size)))

  ;; draw food
  (when-let [[food-x food-y] food]
    (.setFill gc Color/RED)
    (let [x (* cell-size food-x)
          y (* cell-size food-y)]
      (.fillRect gc x y cell-size cell-size))))

(defn create-snake-window []
  (init-toolkit)
  (Platform/setImplicitExit false)
  (let [result (promise)]
    (Platform/runLater
     (fn []
       (try
         (let [stage (doto (Stage.)
                       (.setTitle "Radio Snake"))
               canvas (Canvas. window-size window-size)
               gc (.getGraphicsContext2D canvas)
               scene (Scene. (Pane. (into-array Node [canvas]))
                             window-size
                             window-size)]

           (.setScene stage scene)

           #_(.setOnCloseRequest stage (event-handler [_]))

           (-> stage .show)

           (deliver result
                    {:redraw-game (fn [new-state]
                                    (Platform/runLater
                                     (fn []
                                       (.clearRect gc 0 0 window-size window-size)
                                       (redraw-game-state gc new-state))))
                     :close-window (fn [] (Platform/runLater #(.close stage)))}))

         (catch Exception e
           (.printStackTrace e)))))
    @result))

(def initial-world
  {:snake [[20 20] [20 19]]
   :food  [10 10]
   :dir :up
   :last-button-press-nanos 0})

(defn check-collision [{:keys [snake] :as state}]
  (if (or
       ;; into itself
       (->> snake
            frequencies
            vals
            (apply max)
            (not= 1))
       ;; into wall
       (some (fn [[cx cy]]
               (not (and (<= 0 cx world-size)
                         (<= 0 cy world-size))))
             snake))
    initial-world
    state))

(defn maybe-add-food [{:keys [food snake] :as state}]
  (if food
    state

    (let [food [(rand-int world-size) (rand-int world-size)]]
      (if (contains? (into #{} snake) food)
        (maybe-add-food state) ;; try again if we generated food on a snake cell
        (assoc state :food food)))))

(defn update-snake [{:keys [dir food snake] :as state}]
  (let [[hx hy] (get snake (dec (count snake)))
        new-head (case dir
                   :up   [hx       (dec hy)]
                   :down [hx       (inc hy)]
                   :left [(dec hx)       hy]
                   :right[(inc hx)       hy])]
    (if (= new-head food)
      ;; hit food
      (-> state
           (update :snake conj new-head)
           (assoc :food nil))

      ;; else
      (-> state
          (update :snake conj new-head)
          (update :snake subvec 1)))))

(defn snake-speed [snake-len]
  (long (max 30 (+ (* -50 snake-len) 1050))))

(defn snake-runner
  ([] {:ins {:buttons-in ""}
       :outs {:snake-worlds-out ""
              :ticks-speed-out ""}})
  ([{:keys [ticks-ch ticks-speed-ch]}]
   (-> initial-world
       (assoc ::flow/in-ports {:ticks-in ticks-ch}
              ::flow/out-ports {:ticks-speed-out ticks-speed-ch})))
  ([state _] state)
  ([{:keys [last-button-press-nanos] :as state} ch-id msg]
   (let [world-cells-width 100
         state' (case ch-id
                  :ticks-in
                  (-> state
                      maybe-add-food
                      update-snake
                      check-collision)

                  :buttons-in
                  (let [button msg
                        now (System/nanoTime)]
                    (if (< (- now last-button-press-nanos) 1e9)
                      state ;; "debouncing" allow 1 per second

                      (-> state
                          (assoc :last-button-press-nanos now)
                          (update :dir (case button
                                         :button-a {:up    :left ;counter-clockwise
                                                    :left  :down
                                                    :down  :right
                                                    :right :up}
                                         :button-b {:up    :right ;clockwise
                                                    :right :down
                                                    :down  :left
                                                    :left  :up}))))))
         new-speed (when (not= (count (:snake state))
                               (count (:snake state')))
                     (snake-speed (count (:snake state'))))]

     [state' (cond-> [[:snake-worlds-out [state']]]
               new-speed (conj [:ticks-speed-out [new-speed]]))])))



(comment
  (let [{:keys [redraw-game close-window]} (create-snake-window)]
    (def redraw-game redraw-game)
    (def close-window close-window))

  (redraw-game (-> initial-world
                   (snake-runner :ticks-in :tick)
                   first
                   (snake-runner :ticks-in :tick)
                   first))
  (close-window)

  )

(defn rf-snake-main [{:keys [mocked-samples]}]
  (let [hrf-samp-rate 2e6
        hrf-center-freq 629700000
        dst-samp-rate 200e3
        *running (atom true)

        {:keys [in-ch start-fn stop-fn]}
        (if mocked-samples
          (file-replay/file-replay-block mocked-samples
                                         {:frame-samples-size 4096
                                          :samp-rate dst-samp-rate
                                          :loop? true})
          (hackrf/hackrf-block {:freq-hz hrf-center-freq
                                :samp-rate hrf-samp-rate
                                :lna-gain 24
                                :vga-gain 20}))

        conns (-> (if mocked-samples
                    [[[:frame-source :samples-frames-out]   [:am-demod :samples-frames-in]]]

                    [[[:frame-source :samples-frames-out]   [:downsampler :samples-frames-in]]
                     [[:downsampler :samples-frames-out]    [:am-demod :samples-frames-in]]])

                  (into [[[:am-demod :samples-frames-out]       [:burst-splitter :samples-frames-in]]
                         [[:burst-splitter :samples-frames-out] [:normalizer :samples-frames-in]]
                         [[:normalizer :samples-frames-out]     [:ask-bit-decoder :samples-frames-in]]

                         [[:ask-bit-decoder :decoded-button-out] [:snake-runner :buttons-in]]
                         [[:snake-runner :snake-worlds-out]      [:world-renderer :snake-worlds-in]]

                         #_[[:frame-source :samples-frames-out] [:scope-1 :samples-frames-in]]
                         #_[[:downsampler :samples-frames-out]    [:scope-1 :samples-frames-in]]
                         [[:am-demod :samples-frames-out] [:scope-1 :samples-frames-in]]]))

        ticks-speed-ch (async/chan)
        ticks-ch (async/chan)
        _ (async/io-thread
           (loop [ticks-speed (-> initial-world :snake count snake-speed)]
             (when @*running
               (let [[x ch] (async/alts!! [ticks-speed-ch (async/timeout ticks-speed)])]
                 (if (= ch ticks-speed-ch)
                   (recur (long x))

                   (do
                     (async/>!! ticks-ch :tick)
                     (recur (long ticks-speed))))))))

        {:keys [redraw-game close-window]} (create-snake-window)

        system-graph (flow/create-flow
                      {:procs
                       {:frame-source {:proc (flow/process #'frame-source {:workload :io})
                                       :args {:frames-ch in-ch}}
                        :burst-splitter {:proc (flow/process #'burst-splitter {:workload :compute})}
                        :downsampler {:proc (flow/process downsampler {:workload :compute})
                                      :args {:src-samp-rate hrf-samp-rate
                                             :dst-samp-rate dst-samp-rate}}
                        :am-demod {:proc (flow/process #'am-demod {:workload :compute})}
                        :normalizer {:proc (flow/process #'normalizer {:workload :compute})}
                        :ask-bit-decoder {:proc (flow/process #'ask-bit-decoder {:workload :compute})}
                        :fir-filter {:proc (flow/process fir-filter {:workload :compute})}
                        :snake-runner {:proc (flow/process #'snake-runner {:workload :compute})
                                       :args {:ticks-ch ticks-ch
                                              :ticks-speed-ch ticks-speed-ch}}
                        :world-renderer {:proc (flow/process #'world-renderer {:workload :io})
                                         :args {:redraw-game-fn redraw-game}}
                        :scope-1 {:proc (flow/process oscilloscope-sender {:workload :io})
                                  :args {:scope-id :scope-1}}
                        #_#_:scope-2 {:proc (flow/process oscilloscope-sender {:workload :io})
                                  :args {:scope-id :scope-2}}}

                       :conns conns})]

    (flow/start  system-graph)

    {:stop-fn (fn []
                (stop-fn)
                (flow/stop system-graph)
                (reset! *running false)
                (close-window))
     :start-fn (fn []
                 (start-fn)
                 (flow/resume system-graph))}))

(defn gnu-radio []
  (let [hrf-samp-rate 2e6
        frame-samples-size (* 3 4096)

        {grc-in-ch :in-ch grc-out-ch :out-ch grc-stop :stop-fn}
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
;; (defn -main [& _args]
;;   (let [hrf-samp-rate 2e6
;;         frame-samples-size (* 3 4096)

;;         {grc-in-ch :in-ch grc-out-ch :out-ch grc-stop :stop-fn}
;;         (gnuradio-uds-block "/tmp/clj_sdr.sock"
;;                             {:frame-samples-size hackrf/frame-size
;;                              :samp-rate hrf-samp-rate})

;;         {hackrf-in-ch :in-ch hackrf-stop :stop-fn}
;;         (hackrf/hackrf-block {:freq-hz 629700000
;;                               :samp-rate hrf-samp-rate
;;                               :lna-gain 24
;;                               :vga-gain 20
;;                               :start? true})


;;         ;; {fr-in-ch :in-ch fr-stop :stop-fn}
;;         ;; (file-replay-block "/home/jmonetta/my-projects/radio-snake/gnu_radio/remote_200k.samples"
;;         ;;                    {:frame-samples-size frame-samples-size
;;         ;;                     :samp-rate samp-rate})

;;         system-graph (flow/create-flow
;;                       {:procs
;;                        {:frame-source {:proc (flow/process #'frame-source {:workload :io})}
;;                         :burst-splitter {:proc (flow/process #'burst-splitter {:workload :compute})}
;;                         :downsampler {:proc (flow/process downsampler {:workload :compute})
;;                                       :args {:src-samp-rate hrf-samp-rate
;;                                              :dst-samp-rate 200e3}}
;;                         :am-demod {:proc (flow/process #'am-demod {:workload :compute})}
;;                         :normalizer {:proc (flow/process #'normalizer {:workload :compute})}
;;                         :fir-filter {:proc (flow/process fir-filter {:workload :compute})}
;;                         :ask-bit-decoder {:proc (flow/process #'ask-bit-decoder {:workload :compute})}
;;                         :gnu-radio-sender {:proc (flow/process gnu-radio-sender {:workload :io})
;;                                            :args {:grc-out-ch grc-out-ch}}
;;                         :printer {:proc (flow/process #'printer {:workload :io})}
;;                         #_#_:scope-1 {:proc (flow/process oscilloscope-sender {:workload :io})
;;                                   :args {:scope-id :scope-1}}
;;                         #_#_:scope-2 {:proc (flow/process oscilloscope-sender {:workload :io})
;;                                   :args {:scope-id :scope-2}}}

;;                        :conns [[[:frame-source :samples-frames-out] [:downsampler :samples-frames-in]]
;;                                [[:downsampler :samples-frames-out] [:am-demod :samples-frames-in]]
;;                                [[:am-demod :samples-frames-out] [:burst-splitter :samples-frames-in]]
;;                                [[:burst-splitter :samples-frames-out] [:normalizer :samples-frames-in]]
;;                                [[:normalizer :samples-frames-out] [:ask-bit-decoder :samples-frames-in]]
;;                                [[:ask-bit-decoder :decoded-button-out] [:printer :thing]]

;;                                #_[[:normalizer :samples-frames-out] [:scope-2 :samples-frames-in]]
;;                                #_[[:am-demod :samples-frames-out] [:scope-1 :samples-frames-in]]
;;                                #_[[:frame-source :samples-frames-out] [:gnu-radio-sender :samples-frames-in]]]})]
;;     (alter-var-root #'stop-all (constantly (fn []
;;                                              #_(when fr-stop (fr-stop))
;;                                              (when grc-stop (grc-stop))
;;                                              (when hackrf-stop (hackrf-stop))
;;                                              (flow/stop system-graph))))

;;     ;; frame-source will take from `in-ch`
;;     #_(alter-var-root #'in-ch (constantly grc-in-ch))
;;     #_(alter-var-root #'in-ch (constantly fr-in-ch))
;;     (alter-var-root #'in-ch (constantly hackrf-in-ch))
;;     #_(alter-var-root #'out-ch (constantly grc-out-ch))


;;     #_(async/pipeline 1 out-ch (map identity) in-ch)
;;     #_(async/pipeline 1 gr-out-ch (map (fn [frame] (update frame :frame/samples lpf-1k))) gr-in-ch)

;;     (flow/start  system-graph)
;;     (flow/resume system-graph)
;;     ))
