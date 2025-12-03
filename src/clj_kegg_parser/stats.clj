(ns clj-kegg-parser.stats
  "Statistics utilities for summarizing KEGG entry collections.
   
   Provides functions for:
   - Field frequency analysis (which fields appear in entries)
   - Distinct value counts (unique organisms, pathways, etc.)
   - Entry summarization (counts by type, field coverage)
   - CSV export helpers for downstream analysis
   
   All functions work with lazy sequences and are designed for
   efficient processing of large entry collections."
  (:require [clojure.string :as str]
            [clj-kegg-parser.extractors :as ex]))

;; ---------------------------------------------------------------------------
;; Field Analysis
;; ---------------------------------------------------------------------------

(defn field-frequency
  "Computes the frequency of each field across a collection of entries.
   
   Parameters:
   - entries: Sequence of KEGG entry maps
   
   Returns: Map of field-name -> count, sorted by count descending."
  [entries]
  (->> entries
       (mapcat keys)
       (frequencies)
       (sort-by (comp - val))
       (into {})))

(defn field-coverage
  "Computes field coverage as percentage of entries containing each field.
   
   Parameters:
   - entries: Sequence of KEGG entry maps
   
   Returns: Map of field-name -> percentage (0-100)"
  [entries]
  (let [total (count entries)
        freqs (field-frequency entries)]
    (when (pos? total)
      (->> freqs
           (map (fn [[k v]] [k (double (* 100 (/ v total)))]))
           (into {})))))

(defn fields-present
  "Returns the set of fields present in an entry."
  [entry]
  (->> entry
       (filter (fn [[_ v]] (some? v)))
       (map first)
       (set)))

(defn missing-fields
  "Returns fields from expected-fields not present in entry.
   
   Parameters:
   - entry: A KEGG entry map
   - expected-fields: Collection of expected field names"
  [entry expected-fields]
  (let [present (fields-present entry)]
    (remove present expected-fields)))

;; ---------------------------------------------------------------------------
;; Distinct Values
;; ---------------------------------------------------------------------------

(defn distinct-values
  "Extracts distinct values for a field across entries.
   
   Parameters:
   - entries: Sequence of KEGG entry maps
   - field: Field keyword to analyze
   
   Returns: Set of distinct values"
  [entries field]
  (->> entries
       (map #(ex/get-field % field))
       (filter some?)
       (flatten)
       (map str)
       (remove str/blank?)
       (set)))

(defn distinct-count
  "Counts distinct values for a field across entries."
  [entries field]
  (count (distinct-values entries field)))

(defn value-frequency
  "Computes frequency of values for a specific field.
   
   Parameters:
   - entries: Sequence of KEGG entry maps
   - field: Field keyword to analyze
   
   Returns: Map of value -> count, sorted by count descending"
  [entries field]
  (->> entries
       (map #(ex/get-field % field))
       (filter some?)
       (mapcat #(if (sequential? %) % [%]))
       (map str)
       (frequencies)
       (sort-by (comp - val))
       (into {})))

;; ---------------------------------------------------------------------------
;; Entry Summarization
;; ---------------------------------------------------------------------------

(defn summarize-entries
  "Provides a summary overview of a collection of KEGG entries.
   
   Parameters:
   - entries: Sequence of KEGG entry maps
   
   Returns: Map with summary statistics:
   - :total-count - Total number of entries
   - :by-type - Counts grouped by entry type
   - :field-frequency - How often each field appears
   - :sample-ids - First 5 entry IDs"
  [entries]
  (let [entries-vec (vec entries)]
    {:total-count (count entries-vec)
     :by-type (->> entries-vec
                   (map ex/entry-type)
                   (frequencies))
     :field-frequency (field-frequency entries-vec)
     :sample-ids (->> entries-vec
                      (take 5)
                      (map #(or (ex/get-field % :id)
                                (ex/get-field % :entry)))
                      (vec))}))

(defn type-counts
  "Returns counts of entries grouped by entry type.
   
   Parameters:
   - entries: Sequence of KEGG entry maps
   
   Returns: Map of entry-type -> count"
  [entries]
  (->> entries
       (map ex/entry-type)
       (filter some?)
       (frequencies)))

(defn entries-by-type
  "Groups entries by their entry type.
   
   Parameters:
   - entries: Sequence of KEGG entry maps
   
   Returns: Map of entry-type -> [entries]"
  [entries]
  (group-by ex/entry-type entries))

;; ---------------------------------------------------------------------------
;; CSV Export Helpers
;; ---------------------------------------------------------------------------

(defn escape-csv-field
  "Escapes a field value for CSV output.
   Handles commas, quotes, and newlines."
  [value]
  (let [s (str value)]
    (if (or (str/includes? s ",")
            (str/includes? s "\"")
            (str/includes? s "\n"))
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn entry->csv-row
  "Converts an entry to a CSV row for specified fields.
   
   Parameters:
   - entry: A KEGG entry map
   - fields: Sequence of field keywords to include
   
   Returns: CSV row string"
  [entry fields]
  (->> fields
       (map #(ex/get-field entry %))
       (map #(if (sequential? %) (str/join "; " %) %))
       (map escape-csv-field)
       (str/join ",")))

(defn entries->csv
  "Converts entries to CSV format.
   
   Parameters:
   - entries: Sequence of KEGG entry maps
   - fields: Sequence of field keywords to include
   - opts: Optional map with:
     - :header? - Include header row (default true)
     - :separator - Field separator (default ',')
   
   Returns: CSV string"
  ([entries fields] (entries->csv entries fields {}))
  ([entries fields {:keys [header?] :or {header? true}}]
   (let [header (when header?
                  (str/join "," (map name fields)))
         rows (map #(entry->csv-row % fields) entries)]
     (if header?
       (str/join "\n" (cons header rows))
       (str/join "\n" rows)))))

(defn write-csv
  "Writes entries to a CSV file.
   
   Parameters:
   - filepath: Output file path
   - entries: Sequence of KEGG entry maps
   - fields: Sequence of field keywords to include
   - opts: Options passed to entries->csv"
  ([filepath entries fields]
   (write-csv filepath entries fields {}))
  ([filepath entries fields opts]
   (spit filepath (entries->csv entries fields opts))))

;; ---------------------------------------------------------------------------
;; Quick Statistics
;; ---------------------------------------------------------------------------

(defn quick-stats
  "Returns quick statistics for a collection of entries.
   Useful for debugging and exploration.
   
   Returns: Map with :count, :types, :fields, :organisms"
  [entries]
  (let [entries-vec (vec entries)]
    {:count (count entries-vec)
     :types (type-counts entries-vec)
     :fields (count (field-frequency entries-vec))
     :organisms (count (ex/unique-organism-codes entries-vec))}))

(defn print-summary
  "Prints a human-readable summary of entries to stdout."
  [entries]
  (let [summary (summarize-entries entries)]
    (println "=== KEGG Entry Summary ===")
    (println "Total entries:" (:total-count summary))
    (println "\nBy type:")
    (doseq [[t c] (sort-by (comp - val) (:by-type summary))]
      (println (str "  " (or (name t) "unknown") ": " c)))
    (println "\nTop 10 fields:")
    (doseq [[f c] (take 10 (:field-frequency summary))]
      (println (str "  " (name f) ": " c)))
    (println "\nSample IDs:" (str/join ", " (:sample-ids summary)))))
