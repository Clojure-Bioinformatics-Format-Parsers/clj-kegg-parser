(ns schmoho.biodb.kegg.parser
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.string :as str]))

(defn- update-if-exists
  [m k f]
  (if (contains? m k)
    (update m k f)
    m))

(def two-part (partial map #(str/split % #"\s+" 2)))

(defn split-bulk-response
  [lines]
  (let [idx (inc (.indexOf lines "///"))
        [head tail] (split-at idx lines)]
    (if (not-empty tail)
      (concat [(drop-last head)] (split-bulk-response tail))
      [(drop-last head)])))

(defn group-by-kegg-fields
  [response]
  (->> response
       (map #(if (> (count %) 12)
               ;; KEGG uses a line based format with
               ;; 12 char tag
               [(subs % 0 12) (subs % 12)]
               [%]))
       (reduce (fn [acc [tag value]]
                 (if (str/blank? (str/trim tag))
                   (update acc
                           (dec (count acc))
                           #(conj % value))
                   (conj acc [tag value])))
               [])
       (map (fn [[k & vs]]
              [(str/trim k) vs]))
       (into {})))

(defn parse-grouped-kegg-result
  [response]
  (let [sane-response
        (-> response
            (update-keys csk/->kebab-case-keyword)
            (update :entry (comp #(str/split % #"\s+") first))
            (update-if-exists :orthology two-part)
            (update-if-exists :dblinks (partial map #(str/split % #":\s+" 2)))
            (update-if-exists :ntseq (comp (partial apply str)
                                           (partial drop 1)))
            (update-if-exists :aaseq (comp (partial apply str)
                                           (partial drop 1)))
            (update-if-exists :organism two-part)
            (update-if-exists :name first)
            (update-if-exists :pathway (comp (partial map first)
                                             two-part))
            (update-if-exists :gene two-part)
            (update-if-exists :rel-pathway two-part)
            (update-if-exists :compound two-part)
            (update-if-exists :pathway-map two-part)
            (update-if-exists :module two-part))
        entry (:entry sane-response)]
    (-> sane-response
        (assoc :entry-type (second entry))
        (assoc :t-number (nth entry 2 nil))
        (assoc :id (if-let [org (ffirst (:organism sane-response))]
                     (str org ":" (first entry))
                     (first entry))))))

(defn parse-kegg-get-result
  [body]
  (let [parsed-responses
        (->> body
             (str/split-lines)
             split-bulk-response
             (map group-by-kegg-fields)
             (map parse-grouped-kegg-result))]
    (if (= 1 (count parsed-responses))
      (first parsed-responses)
      parsed-responses)))

(defn parse-genome-list
  [list]
  (->> list
       (str/split-lines)
       (map #(str/split % #"\t"))
       (map (fn [[t-number organism-name]]
              (->> (str/split organism-name #";" 2)
                   (map str/trim)
                   (concat [t-number]))))))

(defn parse-organism-list
  [list]
  (->> list
       (str/split-lines)
       (map (comp
             (fn [[t-number kegg-code scientific-name lineage]]
               [t-number kegg-code scientific-name (vec (str/split lineage #";"))])
             #(str/split % #"\t")))))


;; doing this cleanly would distinguish on entry type etc.
;; but it does the job for now

;; (defn entry-type
;;   [entry]
;;   (-> entry
;;       :entry
;;       second
;;       str/lower-case
;;       keyword))
