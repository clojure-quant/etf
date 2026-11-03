(ns etf.download
  "Download a daily snapshot of key ETF facts.

  The universe of tickers and their product URLs come from
  data/etf-universe.edn (written by `etf.core/download-universe`). That file is
  loaded once and cached; `download-etf` uses it to look up a fund's product URL
  by ticker, then scrapes the fund page for the key facts."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [etf.product :as product])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

(def universe-file
  "EDN universe written by `etf.core/download-universe`."
  "data/etf-universe.edn")

(def ^:private ishares-root "https://www.ishares.com")

(defn- absolute-url [u]
  (if (re-find #"^https?://" u) u (str ishares-root u)))

;; --- cached universe --------------------------------------------------------

(def ^:private universe
  (delay (edn/read-string (slurp (io/file universe-file)))))

(def ^:private universe-tickers
  (delay (mapv :ticker @universe)))

(def ^:private product-urls
  (delay (into {} (map (juxt :ticker :product-url)) @universe)))

(defn tickers
  "All tickers in the universe (cached)."
  []
  @universe-tickers)

(defn product-url
  "Product URL for `ticker`, looked up in the cached universe."
  [ticker]
  (get @product-urls ticker))

;; --- per-fund download ------------------------------------------------------

(defn download-etf
  "Fetch and extract the key facts for the fund with `ticker`.
  Returns nil when the ticker is not in the universe."
  [ticker]
  (when-let [url (product-url ticker)]
    (assoc (product/scrape-facts (absolute-url url)) :ticker ticker)))

;; --- daily snapshot ---------------------------------------------------------

(def ^:private date-format (DateTimeFormatter/ofPattern "MMM d, yyyy" Locale/US))

(defn- parse-date [s]
  (when (seq s)
    (try (LocalDate/parse s date-format) (catch Exception _ nil))))

(defn- ->long
  "Parse a formatted number such as \"1,148,300,000\" or \"4,720,358.00\"."
  [s]
  (some-> s (str/replace "," "") bigdec .longValue))

(defn- latest-share-outstanding-date
  [funds]
  (->> funds
       (keep #(parse-date (:share-outstanding-date %)))
       (reduce #(if (or (nil? %1) (.isAfter %2 %1)) %2 %1) nil)))

(defn snapshot
  "Keep the funds whose share-outstanding-date equals the latest one across
  `funds`, and reduce each to a row with `:ticker`, an ISO `:date` and the
  numeric `snapshot-fields`, sorted by ticker."
  [funds]
  (let [latest (latest-share-outstanding-date funds)]
    (->> funds
         (filter #(and latest (= latest (parse-date (:share-outstanding-date %)))))
         (sort-by :ticker)
         (mapv (fn [f]
                 {:ticker              (:ticker f)
                  :date                (str (parse-date (:share-outstanding-date f)))
                  :shares-outstanding  (->long (:shares-outstanding f))
                  :number-of-holdings  (->long (:number-of-holdings f))
                  :30-d-avg-volume     (->long (:30-d-avg-volume f))
                  :net-assets          (->long (:net-assets f))})))))

(defn download-current-date
  "Download the current daily snapshot and write it to `data/day-<date>.edn`.

  Options:
    :date    LocalDate (default: today)
    :out     output file (default: \"data/day-<date>.edn\")
    :tickers tickers to download (default: all from the cached universe)
    :limit   only the first N tickers
    :funds   already-downloaded fund maps, skipping the network"
  ([] (download-current-date {}))
  ([{:keys [date out funds limit] :as opts :or {date (LocalDate/now)}}]
   (let [tickers (or (:tickers opts) (tickers))
         tickers (if limit (take limit tickers) tickers)
         funds   (or funds
                     (->> tickers
                          (pmap (fn [t]
                                  (binding [*out* *err*]
                                    (println "Downloading" t))
                                  (download-etf t)))
                          (remove nil?)))
         snap    (snapshot funds)
         out     (or out (str "data/day-" date ".edn"))
         file    (io/file out)]
     (io/make-parents file)
     (spit file (with-out-str (pprint/pprint snap)))
     (binding [*out* *err*]
       (println (str "Wrote " (count snap) " funds to " out)))
     {:out out :date (str date) :count (count snap)})))