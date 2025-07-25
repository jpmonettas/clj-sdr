(ns clj-sdr.main
  (:require [clojure.core.async.flow :as flow]
            [clojure.core.async :as async]
            [flow-storm.api :as fsa]
            [clj-sdr.samplers.gnu-radio-uds :refer [gnuradio-uds-block]]
            [clj-sdr.samplers.hackrf :as hackrf]
            [clj-sdr.samplers.file-replay :refer [file-replay-block]]
            [clj-sdr.frames :refer [make-frame]])
  (:import [org.apache.commons.math3.complex Complex]))


(set! *warn-on-reflection* true)

(def stop-all nil)
(def in-ch nil)
(def out-ch nil)

#_(defn decimate [signal factor]
  (map second (filter #(zero? (mod (first %) factor))
                      (map-indexed vector signal))))

;; Generate the fir coefficients with gnu-radio
;;
;; from gnuradio.filter import firdes
;; from gnuradio.fft import window
;; firdes.low_pass(1, 200e3, 1e3, 1e3, window.WIN_HAMMING, 6.76)

(def coeffs [0.00010071646829601377, 0.00010010505502577871, 9.948598017217591e-05, 9.885636973194778e-05, 9.821275307331234e-05, 9.755117207532749e-05, 9.686710109235719e-05, 9.615549060981721e-05, 9.541077452013269e-05, 9.462689922656864e-05, 9.37972727115266e-05, 9.291483002016321e-05, 9.197204053634778e-05, 9.096088615478948e-05, 8.987295586848632e-05, 8.869935118127614e-05, 8.743083890294656e-05, 8.605774200987071e-05, 8.457007061224431e-05, 8.295748557429761e-05, 8.120932761812583e-05, 7.93146900832653e-05, 7.726237527094781e-05, 7.504101813538e-05, 7.263901352416724e-05, 7.004461076576263e-05, 6.724596460117027e-05, 6.423112063203007e-05, 6.098806989029981e-05, 5.750480340793729e-05, 5.376932313083671e-05, 4.976969285053201e-05, 4.549408913590014e-05, 4.09308158850763e-05, 3.606837344705127e-05, 3.0895469535607845e-05, 2.540108653192874e-05, 1.957451422640588e-05, 1.340538165095495e-05, 6.883723926875973e-06, -2.5492165854993433e-19, -7.254849606397329e-06, -1.4889366866555065e-05, -2.2911526684765704e-05, -3.1328705517807975e-05, -4.0147631807485595e-05, -4.937433550367132e-05, -5.9014142607338727e-05, -6.907156785018742e-05, -7.955035107443109e-05, -9.04533953871578e-05, -0.00010178264346905053, -0.00011353920854162425, -0.0001257231633644551, -0.00013833364937454462, -0.0001513687166152522, -0.00016482539649587125, -0.00017869961448013783, -0.00019298619008623064, -0.00020767870591953397, -0.00022276968229562044, -0.00023825040261726826, -0.0002541108406148851, -0.0002703398058656603, -0.00028692485648207366, -0.0003038521681446582, -0.0003211066941730678, -0.0003386720491107553, -0.00035653053782880306, -0.0003746630100067705, -0.0003930492384824902, -0.0004116673662792891, -0.0004304943431634456, -0.0004495057219173759, -0.00046867571654729545, -0.00048797717317938805, -0.0005073817446827888, -0.000526859425008297, -0.0005463793058879673, -0.0005659088492393494, -0.0005854145274497569, -0.0006048611830919981, -0.0006242128438316286, -0.0006434320821426809, -0.0006624802481383085, -0.0006813176441937685, -0.0006999034667387605, -0.000718195631634444, -0.0007361512980423868, -0.0007537264609709382, -0.0007708760676905513, -0.0007875542505644262, -0.0008037143270485103, -0.0008193086250685155, -0.0008342888904735446, -0.0008486060542054474, -0.0008622102322988212, -0.0008750513079576194, -0.0008870781748555601, -0.0008982397266663611, -0.0009084839257411659, -0.0009177588508464396, -0.0009260119986720383, -0.0009331907494924963, -0.0009392423089593649, -0.0009441139409318566, -0.0009477527346462011, -0.0009501059539616108, -0.0009511209791526198, -0.0009507454233244061, -0.0009489271906204522, -0.0009456147672608495, -0.0009407566394656897, -0.0009343024576082826, -0.000926201930269599, -0.000916405813768506, -0.000904865562915802, -0.0008915333892218769, -0.0008763627265579998, -0.0008593075908720493, -0.000840323802549392, -0.0008193674730136991, -0.0007963968091644347, -0.0007713708328083158, -0.0007442501955665648, -0.0007149971788749099, -0.0006835752283222973, -0.0006499500013887882, -0.0006140883779153228, -0.000575959391426295, -0.0005355336470529437, -0.000492784078232944, -0.00044768519001081586, -0.00040021390304900706, -0.00035034905886277556, -0.00029807182727381587, -0.00024336556089110672, -0.0001862159842858091, -0.00012661113578360528, -6.454148388002068e-05, 1.186258992679261e-18, 6.701786332996562e-05, 0.00013651407789438963, 0.00020848803978879005, 0.00028293649666011333, 0.00035985346767120063, 0.00043923038174398243, 0.0005210558883845806, 0.0006053157267160714, 0.000691993220243603, 0.000781068520154804, 0.0008725193329155445, 0.0009663201053626835, 0.0010624427814036608, 0.0011608564527705312, 0.001261527300812304, 0.0013644187711179256, 0.001469491282477975, 0.0015767026925459504, 0.0016860079485923052, 0.0017973590875044465, 0.0019107058178633451, 0.0020259947050362825, 0.00214316975325346, 0.0022621722891926765, 0.0023829415440559387, 0.0025054130237549543, 0.0026295206043869257, 0.002755195600911975, 0.0028823663014918566, 0.0030109593644738197, 0.003140899119898677, 0.003272106871008873, 0.0034045022912323475, 0.0035380027256906033, 0.003672523656859994, 0.0038079784717410803, 0.003944279160350561, 0.0040813349187374115, 0.004219054244458675, 0.004357342608273029, 0.004496105946600437, 0.004635246936231852, 0.004774667788296938, 0.004914269782602787, 0.005053950939327478, 0.005193611606955528, 0.0053331488743424416, 0.005472458899021149, 0.005611437372863293, 0.005749981384724379, 0.005887983832508326, 0.006025340408086777, 0.006161944940686226, 0.006297690328210592, 0.006432472262531519, 0.006566183641552925, 0.006698718294501305, 0.0068299719132483006, 0.006959837395697832, 0.00708821089938283, 0.007214989047497511, 0.0073400684632360935, 0.0074633448384702206, 0.0075847189873456955, 0.007704088464379311, 0.007821355946362019, 0.00793642271310091, 0.008049191907048225, 0.008159569464623928, 0.008267461322247982, 0.008372778072953224, 0.008475427515804768, 0.008575322106480598, 0.008672378957271576, 0.00876651145517826, 0.008857641369104385, 0.008945687673985958, 0.00903057586401701, 0.009112230502068996, 0.009190581738948822, 0.00926556158810854, 0.009337103925645351, 0.009405144490301609, 0.009469626471400261, 0.009530490264296532, 0.009587683714926243, 0.00964115746319294, 0.009690862149000168, 0.00973675400018692, 0.009778794832527637, 0.009816943667829037, 0.009851169772446156, 0.009881440550088882, 0.009907729923725128, 0.009930015541613102, 0.009948275052011013, 0.009962495416402817, 0.009972660802304745, 0.0099787637591362, 0.009980798698961735, 0.0099787637591362, 0.009972660802304745, 0.009962495416402817, 0.009948275052011013, 0.009930015541613102, 0.009907729923725128, 0.009881440550088882, 0.009851169772446156, 0.009816943667829037, 0.009778794832527637, 0.00973675400018692, 0.009690862149000168, 0.00964115746319294, 0.009587683714926243, 0.009530490264296532, 0.009469626471400261, 0.009405144490301609, 0.009337103925645351, 0.00926556158810854, 0.009190581738948822, 0.009112230502068996, 0.00903057586401701, 0.008945687673985958, 0.008857641369104385, 0.00876651145517826, 0.008672378957271576, 0.008575322106480598, 0.008475427515804768, 0.008372778072953224, 0.008267461322247982, 0.008159569464623928, 0.008049191907048225, 0.00793642271310091, 0.007821355946362019, 0.007704088464379311, 0.0075847189873456955, 0.0074633448384702206, 0.0073400684632360935, 0.007214989047497511, 0.00708821089938283, 0.006959837395697832, 0.0068299719132483006, 0.006698718294501305, 0.006566183641552925, 0.006432472262531519, 0.006297690328210592, 0.006161944940686226, 0.006025340408086777, 0.005887983832508326, 0.005749981384724379, 0.005611437372863293, 0.005472458899021149, 0.0053331488743424416, 0.005193611606955528, 0.005053950939327478, 0.004914269782602787, 0.004774667788296938, 0.004635246936231852, 0.004496105946600437, 0.004357342608273029, 0.004219054244458675, 0.0040813349187374115, 0.003944279160350561, 0.0038079784717410803, 0.003672523656859994, 0.0035380027256906033, 0.0034045022912323475, 0.003272106871008873, 0.003140899119898677, 0.0030109593644738197, 0.0028823663014918566, 0.002755195600911975, 0.0026295206043869257, 0.0025054130237549543, 0.0023829415440559387, 0.0022621722891926765, 0.00214316975325346, 0.0020259947050362825, 0.0019107058178633451, 0.0017973590875044465, 0.0016860079485923052, 0.0015767026925459504, 0.001469491282477975, 0.0013644187711179256, 0.001261527300812304, 0.0011608564527705312, 0.0010624427814036608, 0.0009663201053626835, 0.0008725193329155445, 0.000781068520154804, 0.000691993220243603, 0.0006053157267160714, 0.0005210558883845806, 0.00043923038174398243, 0.00035985346767120063, 0.00028293649666011333, 0.00020848803978879005, 0.00013651407789438963, 6.701786332996562e-05, 1.186258992679261e-18, -6.454148388002068e-05, -0.00012661113578360528, -0.0001862159842858091, -0.00024336556089110672, -0.00029807182727381587, -0.00035034905886277556, -0.00040021390304900706, -0.00044768519001081586, -0.000492784078232944, -0.0005355336470529437, -0.000575959391426295, -0.0006140883779153228, -0.0006499500013887882, -0.0006835752283222973, -0.0007149971788749099, -0.0007442501955665648, -0.0007713708328083158, -0.0007963968091644347, -0.0008193674730136991, -0.000840323802549392, -0.0008593075908720493, -0.0008763627265579998, -0.0008915333892218769, -0.000904865562915802, -0.000916405813768506, -0.000926201930269599, -0.0009343024576082826, -0.0009407566394656897, -0.0009456147672608495, -0.0009489271906204522, -0.0009507454233244061, -0.0009511209791526198, -0.0009501059539616108, -0.0009477527346462011, -0.0009441139409318566, -0.0009392423089593649, -0.0009331907494924963, -0.0009260119986720383, -0.0009177588508464396, -0.0009084839257411659, -0.0008982397266663611, -0.0008870781748555601, -0.0008750513079576194, -0.0008622102322988212, -0.0008486060542054474, -0.0008342888904735446, -0.0008193086250685155, -0.0008037143270485103, -0.0007875542505644262, -0.0007708760676905513, -0.0007537264609709382, -0.0007361512980423868, -0.000718195631634444, -0.0006999034667387605, -0.0006813176441937685, -0.0006624802481383085, -0.0006434320821426809, -0.0006242128438316286, -0.0006048611830919981, -0.0005854145274497569, -0.0005659088492393494, -0.0005463793058879673, -0.000526859425008297, -0.0005073817446827888, -0.00048797717317938805, -0.00046867571654729545, -0.0004495057219173759, -0.0004304943431634456, -0.0004116673662792891, -0.0003930492384824902, -0.0003746630100067705, -0.00035653053782880306, -0.0003386720491107553, -0.0003211066941730678, -0.0003038521681446582, -0.00028692485648207366, -0.0002703398058656603, -0.0002541108406148851, -0.00023825040261726826, -0.00022276968229562044, -0.00020767870591953397, -0.00019298619008623064, -0.00017869961448013783, -0.00016482539649587125, -0.0001513687166152522, -0.00013833364937454462, -0.0001257231633644551, -0.00011353920854162425, -0.00010178264346905053, -9.04533953871578e-05, -7.955035107443109e-05, -6.907156785018742e-05, -5.9014142607338727e-05, -4.937433550367132e-05, -4.0147631807485595e-05, -3.1328705517807975e-05, -2.2911526684765704e-05, -1.4889366866555065e-05, -7.254849606397329e-06, -2.5492165854993433e-19, 6.883723926875973e-06, 1.340538165095495e-05, 1.957451422640588e-05, 2.540108653192874e-05, 3.0895469535607845e-05, 3.606837344705127e-05, 4.09308158850763e-05, 4.549408913590014e-05, 4.976969285053201e-05, 5.376932313083671e-05, 5.750480340793729e-05, 6.098806989029981e-05, 6.423112063203007e-05, 6.724596460117027e-05, 7.004461076576263e-05, 7.263901352416724e-05, 7.504101813538e-05, 7.726237527094781e-05, 7.93146900832653e-05, 8.120932761812583e-05, 8.295748557429761e-05, 8.457007061224431e-05, 8.605774200987071e-05, 8.743083890294656e-05, 8.869935118127614e-05, 8.987295586848632e-05, 9.096088615478948e-05, 9.197204053634778e-05, 9.291483002016321e-05, 9.37972727115266e-05, 9.462689922656864e-05, 9.541077452013269e-05, 9.615549060981721e-05, 9.686710109235719e-05, 9.755117207532749e-05, 9.821275307331234e-05, 9.885636973194778e-05, 9.948598017217591e-05, 0.00010010505502577871, 0.00010071646829601377])

(defn fir-filter-samples [coeffs samples]
  (let [conv-window-size (count coeffs)
        convolution (fn [fst i]
                      (let [w-end (+ i conv-window-size)]
                        (loop [ii i
                               fst fst
                               acc (Complex. 0 0)]
                          (if (< ii w-end)
                            (let [coef (get coeffs (mod ii conv-window-size))
                                  sample (get samples ii)
                                  acc (.add ^Complex acc ^Complex (.multiply ^Complex sample ^double coef))]
                              (recur (inc ii) fst acc))
                            (conj! fst acc)))))]
    (persistent!
     (reduce (fn [acc i]
               (convolution acc i))
             (transient [])
             (range 0 (- (count samples) conv-window-size))))))

(defn frame-source
  ([] {:outs {:samples-frames-out "A samples frame"}})
  ([_] {::flow/in-ports  {:samples-frames-in in-ch}})
  ([state _] state)
  ([state _ch-id samples-frame]
   [state [[:samples-frames-out [samples-frame]]]]))

(defn fir-filter
  ([] {:ins {:samples-frames-in ["A vector of samples containing an IQ signal"]}
       :outs {:samples-frames-out "A vector of samples containing the filtered IQ signal"}})
  ([_] {})
  ([state _] state)
  ([state _ch-id {:keys [samples samp-rate]}]
   (let [filtered-samples (fir-filter-samples coeffs samples)]
     [state [[:samples-frames-out [(make-frame :complex samp-rate filtered-samples)]]]])))

(defn am-demod
  ([] {:ins {:samples-frames-in ["A vector of samples containing the AM modulated signal"]}
       :outs {:samples-frames-out "A vector of samples containing an amiplitude demodulated signal"}})
  ([_] {})
  ([state _] state)
  ([state _ch-id {:keys [samples samp-rate]}]
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
  ([state _ch-id {:keys [samples samp-rate] :as frame}]
   (let [nanos-per-sample (/ 1e9 samp-rate)
         samples-amplitude (case (:frame-type frame)
                             :real    identity
                             :complex Complex/.abs)
         amplitude-level-threshold 1.4
         activity-nanos-threshold 500e3 ;; 10ms between bursts, so lets separate bursts when more than 7ms low
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
                           (update :burst-frames conj (make-frame (:frame-type frame)
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
                 (:samples samples-frame))]
     [state [[:decoded-packets-out [decoded-packet]]]])))

(defn gnu-radio-sender
  ([] {:ins {:samples-frame-in "A vector of TimedIQSamples samples"}})
  ([_] {::flow/out-ports {:samples-frames-out out-ch}})
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
  (let [samp-rate 200e3
        frame-samples-size (* 3 4096)

        {grc-in-ch :in-ch grc-out-ch :out-ch grc-stop :stop-fn}
        (gnuradio-uds-block "/tmp/clj_sdr.sock"
                            {:frame-samples-size hackrf/frame-size
                             :samp-rate samp-rate})

        {hackrf-in-ch :in-ch hackrf-stop :stop-fn}
        (hackrf/hackrf-block {:freq-hz 629700000})


        ;; {fr-in-ch :in-ch fr-stop :stop-fn}
        ;; (file-replay-block "/home/jmonetta/my-projects/clj-sdr/gnu_radio/remote_200k.samples"
        ;;                    {:frame-samples-size frame-samples-size
        ;;                     :samp-rate samp-rate})

        system-graph (flow/create-flow
                      {:procs
                       {:frame-source {:proc (flow/process frame-source)}
                        :am-demod {:proc (flow/process am-demod)}
                        :burst-splitter {:proc (flow/process burst-splitter)}
                        :ask-bit-decoder {:proc (flow/process ask-bit-decoder)}
                        :gnu-radio-sender {:proc (flow/process gnu-radio-sender)}
                        :printer {:proc (flow/process printer)}}

                       :conns [[[:frame-source :samples-frames-out] [:burst-splitter :samples-frames-in] ]
                               [[:burst-splitter :samples-frames-out] [:am-demod :samples-frames-in]]
                               [[:am-demod :samples-frames-out] [:ask-bit-decoder :samples-frame-in]]
                               [[:ask-bit-decoder :decoded-packets-out] [:printer :thing]]

                              #_[[:frame-source :samples-frames-out] [:gnu-radio-sender :samples-frame-in]]]})]
    (alter-var-root #'stop-all (constantly (fn []
                                             #_(when fr-stop (fr-stop))
                                             (when grc-stop (grc-stop))
                                             (hackrf-stop)
                                             (flow/stop system-graph))))

    ;; frame-source will take from `in-ch`
    #_(alter-var-root #'in-ch (constantly grc-in-ch))
    #_(alter-var-root #'in-ch (constantly fr-in-ch))
    (alter-var-root #'in-ch (constantly hackrf-in-ch))
    #_(alter-var-root #'out-ch (constantly grc-out-ch))


    #_(async/pipeline 1 out-ch (map identity) in-ch)
    #_(async/pipeline 1 gr-out-ch (map (fn [frame] (update frame :frame/samples lpf-1k))) gr-in-ch)


    (doto (Thread.
           (fn []
             (loop [first? true]
               (when-not (Thread/interrupted)
                 (when-let [frame (async/<!! in-ch)]

                   (if first?
                     (fsa/data-window-push-val :scope frame)

                     (fsa/data-window-val-update :scope frame))

                   (recur false))))))
      (.start))
    #_(flow/start  system-graph)
    #_(flow/resume system-graph)
    ))
