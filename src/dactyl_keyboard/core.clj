;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The Dactyl-ManuForm Keyboard — Opposable Thumb Edition              ;;
;; Main Module — CLI, Final Composition and Outputs                    ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(ns dactyl-keyboard.core
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.pprint :refer [pprint]]
            [clojure.java.io :as io]
            [hawk.core :as hawk]
            [clj-yaml.core :as yaml]
            [scad-clj.model :as model]
            [scad-app.core :refer [filter-by-name refine-asset
                                   refine-all build-all]]
            [scad-tarmi.core :refer [π]]
            [scad-tarmi.maybe :as maybe]
            [dactyl-keyboard.misc :refer [output-directory soft-merge]]
            [dactyl-keyboard.param.access :as access]
            [dactyl-keyboard.param.proc.doc :refer [print-markdown-section]]
            [dactyl-keyboard.param.proc.anch :as anch]
            [dactyl-keyboard.cad.body.custom :as custom-body]
            [dactyl-keyboard.cad.body.assembly :as assembly]
            [dactyl-keyboard.cad.body.main :as main-body]
            [dactyl-keyboard.cad.body.central :as central]
            [dactyl-keyboard.cad.body.wrist :as wrist]
            [dactyl-keyboard.cad.bottom :as bottom]
            [dactyl-keyboard.cad.auxf :as auxf]
            [dactyl-keyboard.cad.key :as key]
            [dactyl-keyboard.cad.key.switch :refer [single-cap single-switch]]
            [dactyl-keyboard.cad.mcu :as mcu])
  (:gen-class :main true))

(defn pprint-settings
  "Show settings as assembled from (possibly multiple) files."
  [header settings]
  (println header)
  (if (fn? settings)
    (pprint (settings))  ; Option accessor.
    (pprint settings))   ; Raw data.
  (println))

(defn document-settings
  "Show documentation for settings."
  [{section :describe-parameters}]
  (println "<!--This document was generated and is intended for rendering"
           "to HTML on GitHub. Edit the source files, not this file.-->")
  (println)
  (print-markdown-section
    (case section
      :central dactyl-keyboard.param.tree.central/raws
      :clusters dactyl-keyboard.param.tree.cluster/raws
      :main dactyl-keyboard.param.tree.main/raws
      :nested dactyl-keyboard.param.tree.nested/raws
      :ports dactyl-keyboard.param.tree.port/raws
      :wrist-rest-mounts dactyl-keyboard.param.tree.restmnt/raws
      (do (println "ERROR: Unknown section of parameters.")
          (System/exit 1))))
  (println)
  (println "⸻")
  (println)
  (println "This document was generated from the application CLI."))

(def derivers-static
  "A vector of configuration locations and functions for expanding them."
  ;; Mind the order. One of these may depend upon earlier steps.
  [[[:keys] key/derive-style-properties]
   [[:key-clusters] key/derive-cluster-properties]
   [[:by-key] key/derive-nested-properties]
   [[:central-housing] central/derive-properties]
   [[] (fn [getopt] {:anchors (anch/collect getopt)
                     :bodies (custom-body/collect getopt)})]
   [[:main-body :rear-housing] main-body/rhousing-properties]
   [[:mcu] mcu/derive-properties]
   [[:wrist-rest] wrist/derive-properties]
   [[:flanges] auxf/derive-flange-properties]])

(defn derivers-dynamic
  "Additions for more varied parts of a configuration."
  [getopt]
  (for [i (range (count (getopt :wrist-rest :mounts)))]
       [[:wrist-rest :mounts i] #(wrist/derive-mount-properties % i)]))

(defn enrich-option-metadata
  "Derive certain properties that are implicit in the user configuration.
  Use a gradually expanding but temporary build option accessor.
  Store the results under the “:derived” key in each section."
  [build-options]
  (reduce
    (fn [coll [path callable]]
      (soft-merge
        coll
        (assoc-in coll (conj path :derived)
                       (callable (access/option-accessor coll)))))
    build-options
    (concat derivers-static
            (derivers-dynamic (access/option-accessor build-options)))))

(defn- from-file
  "Parse raw settings out of a YAML file."
  [filepath]
  (try
    (yaml/parse-string (slurp filepath))
    (catch java.io.FileNotFoundException _
      (println (format "Failed to load file “%s”." filepath))
      (System/exit 1))))

(defn- merge-opt-file
  "Merge a single configuration file into a configuration."
  [raws filepath]
  (try
    (soft-merge raws (from-file filepath))
    (catch Exception e
      ;; Most likely a java.lang.ClassCastException or
      ;; java.lang.IllegalArgumentException from a structural problem.
      ;; When such problems do not affect a merge, they are caught on
      ;; parsing or access, i.e. later.
      (println (format "Error while merging options in file “%s”." filepath))
      (throw e))))

(defn- merge-raw-opts
  "Merge all configuration files."
  [filepaths]
  (try
    (reduce merge-opt-file {} filepaths)
    (catch Exception e
      (println
        (format (str "There may be a structural problem in any of "
                     "%s, such as a dictionary (map) in place of a list, "
                     "or vice versa.")
                filepaths))
      (throw e))))

(defn- get-accessor
  "Parse model parameters. Return an accessor for them."
  [{:keys [configuration-file debug]}]
  (let [checkpoint (fn [a b] (when debug (pprint-settings a b)) b)]
    (->>
      (merge-raw-opts configuration-file)
      (checkpoint "Received settings without built-in defaults:")
      (access/checked-configuration)
      (checkpoint "Resolved and validated settings:")
      enrich-option-metadata
      access/option-accessor
      (checkpoint "Enriched settings:"))))

(def module-asset-list
  "OpenSCAD modules and the functions that make them."
  [{:name "housing_adapter_fastener"
    :model-precursor central/build-fastener,
    :chiral true}
   {:name "sprue_negative"
    :model-precursor wrist/sprue-negative}
   {:name "bottom_plate_anchor_positive_nonprojecting"
    :model-precursor bottom/anchor-positive-nonprojecting}
   {:name "bottom_plate_anchor_positive_central"
    :model-precursor bottom/anchor-positive-central}
   {:name "bottom_plate_screw_negative"
    :model-precursor bottom/screw-negative
    :chiral true}
   {:name "bottom_plate_insert_negative"
    :model-precursor bottom/insert-negative}])

(defn module-asset-map
  "Convert module-asset-list to a hash map with fully resolved models.
  Add a variable number of additional modules based on key styles."
  [getopt]
  (merge
    (reduce  ; Static.
      (fn [coll {:keys [name model-precursor] :as asset}]
        (assoc coll name
          (assoc asset :model-main (model-precursor getopt))))
      {}
      module-asset-list)
    (reduce  ; Dynamic.
      (fn [coll key-style]
        (let [prop (getopt :keys :derived key-style)
              {:keys [switch-type module-keycap module-switch]} prop]
          (assoc coll
            module-keycap
            {:name module-keycap
             :model-main (single-cap getopt key-style false)}
            module-switch  ; Uniqueness of input not guaranteed.
            {:name module-switch
             :model-main (single-switch switch-type)})))
      {}
      (keys (getopt :keys :styles)))))

(defn- get-key-modules
  "Produce a sorted vector of module name strings for user-defined key styles."
  [getopt & property-keys]
  (sort
    (into []
      (reduce
        (fn [coll data] (apply (partial conj coll) (map data property-keys)))
        #{}
        (vals (getopt :keys :derived))))))

(defn- conditional-bottom-plate-modules
  [getopt]
  (if (getopt :main-body :bottom-plate :include)
    ["bottom_plate_anchor_positive_nonprojecting",
     "bottom_plate_anchor_positive_central",
     "bottom_plate_screw_negative"
     "bottom_plate_insert_negative"]
    []))

(defn- central-housing-modules
  "A collection of OpenSCAD modules for the central housing."
  [getopt]
  (concat
    [(when (getopt :central-housing :derived :include-adapter)
       "housing_adapter_fastener")]
    (conditional-bottom-plate-modules getopt)))

(defn get-static-precursors
  "Make the central roster of files and the models that go into each.
  The schema used to describe them is a superset of the scad-app
  asset schema, adding dependencies on special configuration values and
  rotation for ease of printing. The models themselves are described with
  unary precursors that take a completed “getopt” function."
  [getopt]
  ;; Hard-coded bodies go into outputs like “body-” + the ID keyword.
  [{:name "body-main"
    ::body :main
    :modules (concat
               [(when (getopt :central-housing :derived :include-adapter)
                  "housing_adapter_fastener")
                (when (getopt :wrist-rest :sprues :include)
                  "sprue_negative")]
               (conditional-bottom-plate-modules getopt)
               (get-key-modules getopt :module-keycap :module-switch))
    :model-precursor assembly/main-body-right
    :chiral (getopt :main-body :reflect)}
   (when (getopt :central-housing :derived :include-main)
     {:name (str "body-central-housing"  ; With conditional suffix.
              (when (getopt :central-housing :derived :include-sections)
                "-full"))
      ::body :central-housing
      :modules (central-housing-modules getopt)
      :model-precursor assembly/central-housing})
   (when (and (getopt :wrist-rest :include)
              (not (= (getopt :wrist-rest :style) :solid)))
     {:name "body-wrist-rest"
      ::body :wrist-rest
      :modules (concat (conditional-bottom-plate-modules getopt)
                       (when (getopt :wrist-rest :sprues :include)
                         ["sprue_negative"]))
      :model-precursor assembly/wrist-rest-plinth-right
      :chiral (getopt :main-body :reflect)})
   ;; Preview-only outputs.
   {:name "preview-keycap-clusters"
    :modules (get-key-modules getopt :module-keycap)
    :model-precursor (partial key/metacluster key/cluster-keycaps)}
   ;; Auxiliary features.
   (when (and (getopt :mcu :include)
              (getopt :mcu :support :lock :include))
     {:name "mcu-lock-bolt"
      :model-precursor mcu/lock-bolt-model
      :rotation [0 π 0]})
   ;; Auxiliary outputs specifically for wrist rests.
   (when (getopt :wrist-rest :include)
     {:name "pad-mould"
      :modules (conditional-bottom-plate-modules getopt)
      :model-precursor assembly/wrist-rest-rubber-casting-mould-right
      :rotation [π 0 0]
      :chiral (getopt :main-body :reflect)})  ; Chirality is possible but not guaranteed.
   (when (getopt :wrist-rest :include)
     {:name "pad-shape"
      :modules (conditional-bottom-plate-modules getopt)
      :model-precursor assembly/wrist-rest-rubber-pad-right
      :chiral (getopt :main-body :reflect)})
   ;; Bottom plate(s):
   (when (and (getopt :main-body :bottom-plate :include)
              (not (and (getopt :main-body :bottom-plate :combine)
                        (getopt :wrist-rest :bottom-plate :include))))
     {:name "bottom-plate-case"
      :modules (conditional-bottom-plate-modules getopt)
      :model-precursor bottom/case-complete
      :rotation [0 π 0]
      :chiral (getopt :main-body :reflect)})
   (when (and (getopt :wrist-rest :include)
              (getopt :wrist-rest :bottom-plate :include)
              (not (and (getopt :main-body :bottom-plate :include)
                        (getopt :main-body :bottom-plate :combine))))
     {:name "bottom-plate-wrist-rest"
      :modules (conditional-bottom-plate-modules getopt)
      :model-precursor bottom/wrist-complete
      :rotation [0 π 0]
      :chiral (getopt :main-body :reflect)})
   (when (and (getopt :main-body :bottom-plate :include)
              (getopt :main-body :bottom-plate :combine)
              (getopt :wrist-rest :include)
              (getopt :wrist-rest :bottom-plate :include))
     {:name "bottom-plate-combined"
      :modules (conditional-bottom-plate-modules getopt)
      :model-precursor bottom/combined-complete
      :rotation [0 π 0]
      :chiral (getopt :main-body :reflect)})])

(defn get-key-style-precursors
  "Collate key-style precursors. No maquettes though; they’re no fun to print."
  [getopt]
  (mapcat
    (fn [key-style]
      (if-not (= (getopt :keys :derived key-style :style) :maquette)
        [{:name (str "keycap-" (name key-style))
          :model-precursor #(single-cap % key-style true)}]))
    (keys (getopt :keys :styles))))

(defn get-dfm-subassemblies
  "Collate model precursors for subassemblies.
  This currently consists of central housing sections only."
  ;; TODO: Subsume this into the more recent custom-body subsystem.
  [getopt]
  (when (getopt :central-housing :derived :include-sections)
    (map-indexed
      (fn [idx [left right]]
        {:name (str "body-central-housing-section-" (inc idx))
         :modules (central-housing-modules getopt)
         :model-precursor
           (fn [getopt]
             (model/rotate [0 (/ π (if (zero? idx) 2 -2)) 0]
               (model/intersection
                 (model/translate [(+ left (/ (- right left) 2)) 0 0]
                   (model/cube (- right left) 1000 1000))
                 (assembly/central-housing getopt))))})
      (->>
        (getopt :dfm :central-housing :sections)
        (concat [-1000 1000])  ; Add left- and right-hand-side bookends.
        (sort)
        (partition 2 1)))))

(defn get-builtin-precursors
  "Add dynamic elements to static precursors."
  [getopt]
  (concat
    (get-static-precursors getopt)
    (get-key-style-precursors getopt)
    (get-dfm-subassemblies getopt)))

(defn- finalize-asset
  "Define scad-app asset(s) from a single proto-asset.
  Return a vector of one or two assets."
  [getopt module-map cli-options
   {:keys [model-precursor rotation modules]
    :or {rotation [0 0 0], modules []}
    :as proto-asset}]
  (refine-asset
    {:original-fn #(str "right-hand-" %),
     :mirrored-fn #(str "left-hand-" %)}
    (conj
      (select-keys proto-asset [:name :chiral ::body])  ; Simplified base.
      [:model-main (maybe/rotate rotation (model-precursor getopt))]
      (when (getopt :resolution :include)
        [:minimum-face-size (getopt :resolution :minimum-face-size)]))
    (map (partial get module-map) (remove nil? modules))))

(defn- builtin-assets
  "All assets prior to the addition and subtraction of custom bodies."
  [getopt]
  (refine-all (remove nil? (get-builtin-precursors getopt))
    {:refine-fn (partial finalize-asset getopt (module-asset-map getopt))}))

(declare customize)

(defn- fork-for-custom-bodies
  "Take a refined asset representing the positive form of a body.
  Return a vector of assets: The complete body with all custom bodies subtracted,
  and one new asset for each of those custom bodies."
  [getopt {:keys [chiral mirrored model-vector] ::keys [body]
           :as parent-asset}]
  (let [[modules parent-base] (map vec (split-at (dec (count model-vector))
                                                 model-vector))
        children (getopt :derived :bodies :parent->children body)]
    (concat
      ;; The modified parent.
      [(assoc parent-asset :model-vector
              (conj modules
                    (custom-body/difference
                      getopt mirrored children parent-base)))]
      ;; Each custom-body child, and its children in turn, if any.
      (mapcat
        (fn [id]
          (customize getopt
            (merge
              parent-asset
              {:name (format (cond (and chiral mirrored) "left-hand-body-%s"
                                   chiral "right-hand-body-%s"
                                   :else "body-%s")
                             (name id))
               ::body id
               :model-vector (conj modules
                                   (custom-body/intersection
                                     getopt mirrored id parent-base))})))
        children))))

(defn- customize
  "Add custom bodies as assets. Subtract them from their parent bodies."
  [getopt {::keys [body] :as parent-asset}]
  (if (contains? (getopt :derived :bodies :parent->children) body)
    ;; The asset describes the positive form of a body.
    (fork-for-custom-bodies getopt parent-asset)
    [parent-asset]))

(defn- output-filepath-fn
  [base suffix]
  "Produce a relative file path for e.g. SCAD or STL."
  (io/file output-directory suffix (str base "." suffix)))

(defn run
  "Build all models, authoring files in parallel. Easily used from a REPL."
  [{:keys [whitelist render renderer] :or {whitelist #""} :as options}]
  (let [getopt (get-accessor options)
        final-assets (mapcat (partial customize getopt)
                             (builtin-assets getopt))]
    (build-all (filter-by-name whitelist final-assets)
               {:render render
                :rendering-program renderer
                :filepath-fn output-filepath-fn})))

(defn watch-config!
  "Build all models every time a configuration file changes.
  Return a nullary function that terminates the watch.
  For REPL use only at this point."
  ;; TODO: Possibly expand to reload altered source code.
  ;; TODO: Componentize and run as an application mode from the CLI.
  [options]
  (let [watcher (hawk/watch! [{:paths ["config"]
                               :filter hawk/file?
                               :handler (fn [ctx e] (run options) ctx)}])]
    #(hawk/stop! watcher)))

(defn execute-mode
  "Act on arguments received from the command line (shell), already parsed.
  If arguments are erroneous, show how, else react to flags for special modes,
  else proceed to the default mode, which is building models.
  Return an appropriate Unix exit code."
  [{:keys [errors summary options]}]
  (let [{:keys [help describe-parameters debug]} options]
    (cond
      (some? errors) (do (println (first errors)) (println summary))
      help (println summary)
      describe-parameters (document-settings options)
      :else (do (run options) (when debug (println "Exiting without error."))))
    (if (some? errors) 1 0)))

(def cli-options
  "Define command-line interface for using the application from the shell."
  [["-c" "--configuration-file PATH" "Path to parameter file in YAML format"
    :default []
    :assoc-fn (fn [m k new] (update-in m [k] (fn [old] (conj old new))))]
   [nil "--describe-parameters SECTION"
    "Print a Markdown document specifying what a configuration file may contain"
    :default nil :parse-fn keyword]
   [nil "--render" "Produce STL in addition to SCAD files"]
   [nil "--renderer PATH" "Path to OpenSCAD" :default "openscad"]
   ["-w" "--whitelist RE"
    "Limit output to files whose names match the regular expression RE"
    :parse-fn re-pattern]
   ["-d" "--debug"]
   ["-h" "--help"]])

(defn -main
  "Parse command-line arguments, act on them and exit the application."
  [& raw]
  (try
    (System/exit (execute-mode (parse-opts raw cli-options)))
    (catch clojure.lang.ExceptionInfo e
      ;; Likely raised by getopt.
      (println "An exception occurred:" (.getMessage e))
      (pprint (ex-data e))
      (System/exit 1))))
