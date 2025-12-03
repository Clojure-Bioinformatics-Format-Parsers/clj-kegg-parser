(ns clj-kegg-parser.extractors
  "Lazy, transducer-friendly extractors for common KEGG data patterns.
   
   These functions are designed to work efficiently with large collections
   of KEGG entries, using lazy sequences and transducers where appropriate.
   
   Common patterns:
   - get-pathway-genes: Extract all genes from pathway entries
   - get-compound-names: Extract compound names/synonyms
   - unique-organism-codes: Extract unique organism codes from genes entries
   
   All extractors tolerate both keyword and string map keys."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Key Access Utilities
;; ---------------------------------------------------------------------------

(defn get-field
  "Gets a field from a map, tolerating keyword or string keys.
   Tries lowercase keyword, uppercase string, and kebab-case variants."
  [m field]
  (let [kw (if (keyword? field) field (keyword (str/lower-case field)))
        kw-kebab (keyword (str/replace (name kw) "_" "-"))
        str-upper (str/upper-case (name kw))
        str-orig (name kw)]
    (or (get m kw)
        (get m kw-kebab)
        (get m str-upper)
        (get m str-orig))))

(defn entry-type
  "Returns the entry type of a KEGG map as a keyword.
   Handles various representations of entry-type field."
  [entry]
  (when-let [et (or (get-field entry :entry-type)
                    (get-field entry "entry-type"))]
    (if (keyword? et)
      et
      (keyword (str/lower-case (str et))))))

;; ---------------------------------------------------------------------------
;; Gene Extractors
;; ---------------------------------------------------------------------------

(defn get-pathway-genes
  "Extracts gene entries from pathway(s).
   
   Parameters:
   - entries: A pathway entry map or sequence of pathway entries
   
   Returns: Lazy sequence of [gene-id description] pairs."
  [entries]
  (let [entries (if (map? entries) [entries] entries)]
    (for [entry entries
          gene (or (get-field entry :gene) [])
          :when gene]
      (if (sequential? gene)
        gene
        [gene nil]))))

(def xf-pathway-genes
  "Transducer for extracting genes from pathway entries.
   Use with transduce or into for efficient processing."
  (comp
   (mapcat #(or (get-field % :gene) []))
   (filter some?)))

(defn genes-by-organism
  "Groups genes by organism code.
   
   Parameters:
   - gene-entries: Sequence of gene entry maps
   
   Returns: Map of organism-code -> [gene-entries]"
  [gene-entries]
  (group-by #(get-field % :organism) gene-entries))

;; ---------------------------------------------------------------------------
;; Compound Extractors
;; ---------------------------------------------------------------------------

(defn get-compound-names
  "Extracts compound names from compound entries.
   Returns all name variants (primary and synonyms).
   
   Parameters:
   - entries: A compound entry map or sequence of compound entries
   
   Returns: Lazy sequence of name strings."
  [entries]
  (let [entries (if (map? entries) [entries] entries)]
    (for [entry entries
          :let [names (get-field entry :name)]
          name (if (sequential? names) names [names])
          :when (and name (not (str/blank? (str name))))]
      (str/trim (str name)))))

(def xf-compound-names
  "Transducer for extracting compound names."
  (comp
   (mapcat #(let [n (get-field % :name)]
              (if (sequential? n) n [n])))
   (filter some?)
   (map str)
   (remove str/blank?)
   (map str/trim)))

(defn compound-formula
  "Extracts the molecular formula from a compound entry."
  [entry]
  (get-field entry :formula))

(defn compound-mass
  "Extracts the molecular weight from a compound entry.
   Returns as number if parseable."
  [entry]
  (when-let [mass (or (get-field entry :mol-weight)
                      (get-field entry :exact-mass))]
    (try
      (Double/parseDouble (str mass))
      (catch Exception _ mass))))

;; ---------------------------------------------------------------------------
;; Organism Extractors
;; ---------------------------------------------------------------------------

(defn organism-code
  "Extracts the organism code from a genes entry.
   The organism code is typically a 3-4 letter code like 'hsa', 'eco'."
  [entry]
  (when-let [org (get-field entry :organism)]
    (if (sequential? org)
      (first org)
      (first (str/split (str org) #"\s+")))))

(defn unique-organism-codes
  "Extracts unique organism codes from a collection of gene entries.
   
   Parameters:
   - entries: Sequence of gene entry maps
   
   Returns: Set of organism code strings."
  [entries]
  (->> entries
       (map organism-code)
       (filter some?)
       (set)))

(def xf-organism-codes
  "Transducer for extracting organism codes from gene entries."
  (comp
   (map organism-code)
   (filter some?)))

;; ---------------------------------------------------------------------------
;; Pathway Extractors
;; ---------------------------------------------------------------------------

(defn pathway-id
  "Extracts the pathway ID from a pathway entry."
  [entry]
  (when-let [e (get-field entry :entry)]
    (if (sequential? e)
      (first e)
      (first (str/split (str e) #"\s+")))))

(defn pathway-compounds
  "Extracts compounds referenced in a pathway entry."
  [entry]
  (let [compounds (get-field entry :compound)]
    (when (seq compounds)
      (if (sequential? compounds)
        compounds
        [compounds]))))

(defn get-pathway-modules
  "Extracts module references from pathway entries.
   
   Parameters:
   - entries: A pathway entry or sequence of pathway entries
   
   Returns: Lazy sequence of [module-id description] pairs."
  [entries]
  (let [entries (if (map? entries) [entries] entries)]
    (for [entry entries
          module (or (get-field entry :module) [])
          :when module]
      (if (sequential? module)
        module
        [module nil]))))

;; ---------------------------------------------------------------------------
;; Reaction Extractors
;; ---------------------------------------------------------------------------

(defn reaction-equation
  "Extracts the equation from a reaction entry."
  [entry]
  (get-field entry :equation))

(defn reaction-enzymes
  "Extracts enzyme EC numbers from a reaction entry."
  [entry]
  (let [enzymes (get-field entry :enzyme)]
    (when (seq enzymes)
      (if (sequential? enzymes)
        enzymes
        (str/split (str enzymes) #"\s+")))))

;; ---------------------------------------------------------------------------
;; DBLINKS Extractors
;; ---------------------------------------------------------------------------

(defn get-dblinks
  "Extracts database cross-references from an entry.
   
   Returns: Sequence of [database ids...] vectors."
  [entry]
  (let [links (get-field entry :dblinks)]
    (when (seq links)
      (if (sequential? links)
        links
        [links]))))

(defn dblink-by-database
  "Gets cross-references for a specific database.
   
   Parameters:
   - entry: A KEGG entry map
   - database: Database name (e.g., 'NCBI-GeneID', 'UniProt')
   
   Returns: Sequence of IDs for that database, or nil."
  [entry database]
  (when-let [links (get-dblinks entry)]
    (let [db-upper (str/upper-case (str database))]
      (some (fn [link]
              (when (sequential? link)
                (when (= (str/upper-case (str (first link))) db-upper)
                  (rest link))))
            links))))

;; ---------------------------------------------------------------------------
;; Generic Extractors
;; ---------------------------------------------------------------------------

(defn filter-by-type
  "Filters entries by entry type.
   
   Parameters:
   - entries: Sequence of KEGG entry maps
   - type-kw: Entry type keyword (e.g., :pathway, :compound)
   
   Returns: Lazy sequence of matching entries."
  [entries type-kw]
  (filter #(= (entry-type %) type-kw) entries))

(def xf-by-type
  "Returns a transducer that filters by entry type."
  (fn [type-kw]
    (filter #(= (entry-type %) type-kw))))

(defn map-entries
  "Maps a function over entries, extracting a specific field.
   
   Parameters:
   - entries: Sequence of KEGG entry maps
   - field: Field keyword to extract
   
   Returns: Lazy sequence of field values (non-nil)."
  [entries field]
  (->> entries
       (map #(get-field % field))
       (filter some?)))
