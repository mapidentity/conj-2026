(ns dev.core
  "Dev-only plumbing.
  wires together: the /dev routes, the render boundary binding, and start!.
  Lives under dev/, on the classpath only through the :dev alias.
  The app (should) never mentions this namespace. In production it is
  structurally absent, not disabled. If additions are made and the app
  really needs to reference here, use `(requiring-resolve …)`."
  (:require
    [dev.socket :as socket]
    [dev.static :as static]
    [dev.watcher :as watcher]
    [demo.main :as main]
    [demo.views :as views]))

;; --- the dev middleware: the app, wrapped ---

(defn- dev-body
  "Applies the app's dev render boundary: appends the dev scripts to the
  rendered page."
  [body]
  (list body
        [:script {:src "/dev/reload.js"}]))

(defn- dev-route
  "Handles dev endpoints, then falls back to files under static/,
  including the dev scripts. Returns nil for anything else, leaving
  it to the app."
  [req]
  (case (:uri req)
    "/dev/ws" (socket/ws-handler req)
    (static/file (:uri req))))

(defn wrap-dev
  "Wraps the app with dev behavior: handles dev endpoints first, then
  runs all other requests with the render boundary bound to `dev-body`."
  [handler]
  (fn [req]
    (or (dev-route req)
        (binding [views/*render-boundary* dev-body]
          (handler req)))))

(defn start!
  "Tags the views, then starts the app wrapped in the dev middleware, then
  the watcher. load-views! comes first: the server is answering requests the
  moment main/start! returns. A page rendered before the views are
  tagged looks fine but lost all metadata."
  []
  (watcher/load-views!)
  (main/start! (wrap-dev #'main/app))
  (watcher/start-watcher!))
