(ns etf.test-runner
  (:require [clojure.test :as t]
            [etf.download-test]
            [etf.product-test]
            [etf.report-change-test]
            [etf.screener-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'etf.product-test
                                          'etf.screener-test
                                          'etf.download-test
                                          'etf.report-change-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))