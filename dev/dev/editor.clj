(ns dev.editor
  "The open-in-editor relay and the message dispatch: what a peer may name
  (resolve-src is the path trust boundary), and what we do about it."
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [dev.socket :as socket]
    [dev.inspector :as inspector]))

(defn- resolve-src
  "The trust boundary: a browser can send any string. Canonicalize and
  confine it to the project's src/ tree, .clj only, or nil."
  ^java.io.File [src]
  (let [root (.getCanonicalFile (io/file "src"))
        f (.getCanonicalFile (io/file root (str src)))]
    (when (and (.exists f)
               (str/ends-with? (.getName f) ".clj")
               (.startsWith (.toPath f) (.toPath root)))
      f)))

(defn- push-open!
  "Push an open command to connected editor agents; returns how many."
  [f line col]
  (let [editors (socket/clients-of :editor)]
    (doseq [ch editors]
      (socket/send1! ch {:type "open" :file (.getPath ^java.io.File f) :line line :col col}))
    (count editors)))

(defn- handle-open!
  "A browser asked for a source location: confine the path, hand it to a
  connected editor agent, and fall back to the `code` CLI when none is
  listening. Answers on the channel it came in on either way; ok, or why
  not, because the click happened over there."
  [ch {:keys [src line col]}]
  (if-let [f (resolve-src src)]
    (if (pos? (push-open! f line col))
      (socket/send1! ch {:type "open-result" :ok true})
      ;; no editor agent connected — fall back to the `code` CLI
      (try
        (shell/sh "code" "-g" (str (.getPath f) ":" line ":" col))
        (socket/send1! ch {:type "open-result" :ok true})
        (catch Exception e
          (socket/send1! ch {:type "open-result" :ok false :error (.getMessage e)}))))
    (socket/send1! ch {:type "open-result" :ok false :error (str "unresolved: " src)})))

(defn- handle-cursor!
  "Editor cursor moved: confine the path, resolve it against the span index,
  broadcast the matching highlight to every browser."
  [{:keys [file line col]}]
  (when (and (string? file) (number? line) (resolve-src file))
    (let [resolved (inspector/resolve-cursor file line (or col 1))]
      (socket/broadcast! (assoc resolved :type "highlight")))))

(defn handle-msg!
  "Dispatches every message accepted by the dev channel: open a file,
  identify the peer's role with `hello`, or report a moved cursor.
  Unknown types are ignored so stale browser tabs cannot break the
  relay. `dev.core` passes messages here; the socket itself remains
  unaware of their meaning."
  [ch msg]
  (case (:type msg)
    "open" (handle-open! ch msg)
    "hello" (swap! socket/roles assoc ch (keyword (or (:role msg) "browser")))
    "cursor" (do (swap! socket/roles assoc ch :editor)
                 (handle-cursor! msg))
    nil))
