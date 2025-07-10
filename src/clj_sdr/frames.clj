(ns clj-sdr.frames)

(defn make-frame [frame-type samp-rate samples]
  {:frame/type frame-type
   :frame/samp-rate samp-rate
   :frame/samples samples})

(defn frame? [x]
  (and (map? x)
       (contains? x :frame/type)))
