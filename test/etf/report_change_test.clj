(ns etf.report-change-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [etf.report-change :as rc]))

(defn- write-snapshot! [dir name rows]
  (let [f (io/file dir name)]
    (io/make-parents f)
    (spit f (with-out-str (pprint/pprint rows)))))

(defn- temp-dir []
  (str (System/getProperty "java.io.tmpdir")
       "/etf-report-change-" (System/nanoTime)))

(defn- write-fixture! [dir]
  (write-snapshot! dir "day-2026-11-03.edn"
                   [{:ticker "AAA" :date "2026-11-02" :shares-outstanding 10}
                    {:ticker "BBB" :date "2026-11-02" :shares-outstanding 5}
                    {:ticker "CCC" :date "2026-11-02" :shares-outstanding 1}
                    {:ticker "EEE" :date "2026-11-02" :shares-outstanding 9}])
  (write-snapshot! dir "day-2026-11-04.edn"
                   [{:ticker "AAA" :date "2026-11-03" :shares-outstanding 12}
                    {:ticker "BBB" :date "2026-11-03" :shares-outstanding 5}
                    {:ticker "CCC" :date "2026-11-03" :shares-outstanding 2}])
  (write-snapshot! dir "day-2026-11-05.edn"
                   [{:ticker "AAA" :date "2026-11-04" :shares-outstanding 12}
                    {:ticker "BBB" :date "2026-11-04" :shares-outstanding 5}
                    {:ticker "CCC" :date "2026-11-04" :shares-outstanding 3}
                    {:ticker "DDD" :date "2026-11-04" :shares-outstanding 7}])
  dir)

(defn- delete-fixture! [dir]
  (doseq [n ["day-2026-11-03.edn" "day-2026-11-04.edn" "day-2026-11-05.edn"]]
    (io/delete-file (io/file dir n) true)))

(deftest reports-only-tickers-whose-shares-change
  (let [dir (temp-dir)]
    (write-fixture! dir)
    (try
      (is (= ["AAA" "CCC"] (rc/changed-tickers dir)))
      (is (= 2 (count (rc/changed-observations (rc/day-files dir)))))
      (is (= 2 (rc/report-changes {:dir dir})))
      (finally
        (delete-fixture! dir)))))

(deftest daily-change-compares-two-snapshots
  (let [dir (temp-dir)]
    (write-fixture! dir)
    (try
      (with-redefs [rc/default-data-dir dir]
        (is (= [{:ticker "AAA" :share-change 2}
                {:ticker "CCC" :share-change 1}]
               (rc/daily-change "2026-11-03" "2026-11-04"))))
      (is (= [{:ticker "CCC" :share-change 1}]
             (rc/daily-change dir "2026-11-04" "2026-11-05")))
      (finally
        (delete-fixture! dir)))))

(deftest change-jobs-lists-consecutive-snapshot-pairs
  (let [dir (temp-dir)]
    (write-fixture! dir)
    (try
      (is (= [{:day-prior "2026-11-03" :day-current "2026-11-04"}
              {:day-prior "2026-11-04" :day-current "2026-11-05"}]
             (rc/change-jobs dir)))
      (finally
        (delete-fixture! dir)))))

(deftest aggregate-daily-changes-tags-each-change-with-its-day
  (let [dir (temp-dir)]
    (write-fixture! dir)
    (try
      (is (= [{:ticker "AAA" :share-change 2 :day "2026-11-03"}
              {:ticker "CCC" :share-change 1 :day "2026-11-03"}
              {:ticker "CCC" :share-change 1 :day "2026-11-04"}]
             (rc/aggregate-daily-changes dir)))
      (finally
        (delete-fixture! dir)))))

(deftest percent-changes-are-relative-to-prior
  (let [dir (temp-dir)]
    (write-fixture! dir)
    (try
      (is (= [{:ticker "AAA" :share-change 20.0}
              {:ticker "CCC" :share-change 100.0}]
             (rc/daily-change dir "2026-11-03" "2026-11-04" {:percent true})))
      (is (= [{:ticker "AAA" :share-change 20.0 :day "2026-11-03"}
              {:ticker "CCC" :share-change 100.0 :day "2026-11-03"}
              {:ticker "CCC" :share-change 50.0 :day "2026-11-04"}]
             (rc/aggregate-daily-changes dir {:percent true})))
      (let [result (volatile! nil)]
        (with-out-str
          (vreset! result (rc/show-pivot (rc/aggregate-daily-changes dir {:percent true}))))
        (is (= [{:ticker "AAA" "2026-11-03" 20.0 "2026-11-04" nil}
                {:ticker "CCC" "2026-11-03" 100.0 "2026-11-04" 50.0}]
               @result)))
      (finally
        (delete-fixture! dir)))))

(deftest percent-skips-zero-prior
  (let [dir (temp-dir)]
    (write-snapshot! dir "day-2026-11-03.edn"
                     [{:ticker "ZERO" :date "2026-11-02" :shares-outstanding 0}
                      {:ticker "OK" :date "2026-11-02" :shares-outstanding 2}])
    (write-snapshot! dir "day-2026-11-04.edn"
                     [{:ticker "ZERO" :date "2026-11-03" :shares-outstanding 5}
                      {:ticker "OK" :date "2026-11-03" :shares-outstanding 3}])
    (try
      (is (= [{:ticker "OK" :share-change 50.0}]
             (rc/daily-change dir "2026-11-03" "2026-11-04" {:percent true})))
      (is (= [{:ticker "OK" :share-change 1}
              {:ticker "ZERO" :share-change 5}]
             (rc/daily-change dir "2026-11-03" "2026-11-04")))
      (finally
        (delete-fixture! dir)))))

(deftest show-pivot-pivots-changes-by-ticker-and-day
  (let [dir (temp-dir)]
    (write-fixture! dir)
    (try
      (let [result  (volatile! nil)
            printed (with-out-str
                      (vreset! result (rc/show-pivot (rc/aggregate-daily-changes dir))))]
        (is (= [{:ticker "AAA" "2026-11-03" 2 "2026-11-04" nil}
                {:ticker "CCC" "2026-11-03" 1 "2026-11-04" 1}]
               @result))
        (is (re-find #"AAA" printed))
        (is (re-find #"2026-11-03" printed)))
      (finally
        (delete-fixture! dir)))))

(deftest percent-pivot-keeps-only-rows-with-a-change-of-at-least-one-percent
  (let [dir (temp-dir)]
    (write-snapshot! dir "day-2026-11-03.edn"
                     [{:ticker "BIG" :date "2026-11-02" :shares-outstanding 100}
                      {:ticker "SMALL" :date "2026-11-02" :shares-outstanding 1000}])
    (write-snapshot! dir "day-2026-11-04.edn"
                     [{:ticker "BIG" :date "2026-11-03" :shares-outstanding 102}
                      {:ticker "SMALL" :date "2026-11-03" :shares-outstanding 1001}])
    (write-snapshot! dir "day-2026-11-05.edn"
                     [{:ticker "BIG" :date "2026-11-04" :shares-outstanding 103}
                      {:ticker "SMALL" :date "2026-11-04" :shares-outstanding 1002}])
    (try
      (let [result (volatile! nil)]
        (with-out-str
          (vreset! result (rc/show-pivot (rc/aggregate-daily-changes dir {:percent true})
                                         {:percent true})))
        (is (= [{:ticker "BIG"
                 "2026-11-03" 2.0
                 "2026-11-04" 1.0}]
               @result)))
      (let [absolute (volatile! nil)]
        (with-out-str
          (vreset! absolute (rc/show-pivot (rc/aggregate-daily-changes dir))))
        (is (= ["BIG" "SMALL"] (mapv :ticker @absolute))))
      (finally
        (delete-fixture! dir)))))

(deftest percent-values-are-rounded-to-one-decimal
  (let [dir (temp-dir)]
    (write-snapshot! dir "day-2026-11-03.edn"
                     [{:ticker "T" :date "2026-11-02" :shares-outstanding 300}])
    (write-snapshot! dir "day-2026-11-04.edn"
                     [{:ticker "T" :date "2026-11-03" :shares-outstanding 301}])
    (try
      (is (= [{:ticker "T" :share-change 0.3}]
             (rc/daily-change dir "2026-11-03" "2026-11-04" {:percent true})))
      (finally
        (delete-fixture! dir)))))
