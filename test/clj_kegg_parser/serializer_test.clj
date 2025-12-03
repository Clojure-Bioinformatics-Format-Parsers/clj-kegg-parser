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
    (is (= "VERYLONGLAB" (subs (ser/pad-label "VERYLONGLABEL") 0 11)))
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
