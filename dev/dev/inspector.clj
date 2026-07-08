(ns dev.inspector
  "The source inspector: weld source coordinates onto Hiccup values, then
  carry them into the rendered page.

  The keystone: the default Clojure reader attaches NO :line metadata to
  nested vector literals — but clojure.tools.reader does, and the compiler
  preserves a vector literal's metadata onto the runtime value. So loading a
  view namespace through tools.reader gives every Hiccup element its own
  :line/:column, welded onto the value itself."
  (:require
    [clojure.string :as str]
    [clojure.tools.reader :as tr]
    [clojure.tools.reader.reader-types :as rt]))

(def ^:private html-tags
  "Base names of keywords we treat as real HTML/SVG elements — so tagging can
  never touch a non-Hiccup vector (let bindings, tuples, pull patterns…)."
  #{"a" "abbr" "address" "article" "aside" "audio" "b" "blockquote" "body"
    "br" "button" "canvas" "caption" "cite" "code" "col" "colgroup" "dd"
    "details" "dfn" "dialog" "div" "dl" "dt" "em" "fieldset" "figcaption"
    "figure" "footer" "form" "h1" "h2" "h3" "h4" "h5" "h6" "head" "header"
    "hr" "html" "i" "iframe" "img" "input" "ins" "kbd" "label" "legend" "li"
    "link" "main" "mark" "menu" "meta" "nav" "ol" "optgroup" "option" "output"
    "p" "picture" "pre" "progress" "q" "s" "samp" "script" "section" "select"
    "small" "source" "span" "strong" "style" "sub" "summary" "sup" "table"
    "tbody" "td" "template" "textarea" "tfoot" "th" "thead" "time" "title"
    "tr" "u" "ul" "video"
    ;; SVG
    "svg" "g" "path" "circle" "rect" "line" "polyline" "polygon" "text"
    "ellipse" "defs" "stop" "use" "symbol"})

(defn element?
  "True when x is a Hiccup element vector — an unnamespaced HTML/SVG tag
  keyword head (`.class`/`#id` suffixes allowed)."
  [x]
  (and (vector? x)
       (keyword? (first x))
       (nil? (namespace (first x)))
       (contains? html-tags (first (str/split (name (first x)) #"[.#]")))))

(defn tag-hiccup
  "Add source attributes to a Hiccup element vector, else return it
  unchanged. An already-tagged element is left alone, so the innermost
  component's location wins."
  [h src nm]
  (if (and (element? h)
           (not (and (map? (second h)) (contains? (second h) :data-src))))
    (let [has-attrs? (map? (second h))
          attrs (if has-attrs? (second h) {})
          children (subvec h (if has-attrs? 2 1))]
      (into [(first h) (assoc attrs :data-src src :data-name nm)] children))
    h))

(defn instrument-var!
  "Wrap a view fn so the root element it returns carries the var's source
  location (data-src = the defn site) and name (data-name = ns/fn).
  Idempotent — unwraps to the original before re-wrapping on a reload."
  [v]
  (let [cur @v]
    (when (fn? cur)
      (let [orig (or (::orig (meta cur)) cur)
            m (meta v)
            src (str (:file m) ":" (:line m) ":" (or (:column m) 1))
            nm (str (ns-name (:ns m)) "/" (:name m))
            wrapped (with-meta
                      (fn [& args] (tag-hiccup (apply orig args) src nm))
                      {::orig orig})]
        (alter-var-root v (constantly wrapped))))))

(defn instrument-ns!
  "Source-tag every fn the namespace defines. Wrapping a fn that returns
  non-Hiccup is safe — tag-hiccup passes anything else through — so we can
  blanket-instrument the whole namespace without picking functions."
  [ns-sym]
  (doseq [[_ v] (ns-interns ns-sym)
          :when (and (var? v) (fn? @v))]
    (instrument-var! v)))

(defn tag-tree
  "Walk an assembled Hiccup tree just before it becomes HTML, turning each
  element's source metadata into real attributes: data-src \"file:line:col\"
  and data-name (the bare tag)."
  [node]
  (cond
    (vector? node)
    (let [m (meta node)
          children (mapv tag-tree node)]
      (if (and (:line m) (:file m) (element? node))
        (let [has-attrs? (map? (second children))
              attrs (if has-attrs? (second children) {})
              body (subvec children (if has-attrs? 2 1))]
          (into [(first children)
                 (assoc attrs
                   :data-src (str (:file m) ":" (:line m) ":" (or (:column m) 1))
                   :data-name (first (str/split (name (first node)) #"[.#]")))]
                body))
        children))

    (seq? node) (doall (map tag-tree node))

    :else node))

;; --- the reverse direction: editor cursor -> on-screen element ---
;; The same tools.reader pass that powers the forward tags also builds a
;; span index. resolve-cursor produces the SAME strings the DOM carries
;; (data-name / data-src), so the browser match is an attribute selector.

(defonce ^{:doc "file -> the spans tools.reader saw in it: each view defn,
  and every element literal inside one. Written by index-ns! on every
  tr-load!, read by resolve-cursor. defonce, so reloading this namespace
  leaves the index standing until the next load refills it."}
  view-index
  (atom {}))

(defn- form-span
  "The [line column end-line end-column] tools.reader put on form, or nil
  when it has no position — anything the default reader produced, or a form
  a macro built rather than read."
  [form]
  (let [{:keys [line column end-line end-column]} (meta form)]
    (when (and line column end-line end-column)
      [line column end-line end-column])))

(defn- src-key
  "The \"file:line:col\" string the DOM carries as data-src. Both directions
  build their keys here, which is why a cursor lookup can be a plain
  attribute selector in the browser."
  [file [line column]]
  (str file ":" line ":" (or column 1)))

(defn- defn-form?
  "True for a top-level (defn name …) or (defn- name …). Matched on the head
  symbol's name, so it reads the source as text — the namespace need not be
  loaded, and an aliased or shadowed defn is not mistaken for one."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (contains? #{"defn" "defn-"} (name (first form)))
       (symbol? (second form))))

(defn- collect-elements
  "Depth-first {:key :span} for every element literal in form."
  [file form]
  (cond
    (and (vector? form) (element? form) (form-span form))
    (cons {:key (src-key file (form-span form)) :span (form-span form)}
          (mapcat #(collect-elements file %) form))
    (coll? form) (mapcat #(collect-elements file %) form)
    :else nil))

(defn index-ns!
  "Build the reverse index for file from its read top-level forms: each
  defn's span, and every element literal's span inside one."
  [file ns-sym forms]
  (let [defns (filter defn-form? forms)]
    (swap! view-index assoc file
      {:defns (vec (keep (fn [f]
                           (when-let [span (form-span f)]
                             {:name (str ns-sym "/" (second f)) :span span}))
                         defns))
       :elements (vec (mapcat #(collect-elements file %) defns))})))

(defn- contains-pos?
  "True when line/col lies in span — inclusive start, exclusive end
  (tools.reader's :end-column is one past the last character)."
  [[l c el ec] line col]
  (and (or (< l line) (and (= l line) (<= c col)))
       (or (< line el) (and (= line el) (< col ec)))))

(defn- innermost
  "The tightest item whose span contains line/col, or nil. Sorted by span
  size, so a nested element wins over the one enclosing it and the cursor
  resolves to what it is actually sitting in."
  [items line col]
  (->> items
       (filter #(contains-pos? (:span %) line col))
       (sort-by (fn [{[l c el ec] :span}] [(- el l) (- ec c)]))
       first))

(defn resolve-cursor
  "Map an editor cursor to the strings the DOM carries: the enclosing defn
  (:component matches data-name) and the innermost element literal under
  the cursor (:element matches data-src). nil outside any view defn."
  [file line col]
  (when-let [{:keys [defns elements]} (get @view-index file)]
    (when-let [d (innermost defns line col)]
      {:component (:name d)
       :element (:key (innermost elements line col))})))

(defn tr-load!
  "load-file, except every Hiccup element literal keeps its source position.

  Reads with tools.reader's indexing reader, told the file's name, so every
  form it reads carries :file beside :line/:column; then evals. The ns form
  is evaluated FIRST, before the body is read — tools.reader resolves
  ::aliased keywords at read time, so the namespace and its aliases have to
  exist."
  [path]
  (let [file  (str/replace path #"^src/" "")
        rdr   (rt/indexing-push-back-reader (slurp path) 1 file)
        eof   (Object.)
        read1 #(tr/read {:eof eof} rdr)]
    (binding [*ns* *ns*, *file* file]
      (eval (read1))   ; the ns form FIRST — ::aliases resolve at read time
      (let [body (loop [acc []]
                   (let [form (read1)]
                     (if (identical? form eof) acc (recur (conj acc form)))))]
        (doseq [form body]
          (eval form))
        (instrument-ns! (ns-name *ns*))
        (index-ns! file (ns-name *ns*) body)))))
