(ns dev.socket
  "The dev channel: connected browsers, the shared WebSocket endpoint
  they use, and the signal that tells them to reload. It knows nothing
  about message semantics; the composition root wires those in."
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
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

(defn send1!
  "Sends `msg` as JSON to a single channel, typically as a reply to a
  message received on that channel. Use `broadcast!` to send to every
  connected browser instead."
  [ch msg]
  (http/send! ch (json/write-str msg)))

(defn- origin-ok?
  "The peer trust boundary; `dev.editor/resolve-src` is the path
  boundary. Browsers always send an `Origin` during the WebSocket
  handshake, and WebSockets are not same-origin restricted, so any
  open page could attempt to connect. Accept only our own origin.
  Native clients, such as the editor agent or `curl`, send no `Origin`."
  [req]
  (let [origin (get-in req [:headers "origin"])]
    (or (nil? origin)
        (= (get-in req [:headers "host"])
           (str/replace origin #"^https?://" "")))))

(defn ws-handler
  "The `/dev/ws` endpoint: validates the origin, upgrades the connection
  to WebSocket, tracks the channel in `clients` while it is active,
  and passes each decoded message to `handle-msg!`. The composition
  root supplies the handler, keeping this namespace unaware of message
  semantics."
  [req handle-msg!]
  (if-not (origin-ok? req)
    {:status 403 :headers {"Content-Type" "text/plain"} :body "forbidden origin"}
    (http/as-channel req
    {:on-open (fn [ch] (swap! clients conj ch))
     :on-receive (fn [ch raw] (handle-msg! ch (json/read-str raw :key-fn keyword)))
     :on-close (fn [ch _] (swap! clients disj ch))})))
