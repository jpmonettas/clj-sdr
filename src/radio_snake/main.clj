(ns radio-snake.main
  (:require [clojure.core.async.flow :as flow]
            [clojure.core.async :as async]
            [radio-snake.samplers.hackrf :as hackrf]
            [radio-snake.samplers.file-replay :as file-replay]
            [radio-snake.frames :as frames]
            [radio-snake.snake :as snake]
            [radio-snake.radio :as radio]))


(set! *warn-on-reflection* true)

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
  ([{:keys [scope-id push-val-fn val-update-fn]}]
   {:scope-id scope-id
    :push-val-fn push-val-fn
    :val-update-fn val-update-fn})
  ([{:keys [scope-id push-val-fn] :as state} transition]
   (when (= ::flow/resume transition)
     (push-val-fn scope-id (frames/make-frame)))
   state)
  ([{:keys [scope-id val-update-fn] :as state} _ch-id samples-frame]
   (val-update-fn scope-id samples-frame)
   state))

(defn snake-runner
  ([] {:ins {:buttons-in ""}
       :outs {:snake-worlds-out ""
              :ticks-speed-out ""}})
  ([{:keys [ticks-ch ticks-speed-ch]}]
   (-> snake/initial-world
       (assoc ::flow/in-ports {:ticks-in ticks-ch}
              ::flow/out-ports {:ticks-speed-out ticks-speed-ch})))
  ([state _] state)
  ([{:keys [last-button-press-nanos] :as state} ch-id msg]
   (let [state' (case ch-id
                  :ticks-in
                  (-> state
                      snake/maybe-add-food
                      snake/update-snake
                      snake/check-collision)

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
                     (snake/snake-speed (count (:snake state'))))]

     [state' (cond-> [[:snake-worlds-out [state']]]
               new-speed (conj [:ticks-speed-out [new-speed]]))])))


(defn rf-snake-main [{:keys [mocked-samples scopes] :or {scopes #{}}}]
  (let [hrf-samp-rate 2e6
        hrf-center-freq 629700000
        dst-samp-rate 200e3
        *running (atom true)
        [push-val-fn val-update-fn] (when-not (empty? scopes)
                                      [(requiring-resolve 'flow-storm.api/data-window-push-val)
                                       (requiring-resolve 'flow-storm.api/data-window-val-update)])
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

        conns (cond-> (if mocked-samples
                        [[[:frame-source :samples-frames-out]   [:am-demod :samples-frames-in]]]

                        [[[:frame-source :samples-frames-out]   [:downsampler :samples-frames-in]]
                         [[:downsampler :samples-frames-out]    [:am-demod :samples-frames-in]]])

                true (into [[[:am-demod :samples-frames-out]              [:burst-splitter :samples-frames-in]]
                            [[:burst-splitter :samples-frames-out]        [:normalizer :samples-frames-in]]
                            [[:normalizer :samples-frames-out]            [:ask-bit-decoder :samples-frames-in]]
                            [[:ask-bit-decoder :bin-strings-out]          [:remote-button-decoder :bin-strings-in]]
                            [[:remote-button-decoder :decoded-button-out] [:snake-runner :buttons-in]]
                            [[:snake-runner :snake-worlds-out]            [:world-renderer :snake-worlds-in]]])
                (scopes :frame-source) (into [[[:frame-source :samples-frames-out] [:frame-source-scope :samples-frames-in]]])
                (scopes :am-demod) (into [[[:am-demod :samples-frames-out] [:am-demod-scope :samples-frames-in]]])
                (scopes :burst-splitter) (into [[[:burst-splitter :samples-frames-out] [:burst-splitter-scope :samples-frames-in]]])
                (scopes :normalizer) (into [[[:normalizer :samples-frames-out] [:normalizer-scope :samples-frames-in]]]))

        ticks-speed-ch (async/chan)
        ticks-ch (async/chan)
        _ (async/io-thread
           (loop [ticks-speed (-> snake/initial-world :snake count snake/snake-speed)]
             (when @*running
               (let [[x ch] (async/alts!! [ticks-speed-ch (async/timeout ticks-speed)])]
                 (if (= ch ticks-speed-ch)
                   (recur (long x))

                   (do
                     (async/>!! ticks-ch :tick)
                     (recur (long ticks-speed))))))))

        {:keys [redraw-game close-window]} (snake/create-snake-window)

        system-graph (flow/create-flow
                      {:procs
                       (cond-> {:frame-source {:proc (flow/process #'radio/frame-source {:workload :io})
                                               :args {:frames-ch in-ch}}
                                :burst-splitter {:proc (flow/process #'radio/burst-splitter {:workload :compute})}
                                :downsampler {:proc (flow/process radio/downsampler {:workload :compute})
                                              :args {:src-samp-rate hrf-samp-rate
                                                     :dst-samp-rate dst-samp-rate}}
                                :am-demod {:proc (flow/process #'radio/am-demod {:workload :compute})}
                                :normalizer {:proc (flow/process #'radio/normalizer {:workload :compute})}
                                :remote-button-decoder {:proc (flow/process #'radio/remote-button-decoder {:workload :compute})}
                                :ask-bit-decoder {:proc (flow/process #'radio/ask-bit-decoder {:workload :compute})}
                                :fir-filter {:proc (flow/process radio/fir-filter {:workload :compute})}
                                :snake-runner {:proc (flow/process #'snake-runner {:workload :compute})
                                               :args {:ticks-ch ticks-ch
                                                      :ticks-speed-ch ticks-speed-ch}}
                                :world-renderer {:proc (flow/process #'world-renderer {:workload :io})
                                                 :args {:redraw-game-fn redraw-game}}}
                         (scopes :frame-source) (assoc :frame-source-scope
                                                       {:proc (flow/process oscilloscope-sender {:workload :io})
                                                        :args {:scope-id :frame-source-scope
                                                               :push-val-fn push-val-fn :val-update-fn val-update-fn}})
                         (scopes :am-demod) (assoc :am-demod-scope
                                                   {:proc (flow/process oscilloscope-sender {:workload :io})
                                                    :args {:scope-id :am-demod-scope
                                                           :push-val-fn push-val-fn :val-update-fn val-update-fn}})
                         (scopes :burst-splitter) (assoc :burst-splitter-scope
                                                   {:proc (flow/process oscilloscope-sender {:workload :io})
                                                    :args {:scope-id :burst-splitter-scope
                                                           :push-val-fn push-val-fn :val-update-fn val-update-fn}})
                         (scopes :normalizer) (assoc :normalizer-scope
                                                   {:proc (flow/process oscilloscope-sender {:workload :io})
                                                    :args {:scope-id :normalizer-scope
                                                           :push-val-fn push-val-fn :val-update-fn val-update-fn}}))
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


(defn -main [& _args]
  (let [{:keys [start-fn]} (rf-snake-main {})]
    (start-fn)))
