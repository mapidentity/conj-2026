(ns demo.main
  "Application entrypoint. Start the webserver from this namespace."
  (:require
    [demo.views :as views]
    [org.httpkit.server :as http]))

(def recipes
  "Some (static) example data. A real app would get this from a database."
  [{:name "Shakshuka" :emoji "🍳" :hue 15
    :description "Eggs poached in a spiced tomato and pepper sauce."
    :tags ["breakfast" "vegetarian"] :minutes 30 :serves 2 :level 1 :stars 4}
   {:name "Pad Thai" :emoji "🍜" :hue 35 :new? true
    :description "Rice noodles with tamarind, lime and peanuts."
    :tags ["noodles" "spicy"] :minutes 25 :serves 2 :level 2 :stars 5}
   {:name "Ratatouille" :emoji "🍆" :hue 280
    :description "Summer vegetables, stewed slowly and stacked proudly."
    :tags ["vegetarian" "dinner"] :minutes 60 :serves 4 :level 2 :stars 4}
   {:name "Okonomiyaki" :emoji "🥞" :hue 45 :new? true
    :description "Savoury cabbage pancake, sauce on everything."
    :tags ["dinner"] :minutes 35 :serves 2 :level 2 :stars 4}
   {:name "Mushroom Risotto" :emoji "🍄" :hue 100
    :description "Slow-stirred arborio, porcini, too much butter."
    :tags ["rice" "vegetarian" "dinner"] :minutes 45 :serves 4 :level 3 :stars 5}
   {:name "Tacos al Pastor" :emoji "🌮" :hue 0
    :description "Chile-marinated pork, charred pineapple, warm tortillas."
    :tags ["spicy" "dinner"] :minutes 90 :serves 6 :level 3 :stars 5}
   {:name "Gazpacho" :emoji "🍅" :hue 340
    :description "Cold-blended tomatoes for days too hot to cook."
    :tags ["soup" "vegetarian"] :minutes 15 :serves 4 :level 1 :stars 3}
   {:name "Chana Masala" :emoji "🍛" :hue 60
    :description "Chickpeas simmered in a deeply spiced tomato gravy."
    :tags ["dinner" "vegetarian"] :minutes 40 :serves 4 :level 1 :stars 4}])

(defn- query-param
  "The value of ?name=… from the request, or nil.
   For a real app you'd probably use the ring library for this."
  [req name]
  (when-let [qs (:query-string req)]
    (second (re-find (re-pattern (str name "=([^&]+)")) qs))))

(defn app
  "Main handler for web requests."
  [req]
  (case (:uri req)
    "/" {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (views/layout (views/page recipes (query-param req "tag")))}
    {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"}))

(def port
  "The port to listen on: $PORT when set, else 8080."
  (parse-long (or (System/getenv "PORT") "8080")))

(defonce ^{:doc "The running server: http-kit's stop function while up, nil
  while down. defonce, not def, so reloading this namespace keeps the handle
  on a server that is already listening — otherwise the port stays bound with
  nothing left that can free it."}
  server
  (atom nil))

(defn start!
  "Starts the application web server."
  []
  (if @server
    (println (str "already running on http://localhost:" port))
    (do (reset! server (http/run-server #'app {:ip "0.0.0.0" :port port}))
        (println (str "listening on http://localhost:" port)))))

(defn stop!
  "Stops an already running web server."
  []
  (when-let [s @server] (s) (reset! server nil)))

(defn -main
  "Production entrypoint (clojure -M -m demo.main): start the server and
  block forever. Nothing from dev/ is on the classpath here, so this is the
  app alone — no watcher, no inspector, nothing to switch off."
  [& _]
  (start!)
  @(promise))
