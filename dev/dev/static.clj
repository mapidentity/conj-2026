(ns dev.static
  "Serves files from `static/` the same way the ingress does, so plain
  `localhost:8080` behaves like `https://myapp.lan`, favicon included.
  This is dev-only; production should serve these files through the web
  server. The app exposes no static route.

  These files are not on the classpath. `static/` contains assets
  served directly by the web server, while `resources/` is for files
  the app reads itself, such as the layout-inlined `style.css`."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]))

(def ^:private content-types
  "Enough of a mime table for what static/ holds."
  {"js" "text/javascript"
   "css" "text/css"
   "html" "text/html; charset=utf-8"
   "svg" "image/svg+xml"
   "ico" "image/x-icon"
   "png" "image/png"})

(defn- resolve-static
  "The trust boundary, as in `dev.editor`: canonicalize the path
  and confine it to the project's `static/` tree, or return `nil`."
  ^java.io.File [uri]
  (let [root (.getCanonicalFile (io/file "static"))
        f (.getCanonicalFile (io/file root (str/replace-first uri #"^/" "")))]
    (when (and (.isFile f) (.startsWith (.toPath f) (.toPath root)))
      f)))

(defn file
  "Returns the file behind `uri` as a response, or `nil` when no such
  file exists. Returning `nil` lets this serve as the fallback in the
  dev route."
  [uri]
  (when-let [f (resolve-static uri)]
    (let [n (.getName f)
          ext (subs n (inc (.lastIndexOf n ".")))]
      {:status 200
       ;; the File goes to http-kit as-is: it writes the bytes itself, so
       ;; this serves favicon.ico as correctly as it serves reload.js
       :headers {"Content-Type" (content-types ext "application/octet-stream")
                 "Cache-Control" "no-store"}
       :body f})))
