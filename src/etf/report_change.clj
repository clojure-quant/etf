(ns etf.report-change
  "Report ETF tickers whose :shares-outstanding changed across daily snapshots.

  Snapshots are the data/day*.edn files written by
  `etf.download/download-current-date`; each file is a vector of maps with
  :ticker, :date and :shares-outstanding."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def default-data-dir
  "Directory searched for daily snapshots."
  "data")

(defn day-files
  "data/day*.edn snapshot files under `dir`, ordered by filename.

  Filenames are ISO-dated (day-YYYY-MM-DD.edn), so lexical order is
  chronological."
  ([] (day-files default-data-dir))
  ([dir]
   (->> (or (.listFiles (io/file dir)) [])
        (filter (fn [f] (and (.isFile f)
                             (re-matches #"day.*\.edn" (.getName f)))))
        (sort-by (fn [f] (.getName f)))
        vec)))

(defn read-snapshot
  "Read one snapshot file into a vector of fund maps."
  [file]
  (edn/read-string (slurp file)))

(defn shares-by-ticker
  "Map of ticker -> vector of {:date :shares-outstanding}, in snapshot order."
  [files]
  (reduce (fn [acc file]
            (reduce (fn [acc {:keys [ticker date shares-outstanding]}]
                      (update acc ticker (fnil conj [])
                              {:date date :shares-outstanding shares-outstanding}))
                    acc
                    (read-snapshot file)))
          {}
          files))

(defn changed-observations
  "Sorted map of ticker -> observations for every ticker whose
  :shares-outstanding is not constant across `files`.

  With no argument, uses all data/day*.edn files."
  ([] (changed-observations (day-files)))
  ([files]
   (->> (shares-by-ticker files)
        (filter (fn [[_ obs]]
                  (not (apply = (map :shares-outstanding obs)))))
        (into (sorted-map)))))

(defn changed-tickers
  "Sorted sequence of tickers whose :shares-outstanding changed.

  With no argument, uses all data/day*.edn files; `dir` overrides the
  snapshot directory."
  ([] (keys (changed-observations)))
  ([dir] (keys (changed-observations (day-files dir)))))

(defn report-changes
  "Print each changed ticker with its observed date/shares values and return
  the number of changed tickers.

  Supports -X execution:  clojure -X:report-change
  Optional :dir overrides the snapshot directory (default \"data\")."
  [{:keys [dir] :or {dir default-data-dir}}]
  (let [obs (changed-observations (day-files dir))]
    (println (str (count obs) " tickers changed :shares-outstanding"))
    (doseq [[ticker entries] obs]
      (println (str "  " ticker " "
                    (str/join ", "
                              (map (fn [{:keys [date shares-outstanding]}]
                                     (str date "=" shares-outstanding))
                                   entries)))))
    (count obs)))

;; --- daily change jobs ------------------------------------------------------

(defn- snapshot-file
  "File for one ISO-dated snapshot, e.g. data/day-2026-11-03.edn."
  [dir day]
  (io/file dir (str "day-" day ".edn")))

(defn- read-snapshot-shares
  "Read a snapshot as {:day <:date> :shares {ticker shares-outstanding}}."
  [file]
  (let [rows (read-snapshot file)]
    {:day    (:date (first rows))
     :shares (into {} (map (juxt :ticker :shares-outstanding)) rows)}))

(defn- round-percent
  "Round a percentage to one decimal place."
  [x]
  (/ (Math/round (* 10.0 (double x))) 10.0))

(defn- daily-changes
  "Changes between two snapshots, tagged with the current snapshot's trading
  day. Returns {:day <current :date> :changes [{:ticker :share-change}]}.

  Only tickers present in both snapshots with numeric, differing
  shares-outstanding are reported; a ticker present in just one snapshot is
  not a change. With opts {:percent true}, :share-change is
  (current - prior) / prior * 100.0, rounded to one decimal, instead of
  (current - prior); a prior of zero is skipped because the percentage change
  is undefined."
  [dir day-prior day-current {:keys [percent]}]
  (let [prior        (read-snapshot-shares (snapshot-file dir day-prior))
        current      (read-snapshot-shares (snapshot-file dir day-current))
        prior-shares (:shares prior)
        changes      (->> (:shares current)
                          (keep (fn [[ticker shares]]
                                  (let [old (get prior-shares ticker)]
                                    (when (and (contains? prior-shares ticker)
                                               (number? old)
                                               (number? shares)
                                               (not= old shares)
                                               (or (not percent) (not (zero? old))))
                                      {:ticker ticker
                                       :share-change (if percent
                                                       (round-percent (* 100.0 (/ (- shares old) old)))
                                                       (- shares old))}))))
                          (sort-by :ticker)
                          vec)]
    {:day (:day current) :changes changes}))

(defn daily-change
  "Return the tickers whose :shares-outstanding changed between two daily
  snapshots.

  `day-prior` and `day-current` are ISO dates matching the snapshot filenames
  (e.g. \"2026-11-03\" for data/day-2026-11-03.edn). Returns a vector of
  {:ticker <string> :share-change <current - prior>} for tickers present in
  both snapshots whose shares differ.

  With two arguments the snapshots are read from `default-data-dir`; the
  three-argument arity reads from `dir`. Opts may set :percent true to return
  the change as a percentage of the prior value ((current - prior) / prior *
  100.0), rounded to one decimal."
  ([day-prior day-current]
   (daily-change default-data-dir day-prior day-current))
  ([dir day-prior day-current]
   (daily-change dir day-prior day-current {}))
  ([dir day-prior day-current opts]
   (:changes (daily-changes dir day-prior day-current opts))))

(defn- file-date
  "ISO date embedded in a day-YYYY-MM-DD.edn filename, or nil."
  [file]
  (second (re-matches #"^day-(\d{4}-\d{2}-\d{2})\.edn$" (.getName file))))

(defn change-jobs
  "Return each consecutive pair of snapshots as
  {:day-prior <date> :day-current <date>}, in chronological order.

  With no argument the snapshots come from `default-data-dir`; `dir`
  overrides it."
  ([] (change-jobs default-data-dir))
  ([dir]
   (->> (day-files dir)
        (keep file-date)
        (partition 2 1)
        (mapv (fn [[day-prior day-current]]
                {:day-prior day-prior :day-current day-current})))))

(defn aggregate-daily-changes
  "Run every change job and return a flat vector of every ticker that changed.

  Each entry is {:ticker <string> :day <current snapshot :date>
  :share-change <current - prior>}. With no argument the snapshots come from
  `default-data-dir`; `dir` overrides it. Opts may set :percent true to return
  each :share-change as a percentage of the prior value."
  ([] (aggregate-daily-changes default-data-dir))
  ([dir] (aggregate-daily-changes dir {}))
  ([dir opts]
   (->> (change-jobs dir)
        (mapcat (fn [{:keys [day-prior day-current]}]
                  (let [{:keys [day changes]} (daily-changes dir day-prior day-current opts)]
                    (map #(assoc % :day day) changes))))
        vec)))

(def min-percent-change
  "In percent mode, a pivot row is kept only if at least one day changed by at
  least this many percent (in absolute value)."
  1.0)

(defn show-pivot
  "Build and print a pivot table of daily share changes.

  Columns are :ticker followed by each day in ascending order; rows are one
  map per ticker, with the :share-change for that day (nil when the ticker did
  not change that day, printed as a blank cell). Prints the table with
  clojure.pprint/print-table and returns the row maps.

  Opts may set :percent true when the aggregate holds percentage changes; then
  only tickers with at least one day changing by >= `min-percent-change` are
  shown (all of a kept ticker's days are still shown)."
  ([aggregate-daily-changes]
   (show-pivot aggregate-daily-changes {}))
  ([aggregate-daily-changes {:keys [percent]}]
   (let [days      (->> aggregate-daily-changes (map :day) distinct sort vec)
         by-ticker (group-by :ticker aggregate-daily-changes)
         by-ticker (if percent
                     (into {} (filter (fn [[_ entries]]
                                        (some #(>= (Math/abs (double (:share-change %)))
                                                   min-percent-change)
                                              entries)))
                           by-ticker)
                     by-ticker)
         rows      (mapv (fn [ticker]
                           (let [changes (into {} (map (juxt :day :share-change))
                                               (get by-ticker ticker))]
                             (into (array-map :ticker ticker)
                                   (map (fn [day] [day (get changes day)]) days))))
                         (sort (keys by-ticker)))]
     (pprint/print-table (into [:ticker] days) rows)
     rows)))

(defn report-pivot
  "Print the pivot table of daily :shares-outstanding changes.

  Supports -X execution:  clojure -X:show-pivot
  Options:
    :dir     snapshot directory (default \"data\")
    :percent true to show each change as a percentage of the prior value"
  [{:keys [dir percent] :or {dir default-data-dir}}]
  (show-pivot (aggregate-daily-changes dir {:percent percent}) {:percent percent}))
