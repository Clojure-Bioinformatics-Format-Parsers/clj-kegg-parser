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

(defn emit-sequence
  "Emits sequence data (AASEQ/NTSEQ) with proper formatting.
   First line is count, subsequent lines are wrapped sequence.
   
   TODO: Implement proper sequence line width (typically 60 chars)."
  [label sequence-str]
  (let [label-str (-> label normalize-key pad-label)
        ;; For now, just emit as-is with length on first line
        seq-clean (str/replace (str sequence-str) #"\s+" "")
        length (count seq-clean)]
    [(str label-str length)
     (str (blank-label) seq-clean)]))

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
            
            ;; Special handling for sequences
            (#{:aaseq :ntseq} field)
            (emit-sequence field value)
            
            ;; Special blocks - placeholder for bespoke handling
            (registry/special-block? field)
            (emit-field-lines field (str ";; " (normalize-key field) " block - TODO"))
            
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

;; Type-specific methods can override for custom formatting

(defmethod kegg-map->text :pathway
  [entry-map]
  (->> (emit-entry entry-map :pathway)
       (str/join "\n")))

(defmethod kegg-map->text :compound
  [entry-map]
  ;; TODO: Add special handling for ATOM/BOND blocks
  (->> (emit-entry entry-map :compound)
       (str/join "\n")))

(defmethod kegg-map->text :genes
  [entry-map]
  (->> (emit-entry entry-map :genes)
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
