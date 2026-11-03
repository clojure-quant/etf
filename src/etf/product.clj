(ns etf.product
  "Scrape an individual iShares fund page with hickory.

  Every fund page embeds a schema.org JSON-LD block
  (<script type=\"application/ld+json\">) whose @graph carries the fund facts,
  including the ticker, fund name, inception date and net assets of fund.
  hickory parses the HTML; we pull the JSON-LD text out and read the values."
  (:require [cheshire.core :as json]
            [etf.http :as http]
            [hickory.core :as hickory]))

(defn ld-json-texts
  "Extract the text of every <script type=\"application/ld+json\"> element from an
  hickory tree."
  [tree]
  (->> (tree-seq map? :content tree)
       (filter #(and (= :element (:type %)) (= :script (:tag %))))
       (filter #(= "application/ld+json" (get-in % [:attrs :type])))
       (map #(apply str (:content %)))))

(defn- property-pairs
  "Depth-first collect every [name value] pair from schema.org PropertyValue nodes."
  [node]
  (cond
    (map? node)
    (concat
     (when-let [props (:additionalProperty node)]
       (map (fn [p] [(:name p) (:value p)]) props))
     (when (:propertyID node)
       [[(:propertyID node) (:value node)]])
     (mapcat property-pairs (vals node)))

    (sequential? node) (mapcat property-pairs node)
    :else nil))

(def ^:private graph-key (keyword "@graph"))

(defn parse-html
  "Parse a fund page's HTML into {:ticker :name :inception-date :net-assets}."
  [html]
  (let [graphs (->> (ld-json-texts (hickory/as-hickory (hickory/parse html)))
                    (map #(json/parse-string % true))
                    (keep #(get % graph-key)))
        props  (into {} (mapcat property-pairs graphs))
        parsed {:ticker         (get props "ticker")
                :name           (get props "fundName")
                :inception-date (get props "Fund Inception")
                :net-assets     (get props "Net Assets of Fund")}]
    (into {} (filter (comp some? val) parsed))))

(defn scrape
  "Fetch and parse a single fund page given its product URL."
  [product-url]
  (parse-html (http/get-string product-url)))

;; --- key facts (walrus component payloads) ----------------------------------

(defn component-props
  "Parse the JSON inside every `<walrus-render-on-client componentprops=\"...\">`
  element of a parsed fund page into a `{component-key data}` map."
  [tree]
  (->> (tree-seq map? :content tree)
       (filter #(and (= :element (:type %))
                     (= :walrus-render-on-client (:tag %))))
       (keep (fn [el]
               (let [{:keys [componentkey componentprops]} (:attrs el)]
                 (when componentprops
                   [componentkey (json/parse-string componentprops true)]))))
       (into {})))

(defn- find-datapoint
  "Depth-first find the first `{:name n ...}` data-point map under `node`."
  [node n]
  (cond
    (map? node) (or (when (= n (:name node)) node)
                    (some #(find-datapoint % n) (vals node)))
    (sequential? node) (some #(find-datapoint % n) node)
    :else nil))

(defn parse-facts
  "Extract key fund facts from the walrus payloads of a fund page's HTML."
  [html]
  (let [props  (component-props (hickory/as-hickory (hickory/parse html)))
        fact   (fn [n] (some #(find-datapoint % n) (vals props)))
        value  (fn [n] (:formattedValue (fact n)))
        shares (fact "sharesOutstanding")]
    (->> {:shares-outstanding     (:formattedValue shares)
          :share-outstanding-date (:formattedAsOfDate shares)
          :exchange               (value "exchange")
          :number-of-holdings     (value "numHoldings")
          :30-d-avg-volume        (value "thirtyDayAverageVolume")
          :net-assets             (value "totalNetAssetsFundLevel")}
         (remove (comp nil? val))
         (into {}))))

(defn scrape-facts
  "Fetch a fund page and extract its key facts."
  [product-url]
  (parse-facts (http/get-string product-url)))