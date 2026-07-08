(ns demo.views
  "The recipe site's pages: the recipe card, the featured card, the filter
  bar, the header, the page (plain Hiccup composed from the UI kit in
  demo.ui.views) and `layout`, which wraps a page into an HTML document."
  (:require
    [clojure.java.io :as io]
    [demo.ui.views :as ui]
    [hiccup2.core :as h]))

(defn recipe-card
  "A recipe as a card: photo, name (with a NEW badge, and a spicy badge for
  hot ones), description, rating and difficulty, tag pills, time and servings."
  [{:keys [name description tags minutes serves level stars new?] :as r}]
  [:article.card
   (ui/photo r)
   [:div.card-body
    [:h2 name
     (when new?
       [:span.badge "NEW"])
     (when-let [t (some #{"spicy"} tags)]
       [:span.badge.hot "🌶 " t])]
    [:p description]
    [:div.meta
     (ui/rating stars)
     (ui/difficulty level)]
    [:ul.tags
     (for [t tags]
       [:li (ui/tag-pill t false)])]
    [:footer
     [:span.when (ui/icon :clock) (str minutes " min")]
     [:span.serves (ui/icon :people) (str "serves " serves)]]]])

(defn featured
  "Recipe of the day: a recipe card under a label, shown above the grid."
  [r]
  [:section.featured
   [:div.featured-label "recipe of the day"]
   (recipe-card r)])

(defn filter-bar
  "The tag filter: a pill per tag, plus `all`. Plain GET links. `active`
  is the tag from `?tag=…` (or nil), and its pill is marked active."
  [tags active]
  [:nav.filters
   [:a.pill {:href "/" :class (when (nil? active) "active")} "all"]
   (for [t tags]
     (ui/tag-pill t (= t active)))])

(defn site-header
  "Logo and top navigation."
  []
  [:header.site
   [:div.logo (ui/icon :flame) "Recipe" [:b "Book"]]
   [:nav
    [:a {:href "/"} "recipes"]
    [:a {:href "/#about"} "about"]]])

(defn page
  "The site's one page: header, hero with stats, the featured recipe, the
  filter bar, and the grid of recipe cards. `active-tag` (or nil) narrows
  the grid to the recipes carrying that tag."
  [recipes active-tag]
  (let [tags (->> recipes (mapcat :tags) distinct sort)
        shown (if active-tag
                (filter #(some #{active-tag} (:tags %)) recipes)
                recipes)]
    [:main
     (site-header)
     [:section.hero
      [:h1 "What are we cooking today?"]
      [:dl.stats
       (ui/stat "recipes" (count recipes))
       (ui/stat "cooks" 7)
       (ui/stat "avg time" (str (quot (reduce + (map :minutes recipes))
                                      (count recipes))
                                " min"))]]
     (featured (first (filter :new? recipes)))
     (filter-bar tags active-tag)
     [:section.cards
      (for [r shown]
        (recipe-card r))]
     [:footer.site
      [:p "Plain hiccup, one webserver, no framework."]]]))

(def ^:dynamic *render-boundary*
  "Every page's hiccup passes through this fn on its way to the stringifier —
  the app's one seam. Identity here; dev tooling rebinds it per request."
  identity)

(defn layout
  "Wraps a page's Hiccup in the full HTML document (head, inline
  stylesheet, body) and renders it to a string."
  [body]
  (str
    (h/html
      (h/raw "<!DOCTYPE html>")
      [:html
       [:head
        [:meta {:charset "utf-8"}]
        [:title "Recipe Book"]
        [:style (h/raw (slurp (io/resource "style.css")))]]
       [:body (*render-boundary* body)]])))
