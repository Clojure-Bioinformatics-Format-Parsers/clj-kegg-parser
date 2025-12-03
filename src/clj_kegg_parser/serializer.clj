(ns clj-kegg-parser.serializer
  "Serializer for converting KEGG maps back to KEGG flat-file format.
   
   The KEGG flat-file format uses:
   - 12-character label column (left-aligned, space-padded)
   - Content starting at column 13
   - Line width typically 80 characters
   - Continuation lines have blank label column
   - Special blocks (KCF, sequences) have format-specific rules
   
   This serializer provides:
   - kegg-map->text multimethod dispatched on entry type
   - Helper functions for label padding, line wrapping, field emission
   - Support for nested maps (e.g., REFERENCE sub-fields)
   - Tolerance for both keyword and string map keys
   
   References:
   - https://www.genome.jp/kegg/document/ (various entry formats)
   
   TODO: Implement full KCF/ATOM/BOND block formatting
   TODO: Add bespoke handling for AASEQ/NTSEQ sequence wrapping"
  (:require [clojure.string :as str]
            [clj-kegg-parser.field-registry :as registry]))

;; ---------------------------------------------------------------------------
;; Configuration Constants
;; ---------------------------------------------------------------------------

(def ^:dynamic *label-width*
  "Width of the label column in KEGG flat-files. Default 12."
  12)

(def ^:dynamic *line-width*
  "Maximum line width for wrapped content. Default 80."
  80)

(def ^:dynamic *sub-field-indent*
  "Indentation prefix for sub-fields (e.g., in REFERENCE blocks)."
  "  ")

(defn content-width
  "Returns available width for content (line-width minus label-width).
   Uses current values of dynamic vars."
  []
  (- *line-width* *label-width*))

;; ---------------------------------------------------------------------------
;; Special Field Sets
;; ---------------------------------------------------------------------------

(def mol-block-fields
  "Fields that contain MOL-format chemical structure data."
  #{:atom :bond :bracket})

(def sequence-fields
  "Fields that contain sequence data requiring special wrapping."
  #{:aaseq :ntseq})

;; ---------------------------------------------------------------------------
;; Utility Functions
;; ---------------------------------------------------------------------------

(defn normalize-key
  "Normalizes a map key to uppercase string for KEGG output.
   Handles both keyword and string keys."
  [k]
  (-> (if (keyword? k) (name k) (str k))
      (str/replace "-" "_")
      (str/upper-case)))

(defn key->keyword
  "Normalizes a key to lowercase keyword for lookups.
   Handles both string and keyword inputs."
  [k]
  (if (keyword? k)
    k
    (-> (str k)
        (str/lower-case)
        (str/replace "_" "-")
        (keyword))))

(defn pad-label
  "Pads a label string to the label width with trailing spaces.
   If label is longer than width, truncates it."
  ([label] (pad-label label *label-width*))
  ([label width]
   (let [s (str label)]
     (if (>= (count s) width)
       (subs s 0 width)
       (str s (apply str (repeat (- width (count s)) " ")))))))

(defn blank-label
  "Returns a blank label (spaces only) of the standard width."
  ([] (blank-label *label-width*))
  ([width] (apply str (repeat width " "))))

;; ---------------------------------------------------------------------------
;; Line Wrapping
;; ---------------------------------------------------------------------------

(defn wrap-text
  "Wraps text to fit within content width, returning a sequence of lines.
   Each line will be at most `width` characters.
   
   Parameters:
   - text: String to wrap
   - width: Maximum width per line (default (content-width))
   
   Returns a sequence of strings, each representing one line of output."
  ([text] (wrap-text text (content-width)))
  ([text width]
   (if (str/blank? text)
     [""]
     (let [words (str/split text #"\s+")]
       (loop [remaining words
              current-line ""
              lines []]
         (if (empty? remaining)
           (if (str/blank? current-line)
             lines
             (conj lines current-line))
           (let [word (first remaining)
                 new-line (if (str/blank? current-line)
                            word
                            (str current-line " " word))]
             (if (<= (count new-line) width)
               (recur (rest remaining) new-line lines)
               (if (str/blank? current-line)
                 ;; Word itself is longer than width, force it onto line
                 (recur (rest remaining) "" (conj lines word))
                 ;; Start new line with current word
                 (recur remaining "" (conj lines current-line)))))))))))

;; ---------------------------------------------------------------------------
;; Field Emission
;; ---------------------------------------------------------------------------

;; Forward declaration for emit-nested-map
(declare emit-nested-map)

(defn emit-field-lines
  "Emits a single field as KEGG-formatted lines.
   
   Parameters:
   - label: Field label (will be uppercased and padded)
   - value: Field value (string, seq, or map)
   
   Returns a sequence of formatted lines."
  [label value]
  (let [label-str (-> label normalize-key pad-label)]
    (cond
      ;; Nil or empty - skip
      (or (nil? value)
          (and (coll? value) (empty? value))
          (and (string? value) (str/blank? value)))
      []
      
      ;; Single string value - wrap if needed
      (string? value)
      (let [lines (wrap-text value)]
        (map-indexed
         (fn [idx line]
           (if (zero? idx)
             (str label-str line)
             (str (blank-label) line)))
         lines))
      
      ;; Sequence of values - one per line (or wrapped)
      (sequential? value)
      (let [items (map str value)]
        (mapcat
         (fn [idx item]
           (let [lines (wrap-text item)]
             (map-indexed
              (fn [line-idx line]
                (if (and (zero? idx) (zero? line-idx))
                  (str label-str line)
                  (str (blank-label) line)))
              lines)))
         (range) items))
      
      ;; Map value (e.g., REFERENCE with sub-fields) - emit nested
      (map? value)
      (emit-nested-map label value)
      
      ;; Fallback - convert to string
      :else
      [(str label-str (str value))])))

(defn emit-nested-map
  "Emits a nested map field (like REFERENCE blocks).
   The parent label appears first, then sub-fields are indented."
  [label value-map]
  (let [label-str (-> label normalize-key pad-label)]
    (concat
     ;; Parent label with first sub-field or empty
     [(str label-str "")]
     ;; Sub-fields with blank label prefix
     (mapcat
      (fn [[k v]]
        (let [sub-label (str *sub-field-indent* (normalize-key k))]
          (emit-field-lines sub-label v)))
      value-map))))

;; Forward declaration for emit-single-reference
(declare emit-single-reference)

(defn emit-reference
  "Special handler for REFERENCE fields which have structured sub-content.
   Each reference is a map with :authors, :title, :journal, etc.
   
   REFERENCE blocks in KEGG look like:
   REFERENCE   PMID:12345
     AUTHORS   Name A, Name B, ...
     TITLE     The title of the paper...
     JOURNAL   J Name 123:456-789 (2020)"
  [ref-data]
  (if (sequential? ref-data)
    ;; Multiple references
    (mapcat emit-single-reference ref-data)
    ;; Single reference (as map)
    (emit-single-reference ref-data)))

(defn emit-single-reference
  "Emits a single REFERENCE block."
  [ref-map]
  (let [header-line (str (pad-label "REFERENCE")
                         (or (:pmid ref-map) 
                             (:pubmed ref-map)
                             ""))]
    (concat
     [header-line]
     (when-let [authors (:authors ref-map)]
       (emit-field-lines (str *sub-field-indent* "AUTHORS") authors))
     (when-let [title (:title ref-map)]
       (emit-field-lines (str *sub-field-indent* "TITLE") title))
     (when-let [journal (:journal ref-map)]
       (emit-field-lines (str *sub-field-indent* "JOURNAL") journal)))))

(def ^:dynamic *sequence-line-width*
  "Width for sequence lines (AASEQ/NTSEQ). KEGG uses 60 characters."
  60)

(defn emit-sequence
  "Emits sequence data (AASEQ/NTSEQ) with proper KEGG formatting.
   First line shows sequence length, subsequent lines wrap at 60 chars.
   
   KEGG sequence format example:
   AASEQ       123
               MVLSPADKTNVKAAWGKVGAHAGEYGAEALERMFLSFPTTKTYFPHFDLSH
               GSAQVKGHGKKVADALTNAVAHVDDMPNALSALSDLHAHKLRVDPVNFKLL"
  [label sequence-str]
  (let [label-str (-> label normalize-key pad-label)
        seq-clean (str/replace (str sequence-str) #"\s+" "")
        length (count seq-clean)
        ;; Split sequence into lines of *sequence-line-width* chars
        seq-lines (if (empty? seq-clean)
                    []
                    (->> seq-clean
                         (partition-all *sequence-line-width*)
                         (map str/join)))]
    (concat
     [(str label-str length)]
     (map #(str (blank-label) %) seq-lines))))

(defn emit-mol-block
  "Emits MOL-format block fields (ATOM/BOND/BRACKET).
   These are used in COMPOUND and DRUG entries for chemical structure.
   
   MOL format is a standard chemical file format that requires
   specific column-aligned formatting."
  [label mol-data]
  (let [label-str (-> label normalize-key pad-label)]
    (if (string? mol-data)
      ;; If already a formatted string, emit as-is with proper labeling
      (let [lines (str/split-lines mol-data)]
        (map-indexed
         (fn [idx line]
           (if (zero? idx)
             (str label-str line)
             (str (blank-label) line)))
         lines))
      ;; If structured data, emit field-by-field
      (emit-field-lines label (str mol-data)))))

(defn emit-kcf-block
  "Emits KCF (KEGG Chemical Function) format block for GLYCAN entries.
   KCF format describes carbohydrate structures with specific syntax.
   
   Example KCF format:
   NODE        3
               1 Glc a1
               2 Gal b1
               3 Man a1"
  [kcf-data]
  (let [label-str (pad-label "KCF")]
    (if (string? kcf-data)
      ;; If already a formatted string, emit with proper labeling
      (let [lines (str/split-lines kcf-data)]
        (map-indexed
         (fn [idx line]
           (if (zero? idx)
             (str label-str line)
             (str (blank-label) line)))
         lines))
      ;; Otherwise emit as field
      (emit-field-lines "KCF" (str kcf-data)))))

(defn emit-hierarchy
  "Emits BRITE hierarchy with proper tree indentation.
   BRITE hierarchies use letter prefixes (A, B, C, D, E) for levels."
  [hierarchy-data]
  (let [label-str (pad-label "HIERARCHY")]
    (if (string? hierarchy-data)
      (let [lines (str/split-lines hierarchy-data)]
        (map-indexed
         (fn [idx line]
           (if (zero? idx)
             (str label-str line)
             (str (blank-label) line)))
         lines))
      (emit-field-lines "HIERARCHY" (str hierarchy-data)))))

;; ---------------------------------------------------------------------------
;; Entry Emission
;; ---------------------------------------------------------------------------

(defn emit-entry
  "Emits an entire KEGG entry map as KEGG flat-file format lines.
   
   Parameters:
   - entry-map: Map representing a KEGG entry
   - entry-type: Type of entry (:pathway, :compound, etc.) for field ordering
   
   Returns a sequence of formatted lines including terminal ///."
  [entry-map entry-type]
  (let [fields (or (registry/get-field-order entry-type)
                   (keys entry-map))
        get-val (fn [k]
                  (or (get entry-map k)
                      (get entry-map (key->keyword k))
                      (get entry-map (normalize-key k))))]
    (concat
     (mapcat
      (fn [field]
        (let [value (get-val field)]
          (cond
            ;; Skip nil/empty values
            (nil? value) []
            
            ;; Special handling for references
            (= field :reference)
            (emit-reference value)
            
            ;; Special handling for sequences (AASEQ/NTSEQ)
            (sequence-fields field)
            (emit-sequence field value)
            
            ;; Special handling for MOL blocks (ATOM/BOND/BRACKET)
            (mol-block-fields field)
            (emit-mol-block field value)
            
            ;; Special handling for KCF blocks (GLYCAN)
            (= field :kcf)
            (emit-kcf-block value)
            
            ;; Special handling for BRITE hierarchy
            (= field :hierarchy)
            (emit-hierarchy value)
            
            ;; Standard field emission
            :else
            (emit-field-lines field value))))
      fields)
     ;; Entry terminator
     ["///"])))

;; ---------------------------------------------------------------------------
;; Main Serialization Interface
;; ---------------------------------------------------------------------------

(defmulti kegg-map->text
  "Converts a KEGG entry map to KEGG flat-file text format.
   
   Dispatch is based on :entry-type key in the map.
   Falls back to :default for unknown types.
   
   Parameters:
   - entry-map: Map with KEGG entry data and :entry-type key
   
   Returns: String in KEGG flat-file format"
  (fn [entry-map]
    (or (:entry-type entry-map)
        (get entry-map "entry-type")
        :default)))

(defmethod kegg-map->text :default
  [entry-map]
  (let [entry-type (or (:entry-type entry-map)
                       (get entry-map "entry-type")
                       :unknown)]
    (->> (emit-entry entry-map entry-type)
         (str/join "\n"))))

;; ---------------------------------------------------------------------------
;; Type-specific Serialization Methods
;; ---------------------------------------------------------------------------
;; Each KEGG entry type has specific field orders and formatting requirements.
;; See: https://www.genome.jp/kegg/kegg3a.html through kegg7.html

;; PATHWAY format: https://www.genome.jp/kegg/kegg3a.html
;; Fields: ENTRY, NAME, DESCRIPTION, CLASS, PATHWAY_MAP, MODULE, DISEASE, DRUG,
;;         ORGANISM, GENE, COMPOUND, REL_PATHWAY, KO_PATHWAY, REFERENCE, DBLINKS
(defmethod kegg-map->text :pathway
  [entry-map]
  (->> (emit-entry entry-map :pathway)
       (str/join "\n")))

;; BRITE format: https://www.genome.jp/kegg/kegg3b.html
;; Fields: ENTRY, NAME, DESCRIPTION, HIERARCHY, REFERENCE, DBLINKS
;; Note: HIERARCHY is a tree structure with specific indentation
(defmethod kegg-map->text :brite
  [entry-map]
  (->> (emit-entry entry-map :brite)
       (str/join "\n")))

;; MODULE format: https://www.genome.jp/kegg/kegg3c.html  
;; Fields: ENTRY, NAME, DEFINITION, ORTHOLOGY, CLASS, PATHWAY, REACTION,
;;         COMPOUND, COMMENT, REFERENCE, DBLINKS
(defmethod kegg-map->text :module
  [entry-map]
  (->> (emit-entry entry-map :module)
       (str/join "\n")))

;; KO (KEGG Orthology) format: https://www.genome.jp/kegg/kegg4.html
;; Fields: ENTRY, NAME, DEFINITION, PATHWAY, MODULE, BRITE, DBLINKS, GENES, REFERENCE
(defmethod kegg-map->text :ko
  [entry-map]
  (->> (emit-entry entry-map :ko)
       (str/join "\n")))

;; GENES format: https://www.genome.jp/kegg/kegg4.html
;; Fields: ENTRY, NAME, DEFINITION, ORTHOLOGY, ORGANISM, PATHWAY, MODULE, BRITE,
;;         STRUCTURE, POSITION, MOTIF, DBLINKS, AASEQ, NTSEQ
;; Note: AASEQ/NTSEQ require special sequence formatting (60 chars per line)
(defmethod kegg-map->text :genes
  [entry-map]
  (->> (emit-entry entry-map :genes)
       (str/join "\n")))

;; GENOME format: https://www.genome.jp/kegg/kegg4.html
;; Fields: ENTRY, NAME, DEFINITION, ANNOTATION, TAXONOMY, LINEAGE, DATA_SOURCE,
;;         KEYWORDS, DISEASE, COMMENT, REFERENCE, DBLINKS
(defmethod kegg-map->text :genome
  [entry-map]
  (->> (emit-entry entry-map :genome)
       (str/join "\n")))

;; COMPOUND format: https://www.genome.jp/kegg/kegg5.html
;; Fields: ENTRY, NAME, FORMULA, EXACT_MASS, MOL_WEIGHT, REMARK, COMMENT,
;;         REACTION, PATHWAY, ENZYME, BRITE, DBLINKS, ATOM, BOND, BRACKET
;; Note: ATOM/BOND/BRACKET are MOL-format blocks requiring special handling
(defmethod kegg-map->text :compound
  [entry-map]
  (->> (emit-entry entry-map :compound)
       (str/join "\n")))

;; GLYCAN format: https://www.genome.jp/kegg/kegg5.html
;; Fields: ENTRY, NAME, COMPOSITION, MASS, CLASS, REMARK, COMMENT,
;;         REACTION, PATHWAY, ENZYME, BRITE, DBLINKS, KCF
;; Note: KCF is KEGG Chemical Function format block requiring special handling
(defmethod kegg-map->text :glycan
  [entry-map]
  (->> (emit-entry entry-map :glycan)
       (str/join "\n")))

;; REACTION format: https://www.genome.jp/kegg/kegg5.html
;; Fields: ENTRY, NAME, DEFINITION, EQUATION, COMMENT, RCLASS, ENZYME,
;;         PATHWAY, MODULE, ORTHOLOGY, REFERENCE, DBLINKS
(defmethod kegg-map->text :reaction
  [entry-map]
  (->> (emit-entry entry-map :reaction)
       (str/join "\n")))

;; RCLASS format: https://www.genome.jp/kegg/kegg5.html
;; Fields: ENTRY, DEFINITION, RPAIR, REACTION, ENZYME, PATHWAY, ORTHOLOGY, DBLINKS
(defmethod kegg-map->text :rclass
  [entry-map]
  (->> (emit-entry entry-map :rclass)
       (str/join "\n")))

;; ENZYME format: https://www.genome.jp/kegg/kegg5.html
;; Fields: ENTRY, NAME, CLASS, SYSNAME, REACTION, ALL_REAC, SUBSTRATE, PRODUCT,
;;         COMMENT, HISTORY, PATHWAY, ORTHOLOGY, GENES, REFERENCE, DBLINKS
(defmethod kegg-map->text :enzyme
  [entry-map]
  (->> (emit-entry entry-map :enzyme)
       (str/join "\n")))

;; NETWORK format: https://www.genome.jp/kegg/kegg6.html
;; Fields: ENTRY, NAME, DEFINITION, TYPE, PATHWAY, DISEASE, GENE, PERTURBANT,
;;         CARDINALITY, REFERENCE, DBLINKS
(defmethod kegg-map->text :network
  [entry-map]
  (->> (emit-entry entry-map :network)
       (str/join "\n")))

;; VARIANT format: https://www.genome.jp/kegg/kegg6.html
;; Fields: ENTRY, NAME, GENE, VARIATION, DISEASE, DBLINKS
(defmethod kegg-map->text :variant
  [entry-map]
  (->> (emit-entry entry-map :variant)
       (str/join "\n")))

;; DISEASE format: https://www.genome.jp/kegg/kegg7.html
;; Fields: ENTRY, NAME, DESCRIPTION, CATEGORY, GENE, MARKER, ENV_FACTOR,
;;         CARCINOGEN, PATHOGEN, DRUG, PATHWAY, COMMENT, REFERENCE, DBLINKS
(defmethod kegg-map->text :disease
  [entry-map]
  (->> (emit-entry entry-map :disease)
       (str/join "\n")))

;; DRUG format: https://www.genome.jp/kegg/kegg7.html
;; Fields: ENTRY, NAME, PRODUCT, FORMULA, EXACT_MASS, MOL_WEIGHT, SEQUENCE,
;;         REMARK, CLASS, EFFICACY, TARGET, METABOLISM, INTERACTION, STR_MAP,
;;         OTHER_MAP, SOURCE, COMPONENT, COMMENT, BRITE, DBLINKS, ATOM, BOND
;; Note: ATOM/BOND are MOL-format blocks requiring special handling
(defmethod kegg-map->text :drug
  [entry-map]
  (->> (emit-entry entry-map :drug)
       (str/join "\n")))

;; DGROUP (Drug Group) format: https://www.genome.jp/kegg/kegg7.html
;; Fields: ENTRY, NAME, REMARK, MEMBER, CLASS, COMMENT, DBLINKS
(defmethod kegg-map->text :dgroup
  [entry-map]
  (->> (emit-entry entry-map :dgroup)
       (str/join "\n")))

;; ---------------------------------------------------------------------------
;; Bulk Serialization
;; ---------------------------------------------------------------------------

(defn entries->text
  "Converts a sequence of KEGG entry maps to flat-file text.
   Entries are separated by blank lines."
  [entries]
  (->> entries
       (map kegg-map->text)
       (str/join "\n\n")))

(defn write-kegg-file
  "Writes KEGG entries to a file in flat-file format."
  [filepath entries]
  (spit filepath (entries->text entries)))
