# KEGG Serializer Usage Snippets

This document provides usage examples for the KEGG serializer, extractors, and statistics utilities.

## Overview

The `clj-kegg-parser` library now includes:
- **field-registry**: Machine-readable field-order registry for all KEGG entry types
- **serializer**: Convert parsed KEGG maps back to flat-file format
- **extractors**: Lazy, transducer-friendly data extraction utilities
- **stats**: Statistical analysis and CSV export helpers

## Basic Serialization

```clojure
(require '[clj-kegg-parser.serializer :as ser])

;; Convert a parsed KEGG entry back to flat-file format
(def compound-entry
  {:entry "C00001"
   :name "H2O; Water"
   :formula "H2O"
   :mol-weight "18.0153"
   :reaction ["R00001" "R00002"]
   :pathway ["map00010" "map00020"]
   :entry-type :compound})

(println (ser/kegg-map->text compound-entry))
;; Output:
;; ENTRY       C00001
;; NAME        H2O; Water
;; FORMULA     H2O
;; MOL_WEIGHT  18.0153
;; REACTION    R00001
;;             R00002
;; PATHWAY     map00010
;;             map00020
;; ///

;; Serialize multiple entries
(def entries [compound-entry another-entry])
(spit "output.txt" (ser/entries->text entries))
```

## Field Registry

```clojure
(require '[clj-kegg-parser.field-registry :as reg])

;; Get canonical field order for an entry type
(reg/get-field-order :compound)
;; => [:entry :name :formula :exact-mass :mol-weight :remark :comment
;;     :reaction :pathway :enzyme :brite :dblinks :atom :bond :bracket]

;; List all known entry types
(reg/entry-types)
;; => (:pathway :brite :module :ko :genes :genome :compound ...)

;; Check if a field requires special handling
(reg/special-block? :aaseq) ;; => true
(reg/special-block? :name)  ;; => false
```

## Extractors

```clojure
(require '[clj-kegg-parser.extractors :as ex])

;; Extract genes from pathway entries
(def pathways [...]) ; sequence of parsed pathway entries
(ex/get-pathway-genes pathways)
;; => (["b0008" "talB; transaldolase B"] ["b0114" "aceE; ..."] ...)

;; Get unique organism codes from gene entries
(def genes [...]) ; sequence of parsed gene entries
(ex/unique-organism-codes genes)
;; => #{"hsa" "eco" "sce" "mmu"}

;; Extract compound names
(def compounds [...])
(ex/get-compound-names compounds)
;; => ("Water" "Glucose" "ATP" ...)

;; Using transducers for efficient processing
(into [] ex/xf-pathway-genes pathways)
(transduce ex/xf-organism-codes conj #{} genes)

;; Filter by entry type
(ex/filter-by-type entries :compound)

;; Get database cross-references
(ex/dblink-by-database compound-entry "PubChem")
;; => ("3303" "5459307")
```

## Statistics

```clojure
(require '[clj-kegg-parser.stats :as stats])

;; Get quick stats
(stats/quick-stats entries)
;; => {:count 150, :types {:compound 50, :pathway 30, ...}, 
;;     :fields 24, :organisms 5}

;; Field frequency analysis
(stats/field-frequency entries)
;; => {:entry 150, :name 148, :dblinks 120, :formula 50, ...}

;; Field coverage (percentage)
(stats/field-coverage entries)
;; => {:entry 100.0, :name 98.7, :dblinks 80.0, ...}

;; Distinct values for a field
(stats/distinct-values entries :organism)
;; => #{"hsa" "eco" "sce"}

;; Value frequency for a field
(stats/value-frequency entries :entry-type)
;; => {:compound 50, :pathway 30, :reaction 20, ...}

;; Full summary
(stats/summarize-entries entries)
;; => {:total-count 150
;;     :by-type {:compound 50 :pathway 30 ...}
;;     :field-frequency {...}
;;     :sample-ids ["C00001" "map00010" ...]}

;; Print human-readable summary
(stats/print-summary entries)
```

## CSV Export

```clojure
(require '[clj-kegg-parser.stats :as stats])

;; Export selected fields to CSV
(stats/write-csv "compounds.csv" 
                 compound-entries 
                 [:entry :name :formula :mol-weight])

;; Get CSV as string
(stats/entries->csv entries [:entry :name :entry-type])
;; => "entry,name,entry-type\nC00001,Water,compound\n..."

;; Without header
(stats/entries->csv entries [:entry :name] {:header? false})
```

## Configuration

```clojure
;; Adjust serialization parameters
(binding [ser/*label-width* 16    ; wider label column
          ser/*line-width* 100]   ; longer lines
  (ser/kegg-map->text entry))
```

## What's Not Yet Implemented

The following features require additional work:

1. **KCF/ATOM/BOND blocks**: GLYCAN KCF and COMPOUND MOL-format blocks need bespoke serialization
2. **Sequence formatting**: AASEQ/NTSEQ should wrap at 60 characters per line
3. **BRITE hierarchy**: Tree-structured hierarchies need indentation handling
4. **Full round-trip tests**: Pending integration with text→map parser

## KEGG Documentation References

Field definitions are based on the official KEGG documentation:
- [PATHWAY](https://www.genome.jp/kegg/kegg3a.html)
- [BRITE](https://www.genome.jp/kegg/kegg3b.html)
- [MODULE](https://www.genome.jp/kegg/kegg3c.html)
- [KO, GENES, GENOME](https://www.genome.jp/kegg/kegg4.html)
- [COMPOUND, GLYCAN, REACTION, RCLASS, ENZYME](https://www.genome.jp/kegg/kegg5.html)
- [NETWORK, VARIANT](https://www.genome.jp/kegg/kegg6.html)
- [DISEASE, DRUG, DGROUP](https://www.genome.jp/kegg/kegg7.html)

## Contributing

When adding new entry types or fields:
1. Add field definitions to `field-registry.clj`
2. Add type-specific serialization in `serializer.clj` if needed
3. Add extractors in `extractors.clj`
4. Add tests in `serializer-test.clj`
