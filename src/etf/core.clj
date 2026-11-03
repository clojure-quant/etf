(ns etf.core
  "Command line entry point.

  etf.core [-s api|html] [-f json|edn|csv|table] [-o FILE] [-n LIMIT] [-u URL]"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [etf.product :as product]
            [etf.screener :as screener]))

(def columns
  "The fields this tool reports."
  [:ticker :name :inception-date :net-assets])

(def usage
  (str "Usage: clojure -M:run [options]\n\n"
       "  -s, --source api|html   scraped source (default: api)\n"
       "                            api  = product-screener JSON API (fast, one request)\n"
       "                            html = each fund page parsed with hickory (slow)\n"
       "  -f, --format FORMAT     json|edn|csv|table (default: json)\n"
       "  -o, --out FILE          write to FILE instead of stdout\n"
       "  -n, --limit N           only scrape the first N funds (for html source)\n"
       "  -u, --url URL           override the screener API URL\n"
       "  -h, --help              show this help\n"))

(defn- parse-args [args]
  (loop [args args opts {}]
    (if-let [a (first args)]
      (let [[k v & more] args]
        (case k
          ("-h" "--help") (assoc opts :help true)
          ("-s" "--source") (recur more (assoc opts :source v))
          ("-f" "--format") (recur more (assoc opts :format v))
          ("-o" "--out") (recur more (assoc opts :out v))
          ("-n" "--limit") (recur more (assoc opts :limit (Long/parseLong v)))
          ("-u" "--url") (recur more (assoc opts :url v))
          (recur (rest args) opts)))
      opts)))

;; --- scraping ---------------------------------------------------------------

(defn scrape-api
  ([] (screener/scrape))
  ([url] (screener/scrape url)))

(def ^:private ishares-root "https://www.ishares.com")

(defn- absolute-url [u]
  (if (re-find #"^https?://" u) u (str ishares-root u)))

(defn scrape-html
  "Scrape each fund page with hickory, starting from the API's list of funds.
  Optional `limit` caps how many pages are visited."
  [url limit]
  (let [funds (screener/scrape url)
        funds (if limit (take limit funds) funds)]
    (->> funds
         (mapv (fn [fund]
                 (binding [*out* *err*]
                   (println "Scraping" (:ticker fund) (:product-url fund)))
                 (merge fund (product/scrape (absolute-url (:product-url fund)))))))))

;; --- download-universe (-X exec) --------------------------------------------

(defn download-universe
  "Download the full ETF universe and write it to `out` as EDN.

  Invoke with:  clojure -X:download-universe
  Options:      :out (string, default \"data/etf-universe.edn\")
                :url (string, screener API URL override)"
  [{:keys [out url] :or {out "data/etf-universe.edn"}}]
  (let [funds (screener/scrape (or url screener/default-url))
        file  (io/file out)]
    (io/make-parents file)
    (spit file (with-out-str (pprint/pprint funds)))
    (binding [*out* *err*]
      (println (str "Wrote " (count funds) " funds to " out)))
    {:out out :count (count funds)}))

;; --- output -----------------------------------------------------------------

(defn- csv-cell [v]
  (let [s (str (or v ""))]
    (if (re-find #"[,\"\n]" s)
      (str \" (str/replace s "\"" "\"\"") \")
      s)))

(defn to-csv [funds]
  (str (str/join "," (map name columns)) "\n"
       (str/join "\n" (map #(str/join "," (map (comp csv-cell %) columns)) funds))
       "\n"))

(defn- pad [s w] (let [s (str s)] (str s (apply str (repeat (max 0 (- w (count s))) " ")))))

(defn to-table [funds]
  (let [widths (map (fn [c] (apply max (count (name c)) (map #(count (str (get % c))) funds))) columns)
        row (fn [vals] (str/join "  " (map pad vals widths)))
        sep (str/join "  " (map #(apply str (repeat % \-)) widths))]
    (str (row (map name columns)) "\n" sep "\n"
         (str/join "\n" (map #(row (map (fn [c] (get % c)) columns)) funds)))))

(defn serialize [format funds]
  (case format
    "json"  (json/generate-string funds {:pretty true})
    "edn"   (with-out-str (pprint/pprint (mapv #(select-keys % columns) funds)))
    "csv"   (to-csv funds)
    "table" (to-table funds)
    (throw (ex-info (str "Unknown format: " format) {:format format}))))

;; --- main -------------------------------------------------------------------

(defn -main [& args]
  (let [{:keys [help source format out limit url] :or {source "api" format "json"}} (parse-args args)]
    (if help
      (print usage)
      (let [funds (case source
                    "api"  (scrape-api (or url screener/default-url))
                    "html" (scrape-html (or url screener/default-url) limit)
                    (throw (ex-info (str "Unknown source: " source) {:source source})))
            payload (serialize format funds)]
        (if out
          (spit out payload)
          (print payload))
        (when out
          (binding [*out* *err*]
            (println (str "Wrote " (count funds) " funds to " out))))))))