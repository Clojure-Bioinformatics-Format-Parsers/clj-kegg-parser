(ns clj-kegg-parser.field-registry
  "Machine-readable registry of KEGG flat-file fields.
   
   This registry maps entry types to ordered vectors of field names,
   derived from the official KEGG database help pages for each entry type.
   
   The order of fields follows the canonical KEGG flat-file format, enabling
   lossless round-trip conversions between parsed maps and KEGG text.
   
   References:
   - https://www.genome.jp/kegg/kegg3a.html (PATHWAY)
   - https://www.genome.jp/kegg/kegg3b.html (BRITE)
   - https://www.genome.jp/kegg/kegg3c.html (MODULE)
   - https://www.genome.jp/kegg/kegg4.html  (KO, GENES, GENOME)
   - https://www.genome.jp/kegg/kegg5.html  (COMPOUND, GLYCAN, REACTION, RCLASS, ENZYME)
   - https://www.genome.jp/kegg/kegg6.html  (NETWORK, VARIANT)
   - https://www.genome.jp/kegg/kegg7.html  (DISEASE, DRUG, DGROUP)
   - https://www.genome.jp/kegg/document/help_bget_drug.html
   - https://www.genome.jp/kegg/document/help_bget_compound.html
   
   Note: Some fields (REFERENCE with sub-fields, KCF/ATOM/BOND/BRACKET blocks,
   SEQUENCE/AASEQ/NTSEQ) require special serialization handling beyond simple
   key-value emission. See serializer.clj for format-specific logic.")

;; ---------------------------------------------------------------------------
;; Field Order Registries
;; ---------------------------------------------------------------------------
;; Each entry type maps to a vector of field keywords in canonical order.
;; Keywords use lowercase-kebab-case to match idiomatic Clojure conventions.

(def pathway-fields
  "Fields for PATHWAY entries (map/pathway).
   See: https://www.genome.jp/kegg/kegg3a.html"
  [:entry :name :description :class :pathway-map :module :disease :drug
   :organism :gene :compound :rel-pathway :ko-pathway :reference :dblinks])

(def brite-fields
  "Fields for BRITE hierarchy entries.
   See: https://www.genome.jp/kegg/kegg3b.html"
  [:entry :name :description :hierarchy :reference :dblinks])

(def module-fields
  "Fields for MODULE entries (functional units).
   See: https://www.genome.jp/kegg/kegg3c.html"
  [:entry :name :definition :orthology :class :pathway :reaction
   :compound :comment :reference :dblinks])

(def ko-fields
  "Fields for KO (KEGG Orthology) entries.
   See: https://www.genome.jp/kegg/kegg4.html"
  [:entry :name :definition :pathway :module :brite :dblinks :genes :reference])

(def genes-fields
  "Fields for GENES entries (organism-specific genes).
   See: https://www.genome.jp/kegg/kegg4.html"
  [:entry :name :definition :orthology :organism :pathway :module :brite
   :structure :position :motif :dblinks :aaseq :ntseq])

(def genome-fields
  "Fields for GENOME entries (organisms).
   See: https://www.genome.jp/kegg/kegg4.html"
  [:entry :name :definition :annotation :taxonomy :lineage :data-source
   :keywords :disease :comment :reference :dblinks])

(def compound-fields
  "Fields for COMPOUND entries (chemical substances).
   See: https://www.genome.jp/kegg/kegg5.html
   Note: ATOM/BOND blocks in MOL-format require special handling."
  [:entry :name :formula :exact-mass :mol-weight :remark :comment
   :reaction :pathway :enzyme :brite :dblinks :atom :bond :bracket])

(def glycan-fields
  "Fields for GLYCAN entries (carbohydrates).
   See: https://www.genome.jp/kegg/kegg5.html
   Note: KCF blocks require special handling."
  [:entry :name :composition :mass :class :remark :comment
   :reaction :pathway :enzyme :brite :dblinks :kcf])

(def reaction-fields
  "Fields for REACTION entries (biochemical reactions).
   See: https://www.genome.jp/kegg/kegg5.html"
  [:entry :name :definition :equation :comment :rclass :enzyme
   :pathway :module :orthology :reference :dblinks])

(def rclass-fields
  "Fields for RCLASS entries (reaction class).
   See: https://www.genome.jp/kegg/kegg5.html"
  [:entry :definition :rpair :reaction :enzyme :pathway :orthology :dblinks])

(def enzyme-fields
  "Fields for ENZYME entries (EC numbers).
   See: https://www.genome.jp/kegg/kegg5.html"
  [:entry :name :class :sysname :reaction :all-reac :substrate :product
   :comment :history :pathway :orthology :genes :reference :dblinks])

(def network-fields
  "Fields for NETWORK entries (disease/gene networks).
   See: https://www.genome.jp/kegg/kegg6.html"
  [:entry :name :definition :type :pathway :disease :gene :perturbant
   :cardinality :reference :dblinks])

(def variant-fields
  "Fields for VARIANT entries (disease variants).
   See: https://www.genome.jp/kegg/kegg6.html"
  [:entry :name :gene :variation :disease :dblinks])

(def disease-fields
  "Fields for DISEASE entries.
   See: https://www.genome.jp/kegg/kegg7.html"
  [:entry :name :description :category :gene :marker :env-factor
   :carcinogen :pathogen :drug :pathway :comment :reference :dblinks])

(def drug-fields
  "Fields for DRUG entries.
   See: https://www.genome.jp/kegg/kegg7.html and help_bget_drug.html"
  [:entry :name :product :formula :exact-mass :mol-weight :sequence
   :remark :class :efficacy :target :metabolism :interaction :str-map
   :other-map :source :component :comment :brite :dblinks :atom :bond])

(def dgroup-fields
  "Fields for DGROUP entries (drug groups).
   See: https://www.genome.jp/kegg/kegg7.html"
  [:entry :name :remark :member :class :comment :dblinks])

;; ---------------------------------------------------------------------------
;; Master Registry
;; ---------------------------------------------------------------------------

(def field-registry
  "Master registry mapping entry-type keywords to their field vectors.
   Entry types may be specified as keywords (:pathway, :compound, etc.)."
  {:pathway   pathway-fields
   :brite     brite-fields
   :module    module-fields
   :ko        ko-fields
   :genes     genes-fields
   :genome    genome-fields
   :compound  compound-fields
   :glycan    glycan-fields
   :reaction  reaction-fields
   :rclass    rclass-fields
   :enzyme    enzyme-fields
   :network   network-fields
   :variant   variant-fields
   :disease   disease-fields
   :drug      drug-fields
   :dgroup    dgroup-fields})

;; ---------------------------------------------------------------------------
;; Utility Functions
;; ---------------------------------------------------------------------------

(defn get-field-order
  "Returns the canonical field order for a given entry type.
   Entry type can be a keyword or string (case-insensitive).
   Returns nil if entry type is unknown."
  [entry-type]
  (let [k (if (keyword? entry-type)
            entry-type
            (keyword (clojure.string/lower-case (name entry-type))))]
    (get field-registry k)))

(defn entry-types
  "Returns all known entry types as keywords."
  []
  (keys field-registry))

(defn field-names
  "Returns all unique field names across all entry types."
  []
  (->> (vals field-registry)
       (apply concat)
       (distinct)
       (sort)))

(defn fields-for-type
  "Returns the set of fields for a given entry type.
   Useful for validation and filtering."
  [entry-type]
  (set (get-field-order entry-type)))

;; ---------------------------------------------------------------------------
;; Reference sub-fields
;; ---------------------------------------------------------------------------
;; REFERENCE is a multi-occurrence block with internal sub-fields.
;; These sub-fields appear indented under each REFERENCE line.

(def reference-subfields
  "Sub-fields that appear within REFERENCE blocks.
   Order: AUTHORS, TITLE, JOURNAL, and optionally DOI, PUBMED, SEQUENCE."
  [:authors :title :journal :doi :pubmed :sequence])

;; ---------------------------------------------------------------------------
;; Special blocks requiring bespoke handling
;; ---------------------------------------------------------------------------

(def special-block-fields
  "Fields that require special formatting logic:
   - :atom/:bond/:bracket - MOL-format blocks in COMPOUND/DRUG
   - :kcf - KCF format blocks in GLYCAN  
   - :aaseq/:ntseq - Sequence data with wrap formatting
   - :hierarchy - BRITE hierarchy with tree indentation"
  #{:atom :bond :bracket :kcf :aaseq :ntseq :hierarchy})

(defn special-block?
  "Returns true if the field requires special serialization handling."
  [field-key]
  (contains? special-block-fields field-key))
