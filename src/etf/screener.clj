(ns etf.screener
  "Scrape the ETF list that powers
  https://www.ishares.com/us/products/etf-investments#/?productView=etf

  The listing page is an Angular app, so the rows are not present in the HTML.
  The app fetches them from the iShares product-screener JSON API, which returns
  every matching fund in a single response keyed by portfolio id."
  (:require [cheshire.core :as json]
            [etf.http :as http]))

(def backend-config-dcr-path
  "/templatedata/config/product-screener-v3/data/en/us-ishares/ishares-product-screener-backend-config")

(def default-url
  (str "https://www.ishares.com/us/product-screener/product-screener-v3.1.jsn"
       "?dcrPath=" backend-config-dcr-path
       "&siteEntryPassthrough=true"
       "&productView=etf"
       "&sortColumn=totalNetAssets&sortDirection=desc&pageNumber=1&search=ishares"))

(defn fetch-json
  "Fetch the raw screener payload as Clojure data (JSON keys -> keywords)."
  ([] (fetch-json default-url))
  ([url] (json/parse-string (http/get-string url {:Accept "application/json"}) true)))

(defn ->fund
  "Reduce one raw screener record to the fields we care about.
  iShares wraps display/raw values as {\"d\" formatted \"r\" numeric}."
  [m]
  {:portfolio-id     (:portfolioId m)
   :ticker           (:localExchangeTicker m)
   :name             (:fundName m)
   :inception-date   (get-in m [:inceptionDate :d])
   :net-assets       (get-in m [:totalNetAssets :d])
   :net-assets-value (get-in m [:totalNetAssets :r])
   :product-url      (:productPageUrl m)})

(defn parse-funds
  "Turn the raw screener payload into a vector of fund maps, sorted by net assets
  descending (the order requested by the listing URL)."
  [raw]
  (->> (vals raw)
       (map ->fund)
       (sort-by #(or (:net-assets-value %) 0) >)
       vec))

(defn scrape
  "Fetch and parse the ETF listing."
  ([] (scrape default-url))
  ([url] (parse-funds (fetch-json url))))