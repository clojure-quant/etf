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
