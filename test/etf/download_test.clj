(ns etf.download-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [etf.download :as download])
  (:import [java.time LocalDate]))

(def funds
  [{:ticker "IVV"
    :shares-outstanding "1,148,300,000"
    :share-outstanding-date "Nov 02, 2026"
    :exchange "NYSE Arca"
    :number-of-holdings "504"
    :30-d-avg-volume "4,720,358.00"
    :net-assets "867,098,495,687"}
   {:ticker "AGG"
    :shares-outstanding "139,000,000"
    :share-outstanding-date "Nov 02, 2026"
    :number-of-holdings "12,345"
    :30-d-avg-volume "5,000,000.00"
    :net-assets "139,555,967,442"}
   {:ticker "OLD"
    :shares-outstanding "1"
    :share-outstanding-date "Nov 01, 2026"
    :number-of-holdings "10"
    :30-d-avg-volume "1.00"
    :net-assets "2"}
   {:ticker "NODATE"
    :shares-outstanding "3"
    :number-of-holdings "4"}])

(deftest snapshot-keeps-latest-date-only
  (is (= [{:ticker "AGG"
           :date "2026-11-02"
           :shares-outstanding 139000000
           :number-of-holdings 12345
           :30-d-avg-volume 5000000
           :net-assets 139555967442}
          {:ticker "IVV"
           :date "2026-11-02"
           :shares-outstanding 1148300000
           :number-of-holdings 504
           :30-d-avg-volume 4720358
           :net-assets 867098495687}]
         (download/snapshot funds))))

(deftest download-current-date-writes-snapshot
  (let [out  (str (System/getProperty "java.io.tmpdir")
                  "/etf-day-" (System/nanoTime) ".edn")
        res  (download/download-current-date
              {:date (LocalDate/of 2026 11 3) :funds funds :out out})
        data (edn/read-string (slurp out))]
    (is (= {:out out :date "2026-11-03" :count 2} res))
    (is (vector? data))
    (is (= ["AGG" "IVV"] (mapv :ticker data)))
    (is (= ["2026-11-02" "2026-11-02"] (mapv :date data)))
    (is (every? #(every? integer? (vals (dissoc % :ticker :date))) data))
    (io/delete-file out true)))