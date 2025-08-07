(ns radio-snake.snake
  (:require [clojure.java.io :as io])
  (:import [javafx.stage Stage]
           [javafx.scene Scene Node]
           [javafx.scene.image Image]
           [javafx.scene.layout Pane]
           [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.application Platform]
           [javafx.scene.paint Color]))

(set! *warn-on-reflection* true)

(defn init-toolkit []
  (let [p (promise)]
    (try
      (Platform/startup (fn [] (deliver p true)))
      (catch Exception _ (deliver p false)))
    (if @p
      (println "JavaFX toolkit initialized")
      (println "JavaFX toolkit already initialized"))))

(def window-size 500)
(def cell-size 20)
(def world-size (/ window-size cell-size))
(def grass-img (Image. (io/input-stream (io/resource "grass.jpg"))))

(defn redraw-game-state [^GraphicsContext gc {:keys [food snake]}]
  (let [head-pos (dec (count snake))
        [head-x head-y] (get snake head-pos)
        snake-tail (subvec snake 0 head-pos)]

    ;; draw background
    (.drawImage gc grass-img 0.0 0.0 ^double window-size ^double window-size)

    ;; draw head
    (.setFill gc Color/BLUE)
    (.fillOval gc (- (* cell-size head-x) 5) (- (* cell-size head-y) 5) (+ cell-size 10) (+ cell-size 10))

    ;; draw tail
    (doseq [[s-cell-x s-cell-y] snake-tail]
      (let [x (* cell-size s-cell-x)
            y (* cell-size s-cell-y)]
        (.fillRect gc x y cell-size cell-size)))

    ;; draw food
    (when-let [[food-x food-y] food]
      (.setFill gc Color/RED)
      (let [x (* cell-size food-x)
            y (* cell-size food-y)]
        (.fillRect gc x y cell-size cell-size)))))

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
  {:snake [[20 22] [20 21] [20 20] [20 19]]
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
