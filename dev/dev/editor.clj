(ns dev.editor
  "The open-in-editor relay and the message dispatch: what a peer may name
  (resolve-src is the path trust boundary), and what we do about it."
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [dev.socket :as socket]))

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

(defn- handle-open!
  "A browser asked for a source location: confine the path, shell out to
  the `code` CLI, and answer on the channel it came in on; ok, or why
  not, because the click happened over there."
  [ch {:keys [src line col]}]
  (if-let [f (resolve-src src)]
    (try
      (shell/sh "code" "-g" (str (.getPath f) ":" line ":" col))
      (socket/send1! ch {:type "open-result" :ok true})
      (catch Exception e
        (socket/send1! ch {:type "open-result" :ok false :error (.getMessage e)})))
    (socket/send1! ch {:type "open-result" :ok false :error (str "unresolved: " src)})))

(defn handle-msg!
  "Dispatches every message accepted by the dev channel; at this point
  only `open`. Unknown types are ignored so stale browser tabs cannot
  break the relay. `dev.core` passes messages here; the socket itself
  remains unaware of their meaning."
  [ch msg]
  (case (:type msg)
    "open" (handle-open! ch msg)
    nil))
