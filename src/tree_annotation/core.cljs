(ns tree-annotation.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [markdown-to-hiccup.core :as md]
            [tree-annotation.database :as db]))


;----------------;
; Node component ;
;----------------;

(defn selection-class [node]
  "Returns a string of CSS helper classes to add to a node's class attribute
   indicating the node's selection status."
  (cond (:selected node) "selected"
        (db/tree-selected? node) "tree-selected"
        true ""))

(defn node-component [node index]
  "Create a component (a button or text field) from a node."
  (if (:renaming node)
    [:input.node
     {:auto-focus true
      :type "text"
      :value (:label node)
      :on-change #(db/rename-node (-> % .-target .-value) index)
      :on-focus #(-> % .-target .select)
      :on-key-down (fn [ev]
                     (when (= (.-key ev) "Enter")
                       (db/stop-renaming-node index)))}]
    [:div.node
     {:class (selection-class node)
      :on-click #(db/toggle-select! index)
      :on-double-click #(db/start-renaming-node index)}
     (:label node)]))

;--------------------------------------;
; Tree component and tree manipulation ;
;--------------------------------------;

(defn tree-component [node index]
  (let [children (:children node)
        length (count children)
        component (node-component node index)]
    [:div.subtree
     (into [:div.forest.children]
           (mapv (fn [child i] (tree-component child (conj index i)))
                 children
                 (range length)))
     component]))

(defn tree-annotation-component []
  "Creates a set of components corresponding to the nodes in the database
and some buttons for interaction."
  [:div
   [:div.content
    [:h2 "Annotation"]
    [:div.pure-button-group.controls
     {:role "group"}
     [:button.pure-button
      {:on-click db/combine-selected} "Combine"]
     [:button.pure-button
      {:on-click db/deselect-all} "Deselect All"]
     [:button.pure-button.button-delete
      {:on-click db/delete-selected} "Delete"]]]
   (into
    [:div.tree.forest]
    (let [forest (db/get-forest)
          length (count forest)]
      (mapv (fn [tree i] (tree-component tree [i]))
            forest
            (range length))))])

;------------------------;
; tree preview component ;
;------------------------;

(def svg-scale 50)

(defn svg-align-subtrees [subtrees]
  "takes a list of subtree elements and returns a new element with aligned subtrees"
  (let [maxh (reduce max (map (comp :h :coords) subtrees))
        [svgs wtotal]
        (reduce (fn [[elts wtotal] {{w :w h :h} :coords subtree :svg}]
                  (let [x wtotal
                        y (inc (- maxh h))
                        svg [:svg
                             {:x (* svg-scale x) :y (* svg-scale y)
                              :style {:overflow "visible"}}
                             subtree]
                        elt {:coords {:x x :y y :w w :h h}
                             :svg svg}
                        elts' (conj elts elt)]
                    [elts' (+ wtotal w)]))
                [[] 0]
                subtrees)]
    {:coords {:w wtotal :h maxh}
     :svg (into [:g] (map :svg svgs))
     :child-coords (mapv :coords svgs)}))

(defn svg-child-line [w h {xc :x wc :w hc :h}]
  [:line {:x1 (* svg-scale (/ (dec w) 2)) :y1 0
          :x2 (* svg-scale (+ xc (/ (dec wc) 2))) :y2 (* svg-scale (- (inc h) hc))
          :stroke "black"}])

(defn svg-label [label x y]
  [:text {:x x :y y
          :text-anchor "middle"
          :dominant-baseline "middle"
          :filter "url(#clear)"}
   label])

(defn svg-subtree [node]
  (let [children (:children node)
        label (:label node)
        subtrees (map svg-subtree children)
        coords (map :coords subtrees)
        {{w :w h :h} :coords children-svg :svg child-coords :child-coords}
        (svg-align-subtrees subtrees)]
    (if (empty? children)
      ;; leaf
      {:coords {:w 1 :h 1}
       :svg (svg-label label 0 0)}
      ;; inner node
      {:coords {:w w :h (inc h)}
       :svg
       [:svg {:style {:overflow "visible"}}
        (into [:g] (map (partial svg-child-line w h) child-coords))
        (svg-label label (* svg-scale (/ (dec w) 2)) 0)
        children-svg]})))

(defn svg-tree-component []
  (let [forest (db/get-forest)
        subtrees (mapv svg-subtree forest)
        {{w :w h :h} :coords trees-svg :svg} (svg-align-subtrees subtrees)
        width (* svg-scale (+ w 2))
        height (* svg-scale (+ h 2))
        svg (into
             [:svg {:width width :height height
                    :viewBox [(- svg-scale) (- svg-scale) width height]
                    :style {:overflow "visible"}}
              [:defs [:filter {:x 0 :y 0 :width 1 :height 1 :id "clear"}
                      [:feFlood {:flood-color "white"}]
                      [:feComposite {:in "SourceGraphic"}]]]]
             trees-svg)]
    [:div#preview.tree
     (when (db/show-preview?)
       svg)]))

;-----------------;
; Input component ;
;-----------------;

(defn load-input-sequence []
  "Create leaf nodes from an input string which is split on spaces."
  (let [labels (str/split (db/get-input-str) #" ")]
    (db/set-leaves labels)
    (db/toggle-input!)))

(defn sequence-input-component []
  [:div
   [:h3 "List of Leaves"]
   [:div.pure-form.pure-g
    [:textarea.pure-input-1
     {:value (db/get-input-str)
      :on-change #(db/set-input-str (-> % .-target .-value))
      :on-key-down (fn [ev]
                     (when (= (.-key ev) "Enter")
                       (load-input-sequence)))}]
    [:div.pure-u-1.pure-u-md-3-4]
    [:button.pure-button.pure-button-primary.pure-u-1.pure-u-md-1-4
     {:on-click load-input-sequence}
     "Load Sequence"]]])

(defn tree-input-component []
  [:div
   [:h3 "QTree String"]
   [:div.pure-form.pure-g
    [:textarea.pure-input-1
     {:value (db/get-input-qtree-str)
      :on-change #(db/set-input-qtree-str (-> % .-target .-value))
      :on-key-down (fn [ev]
                     (when (= (.-key ev) "Enter")
                       (db/load-qtree-string)
                       (db/toggle-input!)
                       false))}]
    [:label.pure-u-1.pure-u-md-1-4.pure-checkbox
     [:input
      {:type "checkbox"
       :checked (db/strip-math?)
       :on-change db/toggle-strip-math!}]
     " strip math"
     ]
    [:div.pure-u-1.pure-u-md-1-2]
    [:button.pure-button.pure-button-primary.pure-u-1.pure-u-md-1-4
     {:on-click #(do (db/load-qtree-string)
                     (db/toggle-input!))}
     "Load QTree String"]]
   [:h3 "JSON"]
   [:div.pure-form.pure-g
    [:textarea.pure-input-1
     {:value (db/get-input-json-str)
      :on-change #(db/set-input-json-str (-> % .-target .-value))
      :on-key-down (fn [ev]
                     (when (= (.-key ev) "Enter")
                       (db/load-json-string)
                       (db/toggle-input!)
                       false))}]
    [:div.pure-u-1.pure-u-md-3-4]
    [:button.pure-button.pure-button-primary.pure-u-1.pure-u-md-1-4
     {:on-click #(do (db/load-json-string)
                     (db/toggle-input!))}
     "Load JSON String"]]
   ])

(defn input-component []
  [:div
   (when (db/show-input?)
     [:div.tab
      ;;[:h2 "Input"]
      [sequence-input-component]
      [tree-input-component]
      ])
   #_[:a {:on-click db/toggle-input! :href "javascript:void(0)"} ; void() is used as a dummy href
       (if (db/show-input?) "Hide Input" "Show Input")]])

;------------------;
; Output component ;
; -----------------;

(defn copy-to-clipboard [str]
  (let [el (js/document.createElement "textarea")]
    (set! (.-value el) str)
    (.appendChild js/document.body el)
    (.select el)
    (.setSelectionRange el 0 99999)
    (js/document.execCommand "copy")
    (.removeChild js/document.body el)))

(def base-url "https://dcmlab.github.io/tree-annotation-code/")

(defn link-output-component []
  (let [out-str-b64 (db/get-output-str-b64)
        href (str base-url "?tree=" out-str-b64)]
    [:div
     [:h3 "Share this Tree"]
     [:a {:href href} "Link to This Tree"]]))

(defn qtree-output-component []
  (let [out-str-qtree (db/get-output-str-qtree)]
    [:div.pure-form.pure-g
     [:h3.pure-u-1 "QTree String"]
     [:label.pure-u-1.pure-u-md-1-4.pure-checkbox
      [:input
       {:type "checkbox"
        :checked (db/math-inner?)
        :on-change db/toggle-math-inner!}]
      " math inner nodes"]
     [:label.pure-u-1.pure-u-md-1-4.pure-checkbox
      [:input
       {:type "checkbox"
        :checked (db/math-leaves?)
        :on-change db/toggle-math-leaves!}]
      " math leaf nodes"]
     [:div.pure-u-1.pure-u-md-1-4]
     [:textarea.pure-input-1.output
      {:value out-str-qtree
       :readOnly "true"}]
     [:div.pure-u-1.pure-u-md-3-4]
     [:button.pure-button.pure-button-primary.pure-u-1.pure-u-md-1-4
      {:on-click #(copy-to-clipboard out-str-qtree)}
      "Copy to Clipboard"]]))

(defn json-output-component []
  (let [out-str-json (db/get-output-str-json)]
    [:div.pure-form.pure-g
     [:h3.pure-u-1 "JSON String"]
     [:label.pure-u-1.pure-u-md-1-4.pure-checkbox
      [:input
       {:type "checkbox"
        :checked (db/pretty-print-json?)
        :on-change db/toggle-pretty-print-json!}]
      " pretty print"]
     [:textarea.pure-input-1.output
      {:value out-str-json
       :readOnly "true"}]
     [:div.pure-u-1.pure-u-md-3-4]
     [:button.pure-button.pure-button-primary.pure-u-1.pure-u-md-1-4
      {:on-click #(copy-to-clipboard out-str-json)}
      "Copy to Clipboard"]]))

(defn output-component []
  [:div
   (when (db/show-output?)
     [:div.tab
      ;;[:h2 "Output"]
      (when (> (count (db/get-forest)) 1)
        [:div.alert "Warning: tree is incomplete!"])
      [link-output-component]
      [qtree-output-component]
      [json-output-component]])
   #_[:button.pure-button {:on-click db/toggle-output!} ; void() is used as a dummy href
       (if (db/show-output?) "Hide Output" "Show Output")]])

;------------------;
; Manual component ;
;------------------;

(def manual-string "

## Manual

by [Daniel Harasim](https://people.epfl.ch/daniel.harasim),
[Christoph Finkensiep](https://people.epfl.ch/christoph.finkensiep),
and the [Digital and Cognitive Musicology Lab (DCML)](https://dcml.epfl.ch)

This is an open source project. Find the code [here](https://github.com/DCMLab/tree-annotation-code).

### Quickstart

1. Write the sequence that you want to annotate with a tree into the text field.
   The elements of the sequence must be separated by space characters.
1. Press the *load sequence* button to load the sequence as leaf nodes of a tree.
1. Select nodes that you want to combine by clicking on them.
   Clicking again deselects a selected node.
   If the node is not a root, the path to the root will be highlighted too, in a different color.
1. Press `Enter` (or click on `Combine`) to combine the selected subtrees into a new tree.
   Only adjacent subtrees can be combined.
   If there are several adjacent groups of trees selected,
   each group will be combined into a new tree.
1. Combine as many more nodes as you like to create the complete tree.
1. The current structure of the tree will be shown in the output field
   as a string usable by the LaTeX package
   [tikz-qtree](http://www.pirbot.com/mirrors/ctan/graphics/pgf/contrib/tikz-qtree/tikz-qtree-manual.pdf).
   The string can also copied to the clipboard by pressing the `Copy` button.
1. Render the tree in a latex document using the *tikz-qtree* package.

###  Additional Functionality

- Double clicking on a node opens a text field to rename that node.
  Submit the new name by pressing `Enter`.
  Pressing `e` or `r` opens a text field for every selected node.
- Pressing `Delete` or `Backspace` (or clicking the `Delete` button)
  deletes all selected nodes and their ancestors.
  Only inner nodes or the last leaf node can be deleted.
- Pressing `Esc` (or clicking the `Deselect All` button) deselects all nodes.
- Pressing `i` or `o` toggles the input or output section, respectively.
  Pressing `m`, `h`, or `?` toggles the manual section.
  Pressing `p` toggles the preview section.
- You can also edit an existing qtree string by loading it 
  using the *load qtree string* button.

")

(defn manual-component []
  [:div
   (when (db/show-manual?)
     [:div.manual.tab
      (md/md->hiccup manual-string)])
   #_[:a {:on-click db/toggle-manual! :href "javascript:void(0)"} ; void() is used as a dummy href
    (if (db/show-manual?) "Hide Manual" "Show Manual")]])

;---------------;
; App component ;
;---------------;

(defn unfocus [action!]
  (fn [ev]
    (.. ev -target blur)
    (action!)))

(defn tab-component []
  [:div.pure-menu.pure-menu-horizontal
   [:ul.pure-menu-list
    [:li.pure-menu-item
     {:class (if (db/show-input?) "pure-menu-selected" "")}
     [:a.pure-menu-link
      {:on-click (unfocus db/toggle-input!) :href "javascript:;"}
      "Input"]]
    [:li.pure-menu-item
     {:class (if (db/show-output?) "pure-menu-selected" "")}
     [:a.pure-menu-link
      {:on-click (unfocus db/toggle-output!) :href "javascript:;"}
      "Output"]]
    [:li.pure-menu-item
     {:class (if (db/show-preview?) "pure-menu-selected" "")}
     [:a.pure-menu-link
      {:on-click (unfocus db/toggle-preview!) :href "javascript:;"}
      "Preview"]][:li.pure-menu-item
     {:class (if (db/show-manual?) "pure-menu-selected" "")}
     [:a.pure-menu-link
      {:on-click (unfocus db/toggle-manual!) :href "javascript:;"}
      "Help"]]]])

(defn app-component []
  [:div
   [:div.content
    [:h1 "Tree Annotation"]
    [tab-component]
    [manual-component]
    [input-component]
    [output-component]]
   [svg-tree-component]
   [tree-annotation-component]
   [:div.bottom-whitespace]])

(defn render []
  (let [params (new js/URLSearchParams. (.. js/window -location -search))
        tree (.get params "tree")]
    (when tree
      (db/load-b64-string tree)
      (db/toggle-preview!)))
  (rdom/render [app-component] (js/document.getElementById "app")))

(render)

;-------------------;
; Onkeypress events ;
;-------------------;

(set! (.-onkeydown js/document)
      (fn [event]
        ;; check whether event was fired on element (e.g. text field)
        ;; or globally (target == document body)
        (when (identical? (.-target event) (.-body js/document))
          (case (.-key event)
            "Enter" (db/combine-selected)
            "Escape" (db/deselect-all)
            "Backspace" (db/delete-selected)
            "Delete" (db/delete-selected)
            "i" (db/toggle-input!)
            "o" (db/toggle-output!)
            "?" (db/toggle-manual!)
            "m" (db/toggle-manual!)
            "h" (db/toggle-manual!)
            "p" (db/toggle-preview!)
            "e" (db/start-renaming-selected)
            "r" (db/start-renaming-selected)
            nil)
          (.preventDefault event)
          (.stopPropagation event)
          false)))
