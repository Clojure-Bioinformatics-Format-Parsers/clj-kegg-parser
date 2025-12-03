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

## Sequence Formatting

```clojure
;; AASEQ and NTSEQ are properly formatted with 60-char line width
(def gene-entry
  {:entry "hsa:7157"
   :name "TP53"
   :definition "tumor protein p53"
   :organism "Homo sapiens (human)"
   :aaseq "MEEPQSDPSVEPPLSQETFSDLWKLLPENNVLSPLPSQAMDDLMLSPDDIEQWFTEDPGPDEAPRMPEAAPPVAPAPAAPTPAAPAPAPSWPLSSSVPSQKTYQGSYGFRLGFLHSGTAKSVTCTYSPALNKMFCQLAKTCPVQLWVDSTPPPGTRVRAMAIYKQSQHMTEVVRRCPHHERCSDSDGLAPPQHLIRVEGNLRVEYLDDRNTFRHSVVVPYEPPEVGSDCTTIHYNYMCNSSCMGGMNRRPILTIITLEDSSGNLLGRNSFEVRVCACPGRDRRTEEENLRKKGEPHHELPPGSTKRALPNNTSSSPQPKKKPLDGEYFTLQIRGRERFEMFRELNEALELKDAQAGKEPGGSRAHSSHLKSKKGQSTSRHKKLMFKTEGPDSD"
   :entry-type :genes})

(println (ser/kegg-map->text gene-entry))
;; Output:
;; ENTRY       hsa:7157
;; NAME        TP53
;; DEFINITION  tumor protein p53
;; ORGANISM    Homo sapiens (human)
;; AASEQ       393
;;             MEEPQSDPSVEPPLSQETFSDLWKLLPENNVLSPLPSQAMDDLMLSPDDIEQWFTEDPGP
;;             DEAPRMPEAAPPVAPAPAAPTPAAPAPAPSWPLSSSVPSQKTYQGSYGFRLGFLHSGTAK
;;             ...
;; ///
```

## Configuration

```clojure
;; Adjust serialization parameters
(binding [ser/*label-width* 16         ; wider label column
          ser/*line-width* 100         ; longer lines
          ser/*sequence-line-width* 70] ; custom sequence width
  (ser/kegg-map->text entry))
```

## Supported Entry Types

All 16 KEGG entry types are fully implemented:

| Type | Description | Documentation |
|------|-------------|---------------|
| `:pathway` | Metabolic and signaling pathways | [kegg3a.html](https://www.genome.jp/kegg/kegg3a.html) |
| `:brite` | Hierarchical classifications | [kegg3b.html](https://www.genome.jp/kegg/kegg3b.html) |
| `:module` | Functional units | [kegg3c.html](https://www.genome.jp/kegg/kegg3c.html) |
| `:ko` | KEGG Orthology groups | [kegg4.html](https://www.genome.jp/kegg/kegg4.html) |
| `:genes` | Organism-specific genes | [kegg4.html](https://www.genome.jp/kegg/kegg4.html) |
| `:genome` | Complete genomes | [kegg4.html](https://www.genome.jp/kegg/kegg4.html) |
| `:compound` | Chemical compounds | [kegg5.html](https://www.genome.jp/kegg/kegg5.html) |
| `:glycan` | Carbohydrates | [kegg5.html](https://www.genome.jp/kegg/kegg5.html) |
| `:reaction` | Biochemical reactions | [kegg5.html](https://www.genome.jp/kegg/kegg5.html) |
| `:rclass` | Reaction classes | [kegg5.html](https://www.genome.jp/kegg/kegg5.html) |
| `:enzyme` | Enzyme classification | [kegg5.html](https://www.genome.jp/kegg/kegg5.html) |
| `:network` | Disease/gene networks | [kegg6.html](https://www.genome.jp/kegg/kegg6.html) |
| `:variant` | Disease variants | [kegg6.html](https://www.genome.jp/kegg/kegg6.html) |
| `:disease` | Human diseases | [kegg7.html](https://www.genome.jp/kegg/kegg7.html) |
| `:drug` | Approved drugs | [kegg7.html](https://www.genome.jp/kegg/kegg7.html) |
| `:dgroup` | Drug groups | [kegg7.html](https://www.genome.jp/kegg/kegg7.html) |

## Special Block Handling

The serializer properly handles special formatted blocks:

- **AASEQ/NTSEQ**: Wrapped at 60 characters per line (configurable)
- **ATOM/BOND/BRACKET**: MOL-format blocks for chemical structures
- **KCF**: KEGG Chemical Function format for glycan structures
- **HIERARCHY**: BRITE tree structures with proper indentation
- **REFERENCE**: Multi-field blocks with AUTHORS, TITLE, JOURNAL

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
