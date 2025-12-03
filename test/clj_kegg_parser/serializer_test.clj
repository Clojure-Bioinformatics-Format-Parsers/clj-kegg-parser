(ns clj-kegg-parser.serializer-test
  "Test skeleton for KEGG serializer functionality.
   
   Includes:
   - Basic label padding and line wrapping tests
   - Field emission tests for various value types
   - Placeholder for round-trip tests (pending text->map parser)
   
   To run: clj -X:test"
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.string :as str]
            [clj-kegg-parser.serializer :as ser]
            [clj-kegg-parser.field-registry :as reg]))

;; ---------------------------------------------------------------------------
;; Label Padding Tests
;; ---------------------------------------------------------------------------

(deftest pad-label-test
  (testing "pad-label produces correct width"
    (is (= 12 (count (ser/pad-label "ENTRY"))))
    (is (= 12 (count (ser/pad-label "NAME"))))
    (is (= 12 (count (ser/pad-label "DESCRIPTION")))))
  
  (testing "pad-label pads short labels"
    (is (= "ENTRY       " (ser/pad-label "ENTRY")))
    (is (= "NAME        " (ser/pad-label "NAME"))))
  
  (testing "pad-label truncates long labels"
    (is (= "VERYLONGLABE" (ser/pad-label "VERYLONGLABEL")))
    (is (= 12 (count (ser/pad-label "VERYLONGLABELNAME"))))))

(deftest blank-label-test
  (testing "blank-label produces correct width"
    (is (= 12 (count (ser/blank-label))))
    (is (= "            " (ser/blank-label))))
  
  (testing "blank-label with custom width"
    (is (= 5 (count (ser/blank-label 5))))
    (is (= "     " (ser/blank-label 5)))))

;; ---------------------------------------------------------------------------
;; Key Normalization Tests
;; ---------------------------------------------------------------------------

(deftest normalize-key-test
  (testing "keyword to uppercase string"
    (is (= "ENTRY" (ser/normalize-key :entry)))
    (is (= "PATHWAY_MAP" (ser/normalize-key :pathway-map))))
  
  (testing "string preserved and uppercased"
    (is (= "ENTRY" (ser/normalize-key "entry")))
    (is (= "ENTRY" (ser/normalize-key "ENTRY")))))

(deftest key->keyword-test
  (testing "string to keyword"
    (is (= :entry (ser/key->keyword "ENTRY")))
    (is (= :pathway-map (ser/key->keyword "PATHWAY_MAP"))))
  
  (testing "keyword passthrough"
    (is (= :entry (ser/key->keyword :entry)))))

;; ---------------------------------------------------------------------------
;; Line Wrapping Tests
;; ---------------------------------------------------------------------------

(deftest wrap-text-test
  (testing "short text returns single line"
    (is (= ["hello world"] (ser/wrap-text "hello world" 68))))
  
  (testing "text wraps at specified width"
    (let [text "this is a long text that should wrap at the specified width"
          lines (ser/wrap-text text 30)]
      (is (> (count lines) 1))
      (doseq [line lines]
        (is (<= (count line) 30)))))
  
  (testing "empty text returns empty string"
    (is (= [""] (ser/wrap-text ""))))
  
  (testing "single long word preserved"
    (let [lines (ser/wrap-text "superlongwordthatwontfit" 10)]
      (is (= 1 (count lines)))
      (is (= "superlongwordthatwontfit" (first lines))))))

;; ---------------------------------------------------------------------------
;; Field Emission Tests
;; ---------------------------------------------------------------------------

(deftest emit-field-lines-test
  (testing "simple string field"
    (let [lines (ser/emit-field-lines :name "Glucose")]
      (is (= 1 (count lines)))
      (is (str/starts-with? (first lines) "NAME        "))
      (is (str/includes? (first lines) "Glucose"))))
  
  (testing "nil value returns empty"
    (is (empty? (ser/emit-field-lines :name nil))))
  
  (testing "empty string returns empty"
    (is (empty? (ser/emit-field-lines :name ""))))
  
  (testing "sequential values"
    (let [lines (ser/emit-field-lines :pathway ["path:map00010" "path:map00020"])]
      (is (= 2 (count lines)))
      (is (str/starts-with? (first lines) "PATHWAY     "))
      (is (str/starts-with? (second lines) "            ")))))

;; ---------------------------------------------------------------------------
;; Entry Emission Tests
;; ---------------------------------------------------------------------------

(deftest emit-entry-test
  (testing "basic entry emission"
    (let [entry {:entry "C00001" :name "Water" :entry-type :compound}
          lines (ser/emit-entry entry :compound)]
      (is (some #(str/starts-with? % "ENTRY") lines))
      (is (some #(str/starts-with? % "NAME") lines))
      (is (some #(= "///" %) lines))))
  
  (testing "entry terminates with ///"
    (let [entry {:entry "test" :entry-type :unknown}
          lines (ser/emit-entry entry :unknown)]
      (is (= "///" (last lines))))))

;; ---------------------------------------------------------------------------
;; Full Serialization Tests  
;; ---------------------------------------------------------------------------

(deftest kegg-map->text-test
  (testing "basic serialization produces text"
    (let [entry {:entry "C00001"
                 :name "Water"
                 :formula "H2O"
                 :entry-type :compound}
          text (ser/kegg-map->text entry)]
      (is (string? text))
      (is (str/includes? text "ENTRY"))
      (is (str/includes? text "NAME"))
      (is (str/includes? text "Water"))
      (is (str/includes? text "///")))))

;; ---------------------------------------------------------------------------
;; Field Registry Tests
;; ---------------------------------------------------------------------------

(deftest field-registry-test
  (testing "all entry types have field vectors"
    (doseq [entry-type (reg/entry-types)]
      (is (vector? (reg/get-field-order entry-type))
          (str "Missing field vector for " entry-type))))
  
  (testing "field vectors are non-empty"
    (doseq [entry-type (reg/entry-types)]
      (is (seq (reg/get-field-order entry-type))
          (str "Empty field vector for " entry-type))))
  
  (testing "get-field-order handles string input"
    (is (= (reg/get-field-order :pathway)
           (reg/get-field-order "pathway")
           (reg/get-field-order "PATHWAY")))))

(deftest special-blocks-test
  (testing "special blocks are identified"
    (is (reg/special-block? :aaseq))
    (is (reg/special-block? :ntseq))
    (is (reg/special-block? :kcf))
    (is (not (reg/special-block? :name)))
    (is (not (reg/special-block? :entry)))))

;; ---------------------------------------------------------------------------
;; Round-Trip Test Placeholders
;; ---------------------------------------------------------------------------
;; These tests require the text->map parser to be available.
;; Uncomment and implement once parser integration is complete.

(comment
  (deftest round-trip-test
    (testing "compound round-trip"
      (let [original {:entry "C00001"
                      :name "Water"
                      :formula "H2O"
                      :entry-type :compound}
            text (ser/kegg-map->text original)
            parsed (parser/text->map text)]  ; TODO: implement parser
        (is (= (:entry original) (:entry parsed)))
        (is (= (:name original) (:name parsed)))
        (is (= (:formula original) (:formula parsed)))))
  
    (testing "pathway round-trip"
      (let [original {:entry "map00010"
                      :name "Glycolysis / Gluconeogenesis"
                      :entry-type :pathway}
            text (ser/kegg-map->text original)
            parsed (parser/text->map text)]
        (is (= (:entry original) (:entry parsed)))
        (is (= (:name original) (:name parsed)))))))

;; ---------------------------------------------------------------------------
;; Sequence Formatting Tests
;; ---------------------------------------------------------------------------

(deftest emit-sequence-test
  (testing "sequence shows correct length"
    (let [seq-data "MVLSPADKTNVKAAWGKVGAHAGEYGAEALERMFLSFPTTK"
          lines (ser/emit-sequence :aaseq seq-data)]
      (is (str/starts-with? (first lines) "AASEQ"))
      (is (str/includes? (first lines) "41"))))
  
  (testing "sequence wraps at 60 characters"
    (let [long-seq (apply str (repeat 120 "A"))
          lines (ser/emit-sequence :aaseq long-seq)]
      ;; First line has label + length, then sequence lines
      (is (= 3 (count lines)))  ;; header + 2 sequence lines
      (is (str/includes? (first lines) "120"))
      ;; Check sequence lines are 60 chars each (after label padding)
      (is (= 72 (count (second lines))))  ;; 12 spaces + 60 chars
      (is (= 72 (count (nth lines 2)))))))  ;; 12 spaces + 60 chars

(deftest emit-sequence-ntseq-test
  (testing "NTSEQ sequence format"
    (let [seq-data "ATGCATGCATGC"
          lines (ser/emit-sequence :ntseq seq-data)]
      (is (str/starts-with? (first lines) "NTSEQ"))
      (is (str/includes? (first lines) "12")))))

;; ---------------------------------------------------------------------------
;; Format-Specific Serialization Tests
;; ---------------------------------------------------------------------------

(deftest pathway-format-test
  (testing "PATHWAY entry serialization"
    (let [entry {:entry "map00010"
                 :name "Glycolysis / Gluconeogenesis"
                 :class "Metabolism; Carbohydrate metabolism"
                 :pathway-map ["map00010 Glycolysis / Gluconeogenesis"]
                 :entry-type :pathway}
          text (ser/kegg-map->text entry)]
      (is (str/includes? text "ENTRY"))
      (is (str/includes? text "NAME"))
      (is (str/includes? text "CLASS"))
      (is (str/includes? text "PATHWAY_MAP"))
      (is (str/includes? text "///")))))

(deftest genes-format-test
  (testing "GENES entry with sequence"
    (let [entry {:entry "hsa:7157"
                 :name "TP53"
                 :definition "tumor protein p53"
                 :organism "Homo sapiens (human)"
                 :aaseq "MEEPQSDPSVEPPLSQETFSDLWKLLPENNVLSPLPSQAMDDLMLSPDDIEQWFTEDPGP"
                 :entry-type :genes}
          text (ser/kegg-map->text entry)]
      (is (str/includes? text "ENTRY"))
      (is (str/includes? text "AASEQ"))
      (is (str/includes? text "60"))  ;; sequence length
      (is (str/includes? text "///")))))

(deftest compound-format-test
  (testing "COMPOUND entry serialization"
    (let [entry {:entry "C00001"
                 :name "H2O; Water"
                 :formula "H2O"
                 :exact-mass "18.0106"
                 :mol-weight "18.0153"
                 :entry-type :compound}
          text (ser/kegg-map->text entry)]
      (is (str/includes? text "ENTRY"))
      (is (str/includes? text "FORMULA"))
      (is (str/includes? text "EXACT_MASS"))
      (is (str/includes? text "MOL_WEIGHT"))
      (is (str/includes? text "///")))))

(deftest enzyme-format-test
  (testing "ENZYME entry serialization"
    (let [entry {:entry "1.1.1.1"
                 :name "Alcohol dehydrogenase"
                 :class "Oxidoreductases"
                 :sysname "alcohol:NAD+ oxidoreductase"
                 :entry-type :enzyme}
          text (ser/kegg-map->text entry)]
      (is (str/includes? text "ENTRY"))
      (is (str/includes? text "NAME"))
      (is (str/includes? text "CLASS"))
      (is (str/includes? text "SYSNAME"))
      (is (str/includes? text "///")))))

(deftest reaction-format-test
  (testing "REACTION entry serialization"
    (let [entry {:entry "R00001"
                 :name "polyphosphate polyphosphohydrolase"
                 :definition "Polyphosphate + n H2O <=> (n+1) Oligophosphate"
                 :equation "C00404 + n C00001 <=> (n+1) C02174"
                 :entry-type :reaction}
          text (ser/kegg-map->text entry)]
      (is (str/includes? text "ENTRY"))
      (is (str/includes? text "DEFINITION"))
      (is (str/includes? text "EQUATION"))
      (is (str/includes? text "///")))))

(deftest disease-format-test
  (testing "DISEASE entry serialization"
    (let [entry {:entry "H00001"
                 :name "Acute lymphoblastic leukemia"
                 :description "Acute lymphoblastic leukemia (ALL) is a malignant disease"
                 :category "Cancer"
                 :entry-type :disease}
          text (ser/kegg-map->text entry)]
      (is (str/includes? text "ENTRY"))
      (is (str/includes? text "NAME"))
      (is (str/includes? text "DESCRIPTION"))
      (is (str/includes? text "CATEGORY"))
      (is (str/includes? text "///")))))

(deftest drug-format-test
  (testing "DRUG entry serialization"
    (let [entry {:entry "D00001"
                 :name "Water"
                 :formula "H2O"
                 :mol-weight "18.0153"
                 :entry-type :drug}
          text (ser/kegg-map->text entry)]
      (is (str/includes? text "ENTRY"))
      (is (str/includes? text "NAME"))
      (is (str/includes? text "FORMULA"))
      (is (str/includes? text "///")))))

(deftest all-entry-types-serialize
  (testing "all entry types produce valid output"
    (doseq [entry-type (reg/entry-types)]
      (let [entry {:entry "TEST001"
                   :name "Test Entry"
                   :entry-type entry-type}
            text (ser/kegg-map->text entry)]
        (is (string? text) (str "Failed for " entry-type))
        (is (str/includes? text "ENTRY") (str "Missing ENTRY for " entry-type))
        (is (str/includes? text "///") (str "Missing /// for " entry-type))))))

;; ---------------------------------------------------------------------------
;; Sample Data for Manual Testing
;; ---------------------------------------------------------------------------

(def sample-compound
  "Sample compound entry for testing"
  {:entry "C00001"
   :name "H2O; Water"
   :formula "H2O"
   :exact-mass "18.0106"
   :mol-weight "18.0153"
   :entry-type :compound})

(def sample-pathway
  "Sample pathway entry for testing"
  {:entry "map00010"
   :name "Glycolysis / Gluconeogenesis"
   :description "Glycolysis is the process of converting glucose..."
   :gene [["b0008" "talB; transaldolase B"]
          ["b0114" "aceE; pyruvate dehydrogenase"]]
   :entry-type :pathway})

(def sample-genes
  "Sample genes entry for testing"
  {:entry "hsa:7157"
   :name "TP53"
   :definition "tumor protein p53"
   :orthology "K04451 tumor protein p53"
   :organism "Homo sapiens (human)"
   :aaseq "MEEPQSDPSVEPPLSQETFSDLWKLLPENNVLSPLPSQAMDDLMLSPDDIEQWFTEDPGPDEAPRMPEAAPPVAPAPAAPTPAAPAPAPSWPLSSSVPSQKTYQGSYGFRLGFLHSGTAKSVTCTYSPALNKMFCQLAKTCPVQLWVDSTPPPGTRVRAMAIYKQSQHMTEVVRRCPHHERCSDSDGLAPPQHLIRVEGNLRVEYLDDRNTFRHSVVVPYEPPEVGSDCTTIHYNYMCNSSCMGGMNRRPILTIITLEDSSGNLLGRNSFEVRVCACPGRDRRTEEENLRKKGEPHHELPPGSTKRALPNNTSSSPQPKKKPLDGEYFTLQIRGRERFEMFRELNEALELKDAQAGKEPGGSRAHSSHLKSKKGQSTSRHKKLMFKTEGPDSD"
   :entry-type :genes})
