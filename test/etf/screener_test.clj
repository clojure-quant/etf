(ns etf.screener-test
  (:require [clojure.test :refer [deftest testing is]]
            [etf.screener :as screener]))

(def raw
  {"239619" {:portfolioId 239619
             :localExchangeTicker "MCHI"
             :fundName "iShares MSCI China ETF"
             :inceptionDate {:d "Mar 29, 2011" :r 20110329}
             :totalNetAssets {:d "5,565,073,770" :r 5565073769.7}
             :productPageUrl "/us/products/239619/ishares-msci-china-etf"}
   "239620" {:portfolioId 239620
             :localExchangeTicker "IVV"
             :fundName "iShares Core S&P 500 ETF"
             :inceptionDate {:d "May 15, 2000" :r 20000515}
             :totalNetAssets {:d "600,000,000,000" :r 6.0E11}
             :productPageUrl "/us/products/239620/ishares-core-sp-500-etf"}})

(deftest ->fund-test
  (is (= {:portfolio-id 239619
          :ticker "MCHI"
          :name "iShares MSCI China ETF"
          :inception-date "Mar 29, 2011"
          :net-assets "5,565,073,770"
          :net-assets-value 5565073769.7
          :product-url "/us/products/239619/ishares-msci-china-etf"}
         (screener/->fund (get raw "239619")))))

(deftest parse-funds-sorts-by-net-assets-desc
  (let [funds (screener/parse-funds raw)]
    (is (= ["IVV" "MCHI"] (map :ticker funds)))
    (is (= ["600,000,000,000" "5,565,073,770"] (map :net-assets funds)))))