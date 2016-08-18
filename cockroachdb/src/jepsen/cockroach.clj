(ns jepsen.cockroach
  "Tests for CockroachDB"
  (:require [clojure.tools.logging :refer :all]
            [clojure.java.jdbc :as j]
            [clojure.core.reducers :as r]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [jepsen
             [core :as jepsen]
             [db :as db]
             [os :as os]
             [tests :as tests]
             [control :as c :refer [|]]
             [store :as store]
             [nemesis :as nemesis]
             [generator :as gen]
             [independent :as independent]
             [reconnect :as rc]
             [util :as util :refer [meh]]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as cn]
            [jepsen.os.ubuntu :as ubuntu]
            [jepsen.cockroach [nemesis :as cln]
                              [auto :as auto :refer [cockroach-user
                                                     cockroach
                                                     jdbc-mode
                                                     db-port
                                                     insecure
                                                     db-user
                                                     db-passwd
                                                     store-path
                                                     dbname
                                                     verlog
                                                     log-files
                                                     pcaplog]]]))

(import [java.net URLEncoder])

;; timeout for DB operations during tests
(def timeout-delay 10000) ; milliseconds

;; number of simultaneous clients
(def concurrency-factor 30)

;; Isolation level to use with test transactions.
(def isolation-level :serializable)

;; for secure mode
(def client-cert "certs/node.client.crt")
(def client-key "certs/node.client.pk8")
(def ca-cert "certs/ca.crt")

;; Postgres user and dbname for jdbc-mode = :pg-*
(def pg-user "kena") ; must already exist
(def pg-passwd "kena") ; must already exist
(def pg-dbname "mydb") ; must already exist

;;;;;;;;;;;;;;;;;;;; Database set-up and access functions  ;;;;;;;;;;;;;;;;;;;;;;;

;; How to extract db time
(defn db-time
  "Retrieve the current time (precise, monotonic) from the database."
  [c]
  (cond (= jdbc-mode :pg-local)
        (->> (j/query c ["select extract(microseconds from now()) as ts"]
                      :row-fn :ts)
             (first)
             (str))

        true
        (->> (j/query c ["select cluster_logical_timestamp()*10000000000::decimal as ts"]
                      :row-fn :ts)
             (first)
             (.toBigInteger)
             (str))))

(def ssl-settings
  (if insecure
    ""
    (str "?ssl=true"
         "&sslcert=" client-cert
         "&sslkey=" client-key
         "&sslrootcert=" ca-cert
         "&sslfactory=org.postgresql.ssl.jdbc4.LibPQFactory")))

(defn db-conn-spec
  "Assemble a JDBC connection specification for a given Jepsen node."
  [node]
  (merge {:classname    "org.postgresql.Driver"
          :subprotocol  "postgresql"}
         (case jdbc-mode
           :cdb-cluster
           {:subname     (str "//" (name node) ":" db-port "/" dbname
                              ssl-settings)
            :user        db-user
            :password    db-passwd}

           :cdb-local
           {:subname     (str "//localhost:" db-port "/" dbname ssl-settings)
            :user        db-user
            :password    db-passwd}

           :pg-local
           {:subname     (str "//localhost/" pg-dbname)
            :user        pg-user
            :password    pg-passwd})))

(defn close-conn
  "Given a JDBC connection, closes it and returns the underlying spec."
  [conn]
  (when-let [c (j/db-find-connection conn)]
    (.close c))
  (dissoc conn :connection))

(defn client
  "Constructs a network client for a node, and opens it"
  [node]
  (rc/open!
    (rc/wrapper
      {:name [:cockroach node]
       :open (fn open []
               (let [spec (db-conn-spec node)
                     conn (j/get-connection spec)
                     spec' (j/add-connection spec conn)]
                 (assert spec')
                 spec'))
       :close close-conn
       :log? true})))

(defn db
  "Sets up and tears down CockroachDB."
  [opts]
  (reify db/DB
    (setup! [_ test node]
      (when (= node (jepsen/primary test))
        (store/with-out-file test "jepsen-version.txt"
          (meh (->> (sh "git" "describe" "--tags")
                    (:out)
                    (print)))))

      (when (= jdbc-mode :cdb-cluster)
        (auto/install! test node)
        (auto/reset-clock!)
        (jepsen/synchronize test)

        (c/sudo cockroach-user
                (when (= node (jepsen/primary test))
                  (auto/init! node))

                (jepsen/synchronize test)
                (auto/packet-capture! node)
                (auto/save-version! node)
                (auto/start! test node)

                (jepsen/synchronize test)

                (when (= node (jepsen/primary test))
                  (info node "Creating database...")
                  (auto/csql! (str "create database " dbname))))

        (info node "Setup complete")))

    (teardown! [_ test node]
      (when (= jdbc-mode :cdb-cluster)
        (auto/reset-clock!)

        (c/su
          (info node "Stopping cockroachdb...")
          (meh (c/exec :timeout :5s cockroach :quit
                       (if insecure [:--insecure] [])))
          (meh (c/exec :killall -9 :cockroach))

          (info node "Erasing the store...")
          (c/exec :rm :-rf store-path)

          (info node "Stopping tcpdump...")
          (meh (c/exec :killall -9 :tcpdump))

          (info node "Clearing the logs...")
          (doseq [f log-files]
            (when (cu/exists? f)
              (c/exec :truncate :-c :--size 0 f)
              (c/exec :chown cockroach-user f))))))

    db/LogFiles
    (log-files [_ test node] log-files)))

(defn with-idempotent
  "Takes a predicate on operation functions, and an op, presumably resulting
  from a client call. If (idempotent? (:f op)) is truthy, remaps :info types to
  :fail."
  [idempotent? op]
  (if (and (idempotent? (:f op)) (= :info (:type op)))
    (assoc op :type :fail)
    op))

(defmacro with-timeout
  "Like util/timeout, but throws (RuntimeException. \"timeout\") for timeouts.
  Throwing means that when we time out inside a with-conn, the connection state
  gets reset, so we don't accidentally hand off the connection to a later
  invocation with some incomplete transaction."
  [& body]
  `(util/timeout timeout-delay
                 (throw (RuntimeException. "timeout"))
                 ~@body))

(defmacro with-txn-retry
  "Catches PSQL 'restart transaction' errors and retries body a bunch of times,
  with exponential backoffs."
  [& body]
  `(util/with-retry [attempts# 30
                     backoff# 20]
     ~@body
     (catch org.postgresql.util.PSQLException e#
       (if (and (pos? attempts#)
                (re-find #"ERROR: restart transaction"
                         (.getMessage e#)))
         (do (Thread/sleep backoff#)
             (~'retry (dec attempts#)
                      (* backoff# (+ 4 (* 0.5 (- (rand) 0.5))))))
         (throw e#)))))

(defmacro with-txn
  "Wrap a evaluation within a SQL transaction."
  [[c conn] & body]
  `(j/with-db-transaction [~c ~conn :isolation isolation-level]
     ~@body))

(defn exception->op
  "Takes an exception and maps it to a partial op, like {:type :info, :error
  ...}. nil if unrecognized."
  [e]
  (let [m (.getMessage e)]
    (condp instance? e
      java.sql.SQLTransactionRollbackException
      {:type :fail, :error [:rollback m]}

      java.sql.BatchUpdateException
      (let [m' (if (re-find #"getNextExc" m)
                 (.getMessage (.getNextException e))
                 m)]
        {:type :info, :error [:batch-update m']})

      org.postgresql.util.PSQLException
      {:type :info, :error [:psql-exception m]}

      (condp re-find m
        #"^timeout$"
        {:type :info, :error :timeout}

        nil))))

(defmacro with-exception->op
  "Takes an operation and a body. Evaluates body, catches exceptions, and maps
  them to ops with :type :info and a descriptive :error."
  [op & body]
  `(try ~@body
        (catch Exception e#
          (if-let [ex-op# (exception->op e#)]
            (merge ~op ex-op#)
            (throw e#)))))

(defn wait-for-conn
  "Spins until a client is ready. Somehow, I think exceptions escape from this."
  [client]
  (util/timeout 60000 (throw (RuntimeException. "Timed out waiting for conn"))
                (while (try
                         (rc/with-conn [c client]
                           (j/query c ["select 1"])
                           false)
                         (catch RuntimeException e
                           true)))))

;;;;;;;;;;;;;;;;;;;;;;;; Common test definitions ;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn str->int [str]
  (let [n (read-string str)]
    (if (number? n) n nil)))

(defn basic-test
  "Sets up the test parameters common to all tests."
  [opts]
  (merge
    tests/noop-test
    {:nodes   (if (= jdbc-mode :cdb-cluster) (:nodes opts) [:localhost])
     :name    (str "cockroachdb-" (:name opts)
                   (if (:linearizable opts) "-lin" "")
                   (if (= jdbc-mode :cdb-cluster)
                     (str ":" (:name (:nemesis opts)))
                     "-fake"))
     :db      (db opts)
     :os      (if (= jdbc-mode :cdb-cluster) ubuntu/os os/noop)
     :client  (:client (:client opts))
     :nemesis (if (= jdbc-mode :cdb-cluster)
                (:client (:nemesis opts))
                nemesis/noop)
     :generator (gen/phases
                  (->> (gen/nemesis (:during (:nemesis opts))
                                    (:during (:client opts)))
                       (gen/time-limit (:time-limit opts)))
                  (gen/log "Nemesis terminating")
                  (gen/nemesis (:final (:nemesis opts)))
                  (gen/log "Waiting for quiescence")
                  (gen/sleep (:recovery-time opts))
                  ; Final client
                  (gen/clients (:final (:client opts))))}
    (dissoc opts :name :nodes :client :nemesis)))
