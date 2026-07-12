(ns tomoshibi.agent-test
  "Offline behavioral suite for one resident tick — every effect mocked, the
  REAL governor/evangelism-gate in the loop (same wiring as production)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tomoshibi.agent :as agent]
            [tomoshibi.journal :as journal]
            [tomoshibi.leash :as leash]
            [tomoshibi.mail :as mail]
            [tomoshibi.store :as store]))

(def now "2026-07-12T10:00:00Z")

(defn worker-msg [id from subject text & [headers]]
  {:key (str "inbox:k-" id)
   :value {:provider "cloudflare-email-routing"
           :provider_message_id id
           :from from
           :to ["tomoshibi@etzhayyim.com"]
           :subject subject
           :text text
           :headers (or headers {})
           :received_at now
           :spf "pass" :dkim "pass" :dmarc "pass"
           :attachments []}})

(defn harness
  "Build a fully-mocked tick ctx. opts:
    :msgs      worker-shaped inbox fixtures
    :draft-fn  (fn [summary] text|nil)
    :leash?    default true
    :send-ok?  default true
    :cfg       overrides"
  [{:keys [msgs draft-fn leash? send-ok? cfg]
    :or {msgs [] draft-fn (constantly "clean draft") leash? true send-ok? true}}]
  (let [files (atom {})
        read-file (fn [path] (get @files path))
        append-line (fn [path line] (swap! files update path str line "\n"))
        sent (atom [])
        acked (atom #{})
        fetches (atom 0)
        mem-store (store/seed-db)
        paths {:leash "leash.edn" :processed "processed.edn" :suppress "suppress.edn"
               :budget "budget.edn" :ops "ops.edn"}]
    (when leash?
      (swap! files assoc "leash.edn" (leash/leash-line "test-owner" now)))
    {:files files :sent sent :acked acked :fetches fetches :mem-store mem-store
     :ctx {:now-fn (constantly now)
           :read-file read-file
           :append-line append-line
           :paths paths
           :store mem-store
           :cfg (merge {:from-email "tomoshibi@etzhayyim.com"
                        :from-name "tomoshibi (灯)"
                        :actor-did "did:test:tomoshibi"
                        :max-per-day 20 :max-per-tick 5 :max-attempts 3
                        :body-max-chars 4000}
                       cfg)
           :fetch! (fn [limit] (swap! fetches inc)
                     (vec (take limit (mail/parse-inbox {:messages msgs}))))
           :ack! (fn [k] (swap! acked conj k))
           :draft! draft-fn
           :send! (fn [reply] (swap! sent conj reply)
                    (if send-ok? {:ok? true :id "re_test"} {:ok? false :status 500}))}}))

(deftest happy-path-replies-and-attests
  (let [{:keys [ctx sent acked mem-store]}
        (harness {:msgs [(worker-msg "<q1>" "seeker@example.com" "question"
                                     "What does etzhayyim believe?")]
                  :draft-fn (constantly "We believe in wellbecoming — warmly, and with no pressure at all.")})
        result (agent/tick! ctx)]
    (is (= :active (:leash result)))
    (is (= [:replied] (mapv :outcome (:outcomes result))))
    (testing "exactly one send, to the inbound sender"
      (is (= 1 (count @sent)))
      (is (= ["seeker@example.com"] (mapv :mail.address/email (:mail/to (first @sent))))))
    (testing "attestation written AFTER confirmed send, lexicon consts pinned"
      (let [[att] (store/all-attestations mem-store)]
        (is (= "digital" (:mode att)))
        (is (true? (:optOutAffordancePresent att)))
        (is (false? (:coercionAttested att)))
        (is (not (contains? att :to)))))
    (is (= #{"inbox:k-<q1>"} @acked))))

(deftest revoked-leash-does-nothing
  (let [{:keys [ctx sent fetches]}
        (harness {:leash? false
                  :msgs [(worker-msg "<q1>" "s@example.com" "hi" "hello")]})
        result (agent/tick! ctx)]
    (is (= :revoked (:leash result)))
    (is (empty? (:outcomes result)))
    (is (zero? @fetches))
    (is (empty? @sent))))

(deftest stop-request-suppresses-forever
  (let [{:keys [ctx sent files]}
        (harness {:msgs [(worker-msg "<s1>" "done@example.com" "配信停止" "もう送らないでください")]})
        result (agent/tick! ctx)]
    (is (= [:suppressed] (mapv :outcome (:outcomes result))))
    (is (empty? @sent))
    (is (str/includes? (get @files "suppress.edn") "done@example.com"))
    (testing "a NEW inbound from the suppressed sender gets silence"
      (let [{:keys [ctx sent files]}
            (harness {:msgs [(worker-msg "<s2>" "done@example.com" "hello again" "tell me more")]})]
        (swap! files assoc "suppress.edn"
               "{:email \"done@example.com\" :at \"t\" :reason :stop-request}\n")
        (let [r2 (agent/tick! ctx)]
          (is (= [:already-suppressed] (mapv :outcome (:outcomes r2))))
          (is (empty? @sent)))))))

(deftest gate-violating-draft-is-held-never-sent
  (let [{:keys [ctx sent acked mem-store files]}
        (harness {:msgs [(worker-msg "<h1>" "target@example.com" "hi" "tell me about your faith")]
                  :draft-fn (constantly "You must join now or else you will suffer.")})
        result (agent/tick! ctx)]
    (is (= [:held] (mapv :outcome (:outcomes result))))
    (testing "HELD is structural: no send, no attestation, hold-fact logged, acked"
      (is (empty? @sent))
      (is (empty? (store/all-attestations mem-store)))
      (is (str/includes? (get @files "ops.edn") ":governor-hold"))
      (is (= #{"inbox:k-<h1>"} @acked)))))

(deftest auto-generated-mail-is-never-answered
  (let [{:keys [ctx sent]}
        (harness {:msgs [(worker-msg "<b1>" "MAILER-DAEMON@mx.example.com"
                                     "Undelivered" "bounce" {})
                         (worker-msg "<b2>" "human@example.com" "auto"
                                     "ooo" {:auto-submitted "auto-replied"})]})
        result (agent/tick! ctx)]
    (is (= [:auto :auto] (mapv :outcome (:outcomes result))))
    (is (empty? @sent))))

(deftest draft-failure-retries-then-gives-up
  (let [{:keys [ctx acked files]}
        (harness {:msgs [(worker-msg "<d1>" "s@example.com" "q" "question")]
                  :draft-fn (constantly nil)})]
    (testing "failed draft leaves the message unacked for a later tick"
      (is (= [:draft-failed] (mapv :outcome (:outcomes (agent/tick! ctx)))))
      (is (empty? @acked)))
    (testing "after max-attempts the actor gives up (acked, terminal)"
      (swap! files assoc "processed.edn"
             (str (journal/processed-entry "<d1>" :pending 3 now) "\n"))
      (is (= [:gave-up] (mapv :outcome (:outcomes (agent/tick! ctx)))))
      (is (= #{"inbox:k-<d1>"} @acked)))))

(deftest budget-cap-halts-sending
  (let [{:keys [ctx sent files]}
        (harness {:msgs [(worker-msg "<m1>" "a@example.com" "q1" "question one")
                         (worker-msg "<m2>" "b@example.com" "q2" "question two")]
                  :cfg {:max-per-day 1}})]
    (swap! files assoc "budget.edn"
           (str (journal/send-entry "<m0>" "re_0" "2026-07-12T01:00:00Z") "\n"))
    (let [result (agent/tick! ctx)]
      (testing "first message already exhausts today's budget; tick stops"
        (is (= [:budget-exhausted] (mapv :outcome (:outcomes result))))
        (is (empty? @sent))))))

(deftest already-processed-is-skipped
  (let [{:keys [ctx sent acked files]}
        (harness {:msgs [(worker-msg "<p1>" "s@example.com" "q" "question")]})]
    (swap! files assoc "processed.edn"
           (str (journal/processed-entry "<p1>" :replied 0 now) "\n"))
    (is (= [:skipped] (mapv :outcome (:outcomes (agent/tick! ctx)))))
    (is (empty? @sent))
    (is (= #{"inbox:k-<p1>"} @acked))))

(deftest send-failure-does-not-attest
  (let [{:keys [ctx acked mem-store]}
        (harness {:msgs [(worker-msg "<f1>" "s@example.com" "q" "question")]
                  :draft-fn (constantly "A warm and gentle answer, no pressure.")
                  :send-ok? false})
        result (agent/tick! ctx)]
    (is (= [:send-failed] (mapv :outcome (:outcomes result))))
    (testing "a failed send leaves NO attestation and no ack (retry later)"
      (is (empty? (store/all-attestations mem-store)))
      (is (empty? @acked)))))
