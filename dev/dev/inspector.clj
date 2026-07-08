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
        (instrument-ns! (ns-name *ns*))))))
