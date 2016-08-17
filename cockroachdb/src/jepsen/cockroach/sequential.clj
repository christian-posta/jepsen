(ns jepsen.cockroach.sequential
  "A sequential consistency test.

  Verify that client order is consistent with DB order by performing queries
  (in four distinct transactions) like

  A: insert x
  A: insert y
  B: read y
  B: read x

  A's process order enforces that x must be visible before y, so we should
  always read both or neither.

  Splits keys up onto different tables to make sure they fall in different
  shard ranges"
  (:refer-clojure :exclude [test])
  (:require [jepsen [cockroach :as c]
             [client :as client]
             [checker :as checker]
             [generator :as gen]
             [independent :as independent]
             [util :as util :refer [meh]]
             [reconnect :as rc]]
            [jepsen.cockroach.nemesis :as cln]
            [clojure.java.jdbc :as j]
            [clojure.core.reducers :as r]
            [clojure.set :as set]
            [clojure.tools.logging :refer :all]
            [knossos.model :as model]
            [knossos.op :as op]))

(def table-prefix "String prepended to all table names." "seq_")

(defn table-names
  "Names of all tables"
  [table-count]
  (map (partial str table-prefix) (range table-count)))

(defn key->table
  "Turns a key into a table id"
  [table-count k]
  (str table-prefix (mod (hash k) table-count)))

(defn subkeys
  "The subkeys used for a given key, in order."
  [key-count k]
  (mapv (partial str k "_") (range key-count)))

(defrecord Client [table-count table-created? client]
  client/Client

  (setup! [this test node]
    (Thread/sleep 2000)
    (let [client (c/client node)]
      (locking table-created?
        (when (compare-and-set! table-created? false true)
          (rc/with-conn [c client]
            (c/with-timeout
              (info "Creating tables" (pr-str (table-names table-count)))
              (doseq [t (table-names table-count)]
                (j/execute! c [(str "drop table if exists " t)])
                (j/execute! c [(str "create table " t " (key varchar(255))")])
                (info "Created table" t))))))

      (assoc this :client client)))

  (invoke! [this test op]
    (c/with-exception->op op
      (rc/with-conn [c client]
        (c/with-timeout
          (c/with-txn-retry
            (let [ks (subkeys (:key-count test) (:value op))]
              (case (:f op)
                :write (do (doseq [k ks]
                             (j/insert! c (key->table table-count k) {:key k}))
                           (assoc op :type :ok))

                :read ;(j/with-db-transaction [c c :isolation c/isolation-level]
                        (->> ks
                           reverse
                           (mapv (fn [k]
                                   (first
                                     (j/query c
                                              [(str "select key from "
                                                    (key->table table-count k)
                                                    " where key = ?") k]
                                              :row-fn :key))))
                           (vector (:value op))
                           (assoc op :type :ok, :value)))))))))

  (teardown! [this test]
    (try
      (rc/with-conn [c client]
        (c/with-timeout
          (doseq [t (table-names table-count)]
            (j/execute! c [(str "drop table " t)]))))

      (finally
        (rc/close! client)))))

(defn writes
  "We emit sequential integer keys for writes, logging the most recent n keys
  in the given atom, wrapping a PersistentQueue."
  [last-written]
  (let [k (atom -1)]
    (reify gen/Generator
      (op [this test process]
        (let [k (swap! k inc)]
          (swap! last-written #(-> % pop (conj k)))
          {:type :invoke, :f :write, :value k})))))

(defn reads
  "We use the last-written atom to perform a read of a randomly selected
  recently written value."
  [last-written]
  (gen/filter (comp complement nil? :value)
    (reify gen/Generator
      (op [this test process]
        {:type :invoke, :f :read, :value (rand-nth @last-written)}))))

(defn gen
  "Basic generator with n writers, and a buffer of 2n"
  [n]
  (let [last-written (atom
                       (reduce conj clojure.lang.PersistentQueue/EMPTY
                               (repeat (* 2 n) nil)))]
    (gen/reserve n (writes last-written)
                 (reads last-written))))

(defn trailing-nil?
  "Does the given sequence contain a nil anywhere after a non-nil element?"
  [coll]
  (some nil? (drop-while nil? coll)))

(defn checker
  []
  (reify checker/Checker
    (check [this test model history opts]
      (assert (integer? (:key-count test)))
      (let [reads (->> history
                       (r/filter op/ok?)
                       (r/filter #(= :read (:f %)))
                       (r/map :value)
                       (into []))
            none (filter (comp (partial every? nil?) second) reads)
            some (filter (comp (partial some nil?) second) reads)
            bad  (filter (comp trailing-nil? second) reads)
            all  (filter (fn [[k ks]]
                           (= (subkeys (:key-count test) k)
                             (reverse ks)))
                         reads)]
        {:valid?      (not (seq bad))
         :all-count   (count all)
         :some-count  (count some)
         :none-count  (count none)
         :bad-count   (count bad)
         :bad         bad}))))

(defn test
  [opts]
  (let [gen (gen 30)]
    (c/basic-test
      (merge
        {:name "sequential"
         :key-count 5
         :concurrency 90
         :client {:client (Client. 10 (atom false) nil)
                  :during (gen/stagger 1/100 gen)
                  :final  nil}
         :checker (checker/compose
                    {:perf (checker/perf)
                     :sequential (checker)})}
        opts))))
