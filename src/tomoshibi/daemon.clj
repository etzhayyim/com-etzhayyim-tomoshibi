(ns tomoshibi.daemon
  "The resident entrypoint (bb) — binds tomoshibi.agent/tick!'s injected
  effects to the real world and loops forever. Residence is a launchd
  LaunchDaemon on a Murakumo fleet node (infra/launchd/
  com.etzhayyim.tomoshibi.agent.plist), NEVER `nohup … &` (root CLAUDE.md
  operational-code rule).

  Real I/O lives ONLY here:
    - babashka.http-client executes the pure request maps built by
      tomoshibi.mail (Worker pull + Resend send) and tomoshibi.organizer
      (node-local Ollama)
    - files under TOMOSHIBI_STATE_DIR hold the leash + journals
    - httpkit serves loopback healthz (fleet-probe convention: body carries
      \"ok\"/\"node\")

  Config is environment-only (no secrets on disk in the repo; the launchd
  plist on the node carries them — fleet.edn :operator_token_source
  convention):
    TOMOSHIBI_PULL_URL      tomoshibi-mail Worker base URL
    TOMOSHIBI_PULL_TOKEN    Worker pull bearer secret
    RESEND_API_KEY          outbound send
    TOMOSHIBI_OLLAMA_URL    default http://127.0.0.1:11434 (node-local)
    TOMOSHIBI_MODEL         default gemma4:12b-it-qat
    TOMOSHIBI_STATE_DIR     default ~/.etzhayyim/tomoshibi
    TOMOSHIBI_FROM          default tomoshibi@etzhayyim.com
    TOMOSHIBI_MAX_PER_DAY   default 20
    TOMOSHIBI_TICK_SECONDS  default 300
    TOMOSHIBI_HEALTHZ_PORT  default 13094"
  (:require [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [org.httpkit.server :as srv]
            [tomoshibi.agent :as agent]
            [tomoshibi.attest-sign :as attest]
            [tomoshibi.journal :as journal]
            [tomoshibi.mail :as mail]
            [tomoshibi.organizer :as organizer])
  (:import (java.time Instant)))

(defn- env [k default] (or (System/getenv k) default))

(defn- now-iso [] (str (Instant/now)))

(defn- read-file* [path]
  (let [f (io/file path)]
    (when (.exists f) (slurp f))))

(defn- append-line* [path line]
  (io/make-parents path)
  (spit path (str line "\n") :append true))

(defn- http-exec
  "Execute a pure request map ({:method :url :headers :json :timeout-ms}) →
  {:status … :body parsed-json-or-nil}. Never throws (fail-closed callers
  treat nil bodies as failure)."
  [{:keys [method url headers json timeout-ms] :or {timeout-ms 30000}}]
  (try
    (let [resp (http/request
                (cond-> {:method method :uri url :headers (or headers {})
                         :timeout timeout-ms :throw false}
                  json (-> (assoc :body (cheshire.core/generate-string json))
                           (assoc-in [:headers "Content-Type"] "application/json"))))]
      {:status (:status resp)
       :body (try (cheshire.core/parse-string (:body resp) true)
                  (catch Exception _ nil))})
    (catch Exception e
      {:status nil :error (ex-message e)})))

(defn config []
  (let [state-dir (env "TOMOSHIBI_STATE_DIR"
                       (str (System/getProperty "user.home") "/.etzhayyim/tomoshibi"))]
    {:pull {:pull-url (env "TOMOSHIBI_PULL_URL" nil)
            :pull-token (env "TOMOSHIBI_PULL_TOKEN" nil)}
     :resend-key (env "RESEND_API_KEY" nil)
     :ollama {:ollama-url (env "TOMOSHIBI_OLLAMA_URL" "http://127.0.0.1:11434")
              :model (env "TOMOSHIBI_MODEL" "gemma4:12b-it-qat")}
     :paths {:leash (str state-dir "/leash.edn")
             :processed (str state-dir "/processed.journal.edn")
             :suppress (str state-dir "/suppress.journal.edn")
             :budget (str state-dir "/budget.journal.edn")
             :ops (str state-dir "/ops.journal.edn")
             :attestations (str state-dir "/attestations.journal.edn")
             :sigrefs (str state-dir "/attestations.sigrefs.journal.edn")
             :kotoba-journal (str state-dir "/attestations.kotoba.journal.edn")}
     :cfg {:from-email (env "TOMOSHIBI_FROM" "tomoshibi@etzhayyim.com")
           :from-name "tomoshibi (灯)"
           :actor-did (env "TOMOSHIBI_ACTOR_DID" "did:web:etzhayyim.com:actor:tomoshibi")
           :max-per-day (parse-long (env "TOMOSHIBI_MAX_PER_DAY" "20"))
           :max-per-tick 5
           :max-attempts 3
           :body-max-chars 4000}
     :tick-seconds (parse-long (env "TOMOSHIBI_TICK_SECONDS" "300"))
     :healthz-port (parse-long (env "TOMOSHIBI_HEALTHZ_PORT" "13094"))}))

(defn- assert-config! [{:keys [pull resend-key]}]
  (doseq [[k v] [["TOMOSHIBI_PULL_URL" (:pull-url pull)]
                 ["TOMOSHIBI_PULL_TOKEN" (:pull-token pull)]
                 ["RESEND_API_KEY" resend-key]]]
    (when-not v
      (throw (ex-info (str "missing required env " k) {:env k})))))

(defn- open-store!
  "Attestation store backend. TOMOSHIBI_STORE=kotoba (default) → the
  kotoba-Datom-log store (tomoshibi.kotoba-store over etzhayyim.kotoba.engine,
  the repo-wide canonical-state substrate), seeded ONCE from the legacy
  FileStore rows so a backend switch never orphans ledger history; =file, or
  the engine missing from the classpath → FileStore. Fail-open: a storage
  upgrade must never take the actor down (fallback is logged to ops)."
  [paths]
  (let [file-store (journal/file-store read-file* append-line* (:attestations paths))]
    (if-not (= "kotoba" (env "TOMOSHIBI_STORE" "kotoba"))
      {:store file-store :backend :file}
      (try
        (let [open (requiring-resolve 'tomoshibi.kotoba-store/open)
              seed ((requiring-resolve 'tomoshibi.store/all-attestations) file-store)
              {:keys [store migrated]} (open (:kotoba-journal paths) seed)]
          {:store store :backend :kotoba :migrated migrated})
        (catch Exception e
          {:store file-store :backend :file :fallback (ex-message e)})))))

(defn build-ctx
  "Wire the real effects into an agent/tick! ctx."
  [{:keys [pull resend-key ollama paths cfg]}]
  (let [{:keys [store backend migrated fallback]} (open-store! paths)]
    (append-line* (:ops paths)
                  (pr-str (cond-> {:t :store-opened :backend backend :at (now-iso)}
                            migrated (assoc :migrated migrated)
                            fallback (assoc :fallback fallback))))
    {:now-fn now-iso
   :read-file read-file*
   :append-line append-line*
   :paths paths
   :cfg cfg
   :store store
   :fetch! (fn [limit]
             (let [{:keys [status body]} (http-exec (mail/fetch-request pull limit))]
               (if (and status (< status 300) body)
                 (vec (mail/parse-inbox body))
                 (do (append-line* (:ops paths)
                                   (pr-str {:t :fetch-failed :status status :at (now-iso)}))
                     []))))
   :ack! (fn [kv-key]
           (http-exec (mail/ack-request pull kv-key)))
   :draft! (fn [summary]
             (let [{:keys [status body]} (http-exec (organizer/draft-request ollama summary))]
               (when (and status (< status 300))
                 (organizer/parse-draft body))))
   :send! (fn [reply-msg]
            (let [req (mail/send-request reply-msg)
                  {:keys [status body]} (http-exec
                                         {:method :post
                                          :url (:http/url req)
                                          :headers {"Authorization" (str "Bearer " resend-key)}
                                          :json (:http/json req)})]
              (mail/parse-send-response status body)))
   ;; Sign the just-written attestation's head with the node-held did:key via
   ;; the JVM helper (scripts/sign_head.clj — Ed25519 needs a real JDK, not
   ;; bb's SCI). Runs once per committed send (≤ daily budget), so subprocess
   ;; startup is irrelevant. Fail-open on SIGNING only: helper down → an
   ;; explicit unsigned sigref (:sig nil) + :sign-failed ops line; the reply
   ;; and the attestation are never blocked.
   :attest-sign!
   (fn [attestation]
     (let [head (attest/attestation-head attestation)
           signer (try
                    (let [{:keys [out exit]} (p/shell {:out :string :err :string
                                                       :continue true :timeout 60000}
                                                      "clojure" "-M" "-m" "sign-head" head)]
                      (when (zero? exit)
                        (let [r (edn/read-string out)]
                          (when (attest/valid-signer? r) r))))
                    (catch Exception _ nil))]
       (append-line* (:sigrefs paths)
                     (pr-str (attest/sigref head signer (:actor-did cfg) (now-iso))))
       (when-not signer
         (append-line* (:ops paths)
                       (pr-str {:t :sign-failed :head head :at (now-iso)})))))}))

(defonce health (atom {:ticks 0 :last-tick-at nil :last-outcomes nil :started-at nil}))

(defn- healthz-handler [_req]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
          (merge {:ok true :cell "TomoshibiEvangelismMailCell"
                  :node (or (System/getenv "ETZHAYYIM_NODE_NAME") "unknown")}
                 @health))})

(defn run-tick!
  "One crash-isolated tick (organism `safe` pattern — a throwing tick logs and
  never kills the daemon)."
  [ctx ops-path]
  (try
    (let [result (agent/tick! ctx)]
      (swap! health #(-> % (update :ticks inc)
                         (assoc :last-tick-at (now-iso)
                                :last-outcomes (frequencies (map :outcome (:outcomes result)))
                                :leash (:leash result))))
      (append-line* ops-path (pr-str {:t :tick :at (now-iso)
                                      :leash (:leash result)
                                      :outcomes (frequencies (map :outcome (:outcomes result)))}))
      result)
    (catch Exception e
      (append-line* ops-path (pr-str {:t :tick-error :at (now-iso) :error (ex-message e)}))
      {:error (ex-message e)})))

(defn -main [& args]
  (let [conf (config)
        _ (assert-config! conf)
        ctx (build-ctx conf)
        ops (get-in conf [:paths :ops])
        once? (some #{"--once"} args)]
    (swap! health assoc :started-at (now-iso))
    (if once?
      (let [r (run-tick! ctx ops)]
        (println (pr-str r))
        (System/exit (if (:error r) 1 0)))
      (do
        (srv/run-server healthz-handler {:ip "127.0.0.1" :port (:healthz-port conf)})
        (println (str "tomoshibi.daemon resident; healthz 127.0.0.1:" (:healthz-port conf)
                      " tick every " (:tick-seconds conf) "s"))
        (loop []
          (run-tick! ctx ops)
          (Thread/sleep (* 1000 (:tick-seconds conf)))
          (recur))))))
