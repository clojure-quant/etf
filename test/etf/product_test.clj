(ns etf.product-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [etf.product :as product]
            [hickory.core :as hickory]))

(def html
  (str "<html><head>"
       "<script type=\"application/ld+json\">"
       "{\"@context\":\"https://schema.org\",\"@graph\":["
       "{\"@type\":\"WebPageElement\","
       "\"additionalProperty\":["
       "{\"@type\":\"PropertyValue\",\"name\":\"ticker\",\"value\":\"MCHI\"},"
       "{\"@type\":\"PropertyValue\",\"name\":\"fundName\",\"value\":\"iShares MSCI China ETF\"}]},"
       "{\"@type\":\"WebPageElement\","
       "\"additionalProperty\":["
       "{\"@type\":\"PropertyValue\",\"name\":\"Net Assets of Fund\",\"value\":\"$5,565,073,770\"},"
       "{\"@type\":\"PropertyValue\",\"name\":\"Fund Inception\",\"value\":\"Mar 29, 2011\"}]}"
       "]}"
       "</script></head><body></body></html>"))

(deftest parse-html-test
  (is (= {:ticker "MCHI"
          :name "iShares MSCI China ETF"
          :inception-date "Mar 29, 2011"
          :net-assets "$5,565,073,770"}
         (product/parse-html html))))

(deftest ld-json-texts-test
  (is (= 1 (count (product/ld-json-texts
                   (hickory/as-hickory (hickory/parse html)))))))

(defn- component [key data]
  (str "<walrus-render-on-client componentkey=\"" key "\" componentprops=\""
       (str/replace (json/generate-string data) "\"" "&quot;")
       "\"></walrus-render-on-client>"))

(def key-facts
  {"dataPoints" {"sharesOutstanding"          {:name "sharesOutstanding"
                                               :formattedValue "1,148,300,000"
                                               :formattedAsOfDate "Nov 02, 2026"}
                 "exchange"                   {:name "exchange"
                                               :formattedValue "NYSE Arca"}
                 "thirtyDayAverageVolume"     {:name "thirtyDayAverageVolume"
                                               :formattedValue "4,720,358.00"}
                 "totalNetAssetsFundLevel"    {:name "totalNetAssetsFundLevel"
                                               :formattedValue "867,098,495,687"}}})

(def portfolio-characteristics
  {"containersByNameMap"
   {"default"
    {"dataPointsByNameMap"
     {"numHoldings" {:name "numHoldings"
                     :formattedValue "504"
                     :formattedAsOfDate "Nov 02, 2026"}}}}})

(def facts-html
  (str "<html><body>"
       (component "KeyFundFactsV3" key-facts)
       (component "PortfolioCharacteristicsV3" portfolio-characteristics)
       "</body></html>"))

(deftest component-props-test
  (let [props (product/component-props
               (hickory/as-hickory (hickory/parse facts-html)))]
    (is (= #{"KeyFundFactsV3" "PortfolioCharacteristicsV3"} (set (keys props))))
    (is (= 2 (count props)))))

(deftest parse-facts-test
  (is (= {:shares-outstanding     "1,148,300,000"
          :share-outstanding-date "Nov 02, 2026"
          :exchange               "NYSE Arca"
          :number-of-holdings     "504"
          :30-d-avg-volume        "4,720,358.00"
          :net-assets             "867,098,495,687"}
         (product/parse-facts facts-html))))