(ns jepsen.cockroach.auto
  "Cockroach automation functions, for starting, stopping, etc."
  (:require [clojure.tools.logging :refer :all]
            [jepsen.util :as util]
            [jepsen [core :as jepsen]
                    [control :as c :refer [|]]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(def cockroach-user "User to run cockroachdb as" "cockroach")

(def tcpdump "Local path to tcpdump binary" "/usr/sbin/tcpdump")

(def jdbc-mode
  "Possible values:
  :pg-local  Send the test SQL to a PostgreSQL database.
  :cdb-local  Send the test SQL to a preconfigured local CockroachDB instance.
  :cdb-cluster Send the test SQL to the CockroachDB cluster set up by the framework."
  :cdb-cluster)

;; CockroachDB user and db name for jdbc-mode = :cdb-*
(def db-user "root")
(def db-passwd "dummy")
(def db-port 26257)
(def dbname "jepsen") ; will get created automatically

;; Paths
(def working-path "Home directory for cockroach setup" "/opt/cockroach")
(def cockroach "Cockroach binary" (str working-path "/cockroach"))
(def store-path "Cockroach data dir" (str working-path "/cockroach-data"))
(def pidfile "Cockroach PID file" (str working-path "/pid"))

; Logs
(def log-path "Log directory" (str working-path "/logs"))
(def verlog "Version log file" (str log-path "/version.txt"))
(def pcaplog "pcap log file" (str log-path "/trace.pcap"))
(def errlog (str log-path "/cockroach.stderr"))
(def log-files (if (= jdbc-mode :cdb-cluster) [errlog verlog pcaplog] []))

(def insecure
  "Whether to start the CockroachDB cluster in insecure mode (SSL disabled). May be useful to capture the network traffic between client and server."
  true)

;; Extra command-line arguments to give to `cockroach start`
(def cockroach-start-arguments
  (concat [:start
           ;; ... other arguments here ...
           ]
          (if insecure [:--insecure] [])))

(defn control-addr
  "Address of the Jepsen control node, as seen by the local node. Used to
  filter packet captures."
  []
  ; We drop any sudo binding here to make sure we aren't looking at a subshell
  (let [line (binding [c/*sudo* nil]
               (c/exec :env | :grep "SSH_CLIENT"))
        matches (re-find #"SSH_CLIENT=(.+?)\s" line)]
    (assert matches)
    (matches 1)))

(defn packet-capture!
  "Starts a packet capture."
  [node]
  (info node "Starting packet capture (filtering on" (control-addr) ")...")
  (c/su (c/exec :start-stop-daemon
                :--start :--background
                :--exec tcpdump
                :--
                :-w pcaplog :host (control-addr)
                :and :port db-port)))

(defn save-version!
  "Logs cockroach version to a local file."
  [node]
  (c/exec cockroach :version :> verlog (c/lit "2>&1")))

(defn wrap-env
  [env cmd]
  ["env" env cmd])

(defn cockroach-start-cmdline
  "Construct the command line to start a CockroachDB node."
  [& extra-args]
  (concat
   [:env
    ;;:COCKROACH_TIME_UNTIL_STORE_DEAD=5s
    :start-stop-daemon
    :--start :--background
    :--make-pidfile
    :--remove-pidfile
    :--pidfile pidfile
    :--no-close
    :--chuid cockroach-user
    :--chdir working-path
    :--exec (c/expand-path cockroach)
    :--]
   cockroach-start-arguments
   extra-args
   [:--logtostderr :true :>> errlog (c/lit "2>&1")]))

(defmacro csql! [& body]
  "Execute SQL statements using the cockroach sql CLI."
  `(c/cd working-path
         (c/exec
          (concat
           [cockroach :sql]
           (if insecure [:--insecure] nil)
           [:-e ~@body]
           [:>> errlog (c/lit "2>&1")]))))

(defn install!
  "Installs CockroachDB on the given node. Test should include a :tarball url
  the tarball."
  [test node]
  (c/su
    (debian/install [:tcpdump :ntpdate])
    (cu/ensure-user! cockroach-user)
    (cu/install-tarball! node (:tarball test) working-path false)
    (c/exec :mkdir :-p working-path)
    (c/exec :mkdir :-p log-path)
    (c/exec :chown :-R (str cockroach-user ":" cockroach-user) working-path))
  (info node "Cockroach installed"))

(defn init!
  "Initialize Cockroach--used on the primary node."
  [node]
  (c/sudo cockroach-user
          (info node "Starting CockroachDB once to initialize cluster")
          (c/exec (cockroach-start-cmdline nil))

          (Thread/sleep 1000)

          (info node "Stopping 1st CockroachDB before starting cluster")
          (c/exec cockroach :quit (if insecure [:--insecure] []))))

(defn runcmd
  "The command to run cockroach for a given test"
  [test]
  (wrap-env [(str "COCKROACH_LINEARIZABLE="
                 (if (:linearizable test) "true" "false"))
             (str "COCKROACH_MAX_OFFSET=" "250ms")]
            (cockroach-start-cmdline
              [(str "--join=" (name (jepsen/primary test)))])))

(defn start!
  "Start cockroachdb on node."
  [test node]
  (c/sudo cockroach-user
          (if (not= "" (try
                         (c/exec :pgrep :cockroach)
                         (catch RuntimeException e "")))
            (info node "Cockroach already running.")
            (do (info node "Starting CockroachDB...")
                (c/trace (c/exec (runcmd test)))
                (info node "Cochroach started")))))

(def ntpserver "ntp.ubuntu.com")

(defn reset-clock!
  "Reset clock on this host."
  []
  (c/su (c/exec :ntpdate :-b ntpserver))
  (info c/*host* "clock reset"))

(defn reset-clocks!
  "Reset all clocks on all nodes in a test"
  [test]
  (c/with-test-nodes test (reset-clock!)))
