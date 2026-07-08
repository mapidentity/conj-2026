(ns dev.watcher
  "Polls `src/`, `dev/`, `resources/`, and `static/` for changes, reloads
  the Clojure that changed, and tells browsers to reload once per batch.
  No reload is sent if loading fails.

  What to load, and in what order, comes from `clojure.tools.namespace`:
  it reads the `ns` forms, so a changed namespace is reloaded together
  with everything that depends on it. Reloading is long-settled ground in
  Clojure and there is nothing here worth reinventing."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.namespace.dir :as ns-dir]
    [clojure.tools.namespace.file :as ns-file]
    [clojure.tools.namespace.track :as ns-track]
    [dev.socket :as socket]))

;; --- change detection: poll modified times under the watched directories ---
;; A real application would use java.nio's WatchService; polling behaves the
;; same on every OS. Assets are watched as well: editing a .css or .js file
;; reloads the browser without loading anything into the JVM.

(def ^:private source-dirs
  "Directories holding Clojure source, scanned by the dependency tracker."
  ["src" "dev"])

(defn- watched?
  "Only source and asset files are watched. All others are ignored."
  [path]
  (let [n (.getName (io/file path))]
    (and (not (str/starts-with? n "."))
         (or (str/ends-with? n ".clj")
             (str/ends-with? n ".css")
             (str/ends-with? n ".js")))))

(defn- modified-times
  "Maps each file under the watched directories to its last-modified
  time. Change detection compares two of these maps, so this stats
  everything and leaves filtering to the diff via `watched?`."
  []
  (into {}
    (comp (mapcat #(file-seq (io/file %)))
          (filter #(.isFile ^java.io.File %))
          (map (juxt #(.getPath ^java.io.File %)
                     #(.lastModified ^java.io.File %))))
    ["src" "dev" "resources" "static"]))

;; --- loading: the tracker decides what to load, and in what order ---

(defonce ^{:doc "The dependency tracker: which namespaces exist, what each
  one requires, and which are still pending a load. Defined with `defonce`
  so reloading this file keeps the tracker rather than presenting the whole
  source tree as new."}
  tracker
  (atom (ns-track/tracker)))

(defn- project-path
  "Converts a file reported by the tracker, which is absolute, to a path
  relative to the project root. Everything downstream, including the source
  tags written into the page, speaks project-relative paths."
  [^java.io.File f]
  (str (.relativize (.toPath (io/file (System/getProperty "user.dir")))
                    (.toPath f))))

(defn- load-one!
  "Loads the file behind one namespace. Returns true on success. A failure
  is reported and stops the batch, since reloading the browser onto
  half-loaded code would look healthy while showing stale output."
  [path]
  (try
    (load-file path)
    (println "reloaded" path)
    true
    (catch Throwable e
      (println "reload FAILED" path "—" (.getMessage e))
      false)))

(defn- reload-clojure!
  "Scans the source directories and loads every namespace that changed,
  together with everything that depends on it, in dependency order.
  Returns true when the whole batch loaded.

  The tracker's pending list is drained only for namespaces that actually
  loaded, so a file that fails stays pending and is retried on the next
  scan instead of being forgotten until someone touches it again."
  []
  (if-let [t (try
               (ns-dir/scan-dirs @tracker source-dirs)
               (catch Throwable e
                 (println "scan FAILED —" (.getMessage e))
                 nil))]
    (let [paths (into {}
                  (map (fn [[f n]] [n (project-path f)]))
                  (::ns-file/filemap t))
          [loaded ok?] (reduce (fn [[loaded _] ns-sym]
                                 (if-let [path (paths ns-sym)]
                                   (if (load-one! path)
                                     [(conj loaded ns-sym) true]
                                     (reduced [loaded false]))
                                   [(conj loaded ns-sym) true]))
                               [[] true]
                               (::ns-track/load t))]
      (reset! tracker (update t ::ns-track/load #(drop (count loaded) %)))
      ok?)
    false))

(defn- prime-tracker!
  "Records the current source tree as already loaded. Everything is in the
  REPL by the time the watcher starts, so only later changes should reload."
  []
  (reset! tracker (-> (ns-dir/scan-dirs (ns-track/tracker) source-dirs)
                      (assoc ::ns-track/load () ::ns-track/unload ()))))

(defonce ^{:doc "Holds the polling thread once started, or `nil` beforehand.
  Combined with the `when-not` in `start-watcher!`, `defonce` prevents
  reloading this file from starting a second thread over the same
  directories."}
  watcher
  (atom nil))

(defn start-watcher!
  "Starts the polling thread at most once. Every 200 ms it diffs file
  modification times; anything changed under the source directories is
  loaded in dependency order, and the browsers are nudged once for the
  whole batch, but only if every load succeeds. The thread is a daemon, so
  it never keeps the JVM alive."
  []
  (when-not @watcher
    (prime-tracker!)
    (reset! watcher
      (doto (Thread.
              (fn []
                (loop [seen (modified-times)]
                  (Thread/sleep 200)
                  (let [current (modified-times)
                        changed (for [[path t] current
                                      :when (and (not= t (get seen path))
                                                 (watched? path))]
                                  path)]
                    (when (seq changed)
                      ;; Clojure goes through the tracker, which loads in
                      ;; dependency order; assets need no load at all.
                      (when (if (some #(str/ends-with? % ".clj") changed)
                              (reload-clojure!)
                              true)
                        (socket/notify-reload!)))
                    (recur current)))))
        (.setDaemon true)
        (.start)))
    (println "watching src/ + dev/ + resources/ + static/")))
