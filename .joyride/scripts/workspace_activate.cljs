(ns workspace-activate
  "The editor side of the inspector.
  Runs automatically when this workspace opens in VS Code with the Joyride
  extension installed (or on demand: 'Joyride: Run Workspace Script').

  Holds one WebSocket to the dev server and:
    SENDS    {type hello role editor} on connect (registers as the editor)
             {type cursor file line col} on debounced cursor moves
    RECEIVES {type open file line col} and opens that file at that position
             via the vscode API.

  Two hard-won notes about the extension host's WebSocket (undici):
    - a dropped link fires only 'close'; a FAILED connect fires only 'error'
      — reconnect on both, deduped via the current-socket check.
    - never call .close from inside a close/error handler; undici re-enters
      synchronously and wedges the extension host."
  (:require
    ["vscode" :as vscode]
    [clojure.string :as str]))

(def ws-url
  "The dev socket is accessed directly from within the container,
   bypassing the TLS ingress, so mixed-content restrictions do not apply.
   Native clients also omit the Origin header,
   which the server's origin check explicitly allows.
   The only limitation is that the port is hardcoded to 8080,
   so an application running on a different PORT will not be detected."
  "ws://localhost:8080/dev/ws")

(defonce ^{:doc "The agent's mutable state: the active socket, the selection
   listener's disposable, and the debounce timer. Defined with
   `defonce` so re-running the script can recover state from the
   previous run; the activation block below disposes of any existing
   resources before wiring everything up again."}
  !state
  (atom {:ws nil :disposable nil :timer nil}))

(defn- ws-send!
  "Send `obj` as JSON, but only while the socket is actually open. Anything
  sent in between is dropped on purpose: a cursor position is worth nothing
  by the time a queue would deliver it."
  [obj]
  (let [ws (:ws @!state)]
    (when (and ws (= 1 (.-readyState ws)))
      (.send ws (js/JSON.stringify (clj->js obj))))))

(defn- rel-path
  "Absolute fsPath -> the classpath-relative key the index uses:
  …/src/demo/views.clj -> demo/views.clj. nil when not under a src/ dir.
  Split on the LAST /src/ — a clone living under ~/src/… must not fool it."
  [fs-path]
  (when (and fs-path (str/ends-with? fs-path ".clj"))
    (let [parts (str/split fs-path #"/src/")]
      (when (next parts) (last parts)))))

(defn- open-file!
  "Open the `file` at the given `line` and `column`, then scroll it into the
  center of the window. Wire coordinates are 1-based, matching what
  readers see, while the VS Code API is 0-based; the `dec`s simply
  convert between the two."
  [file line column]
  (let [uri (.file vscode/Uri file)
        pos (vscode/Position. (max 0 (dec (or line 1))) (max 0 (dec (or column 1))))
        sel (vscode/Selection. pos pos)]
    (-> (.openTextDocument vscode/workspace uri)
        (.then (fn [doc] (.showTextDocument vscode/window doc #js {:selection sel})))
        (.then (fn [ed] (.revealRange ed (vscode/Range. pos pos)
                                      (.. vscode -TextEditorRevealType -InCenter)))))))

(defn- on-message
  "Handles messages received from the dev server. Only `open`, sent when
  the browser is clicked, is relevant here. All other message types are
  ignored so the server can add new ones without breaking editors still
  running an older version of the script."
  [event]
  (let [m (js->clj (js/JSON.parse (.-data event)) :keywordize-keys true)]
    (when (= "open" (:type m))
      (open-file! (:file m) (:line m) (:col m)))))

(defn- on-selection
  "Debounced cursor reporter: file + 1-based line/column."
  [event]
  (when-let [file (rel-path (.. (.-document (.-textEditor event)) -uri -fsPath))]
    (let [sel (aget (.-selections event) 0)
          line (inc (.. sel -active -line))
          col (inc (.. sel -active -character))]
      (when-let [t (:timer @!state)] (js/clearTimeout t))
      (swap! !state assoc :timer
        (js/setTimeout #(ws-send! {:type "cursor" :file file :line line :col col}) 80)))))

(declare connect!)

(defn- schedule-reconnect!
  "Retry after 2 seconds, at most once per socket. A dropped
  connection and a failed connect can both fire for the same `ws`, so
  only the current socket may schedule a retry. Nothing is closed here
  deliberately; see the Undici note in the namespace docstring."
  [ws]
  ;; only the CURRENT socket may schedule, so close+error dedupe to one retry
  (when (= ws (:ws @!state))
    (swap! !state assoc :ws nil)
    (js/setTimeout connect! 2000)))

(defn- connect!
  "Open the socket, announce this client as the editor, and wire the
  handlers. Both close and error reconnect: undici fires only one of them
  depending on whether the link dropped or never came up."
  []
  (let [ws (js/WebSocket. ws-url)]
    (swap! !state assoc :ws ws)
    (.addEventListener ws "open"
      (fn [_]
        (ws-send! {:type "hello" :role "editor"})
        (js/console.log "demo inspector: editor agent connected")))
    (.addEventListener ws "message" on-message)
    (.addEventListener ws "close" (fn [_] (schedule-reconnect! ws)))
    (.addEventListener ws "error" (fn [_] (schedule-reconnect! ws)))))

;; --- activate (idempotent across re-evals) ---
(when-let [d (:disposable @!state)] (.dispose d))
(when-let [ws (:ws @!state)]
  (when (< (.-readyState ws) 2) (try (.close ws) (catch :default _ nil))))
(swap! !state assoc :disposable
  (.onDidChangeTextEditorSelection vscode/window on-selection))
(connect!)
(println "demo inspector: editor agent active on" ws-url)
