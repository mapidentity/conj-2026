(ns demo.ui.views
  "The small reusable pieces. Represents a tiny server-rendered UI kit.
  Note what's absent: it doesn't even require hiccup. Components are just
  functions returning data.")

(defn icon
  "Tiny inline SVG icons. No asset pipeline, no network."
  [kind]
  (case kind
    :clock [:svg.icon {:viewBox "0 0 24 24"}
            [:circle {:cx 12 :cy 12 :r 9}]
            [:path {:d "M12 7v5l3 3"}]]
    :people [:svg.icon {:viewBox "0 0 24 24"}
             [:circle {:cx 12 :cy 8 :r 3.5}]
             [:path {:d "M5 20a7 7 0 0 1 14 0"}]]
    :flame [:svg.icon {:viewBox "0 0 24 24"}
            [:path {:d "M12 3c1.5 4.5-4 5.5-4 9.5a4 4 0 0 0 8 0c0-2-1-3.5-1-5.5 2 1 4 3.5 4 5.5"}]]))

(defn star
  "SVG for a star icon.
   When `filled?` is truthy it draws a solid star rather then just outline."
  [filled?]
  [:svg.star {:class (when-not filled? "empty") :viewBox "0 0 24 24"}
   [:path {:d "M12 3l2.7 5.6 6.1.8-4.5 4.3 1.1 6-5.4-2.9-5.4 2.9 1.1-6L3.2 9.4l6.1-.8z"}]])

(defn rating
  "SVG for a rating.
   Draws 5 stars. `n` specifies the rating (0-5),
  shown as amount of filled stars. "
  [n]
  [:div.rating
   (for [i (range 5)]
     (star (< i n)))])

(defn difficulty
  "Component for difficulty.
   `level` is an integer 1-3, translating to easy, medium, hard."
  [level]
  [:div.difficulty {:title (get ["easy" "medium" "hard"] (dec level))}
   (for [i (range 3)]
     [:span.dot {:class (when (< i level) "on")}])])

(defn tag-pill
  "A clickable tag: filters the page. server-side, a plain GET."
  [tag active?]
  [:a.pill {:href (if active? "/" (str "/?tag=" tag))
            :class (when active? "active")}
   tag])

(defn photo
  "A decorative 'photo': gradient plate + the recipe's emoji."
  [{:keys [hue emoji]}]
  [:div.photo {:style (str "background:linear-gradient(135deg,"
                           "hsl(" hue ",75%,88%),hsl(" (+ hue 40) ",65%,78%))")}
   [:span.emoji emoji]])

(defn stat
  "Component for a showing a definition."
  [label value]
  [:div.stat
   [:dt label]
   [:dd value]])
