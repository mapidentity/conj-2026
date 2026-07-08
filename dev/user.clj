(ns user
  "REPL conveniences. Lives in dev/, which is on the classpath only under the
  :dev alias, so Clojure loads this file automatically when a REPL starts —
  a fresh REPL lands here with (start!) ready. Nothing in dev/ ships.

  The app namespaces are resolved lazily rather than :require'd: user.clj is
  loaded before the REPL prompt appears, and a half-typed form in the tree
  must not keep the REPL from coming up.")

(defn start!
  "Start the webserver on http://localhost:8080 (or $PORT)."
  []
  ((requiring-resolve 'demo.main/start!)))

(defn stop!
  "Stop the webserver."
  []
  ((requiring-resolve 'demo.main/stop!)))

(defn restart!
  "Stop and start the webserver, e.g. after editing demo.main itself."
  []
  (stop!)
  (start!))
