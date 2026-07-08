(ns dev.socket
  "The dev channel: connected browsers, the shared WebSocket endpoint
  they use, and the signal that tells them to reload. It knows nothing
  about message semantics; the composition root wires those in."
  (:require
    [clojure.data.json :as json]
    [org.httpkit.server :as http]))

(defonce ^{:doc "Tracks every browser that currently has the dev socket open.
  Defined with `defonce` so reloading this namespace, as the watcher
  does after each edit, preserves the set instead of orphaning live
  channels."}
  clients
  (atom #{}))

(defn broadcast!
  "Sends `msg` as JSON to every connected browser. The message is
  encoded once and sent to each browser."
  [msg]
  (let [s (json/write-str msg)]
    (doseq [ch @clients]
      (http/send! ch s))))

(defn notify-reload!
  "Tells every connected browser to reload. The watcher sends this once
  per batch of changed files, and only after a successful load."
  []
  (broadcast! {:type "reload"}))

(defn ws-handler
  "The `/dev/ws` endpoint: upgrades the connection to WebSocket and
  tracks the channel in `clients` while it is active."
  [req]
  (http/as-channel req
    {:on-open (fn [ch] (swap! clients conj ch))
     :on-close (fn [ch _] (swap! clients disj ch))}))
