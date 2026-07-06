;; The attestation-writing contract as executable tests. Invariant: only a
;; COMMITTED (EvangelismGovernor `:ok? true`) proposal ever produces an
;; evangelismActivityAttestation; a HELD proposal never does.
;; Run via run_tests.clj (bb run_tests.clj) — this ns has no standalone
;; -main; it is aggregated by the shared runner (kouhou/tashikame convention).
(ns tomoshibi.operation-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [tomoshibi.operation :as op]
            [tomoshibi.store :as store]))

(def ^:private now "2026-07-06T09:00:00Z")
(def ^:private cell-did "did:web:etzhayyim.com:actor:tomoshibi")

(defn- fresh [] (store/seed-db))

(defn- run [store text opts]
  (op/propose! store nil {:actor-id "tomoshibi"}
               (merge {:effect :assessment :text text} opts)
               now cell-did))

(deftest clean-invitation-commits-and-writes-attestation
  (testing "a clean, opt-out-flagged invitation commits and is recorded"
    (let [s (fresh)
          r (run s "We're gathering this month to share what our community has been building. Everyone is welcome."
                  {:opt-out-present? true :mode "digital" :actor-did cell-did})]
      (is (= :commit (:disposition r)))
      (is (some? (:attestation r)))
      (is (= 1 (count (store/all-attestations s))))
      (is (= (:attestation r) (first (store/all-attestations s)))))))

(deftest attestation-carries-structural-consts
  (testing "the written attestation pins all four STRUCTURAL const fields"
    (let [s (fresh)
          r (run s "We're gathering this month to share what our community has been building. Everyone is welcome."
                  {:opt-out-present? true :mode "digital" :actor-did cell-did})
          att (:attestation r)]
      (is (true?  (:optOutAffordancePresent att)))
      (is (false? (:coercionAttested att)))
      (is (false? (:minorSoloSolicitationAttested att)))
      (is (true?  (:voluntaryAttested att)))
      (is (= cell-did (:attestingCellDid att)))
      (is (= now (:createdAt att))))))

(deftest attestation-has-no-recipient-identifying-field
  (testing "the lexicon deliberately carries no recipient/outcome field — assert none sneaks in"
    (let [s (fresh)
          r (run s "We're gathering this month to share what our community has been building. Everyone is welcome."
                  {:opt-out-present? true :mode "digital" :actor-did cell-did})
          att (:attestation r)]
      (is (not (contains? att :recipientDid)))
      (is (not (contains? att :recipientResponseNoted)))
      (is (not (contains? att :recipientEmail))))))

(deftest held-proposal-writes-no-attestation
  (testing "missing opt-out → HOLD; no attestation is ever written"
    (let [s (fresh)
          r (run s "Come join our community gathering this weekend, everyone welcome."
                  {:mode "digital" :actor-did cell-did})]
      (is (= :hold (:disposition r)))
      (is (nil? (:attestation r)))
      (is (zero? (count (store/all-attestations s)))))))

(deftest coercive-proposal-writes-no-attestation
  (testing "coercive text → HOLD; store stays empty (never a false coercionAttested=false)"
    (let [s (fresh)
          r (run s "You must join now or else you will regret it forever."
                  {:opt-out-present? true :mode "digital" :actor-did cell-did})]
      (is (= :hold (:disposition r)))
      (is (zero? (count (store/all-attestations s)))))))

(deftest multiple-commits-append-only
  (testing "successive commits accumulate, oldest first"
    (let [s (fresh)]
      (run s "First gathering announcement, all welcome. No pressure to attend." {:opt-out-present? true :mode "digital" :actor-did cell-did})
      (run s "Second gathering announcement, all welcome. No pressure to attend." {:opt-out-present? true :mode "digital" :actor-did cell-did})
      (is (= 2 (count (store/all-attestations s)))))))

(deftest interpersonal-mode-carries-adherent-fields
  (testing "interpersonal mode records adherentDid/interpersonalMethod, not actorDid"
    (let [s (fresh)
          r (run s "We're gathering this month to share what our community has been building. Everyone is welcome."
                  {:opt-out-present? true :mode "interpersonal"
                   :adherent-did "did:web:etzhayyim.com:adherent:jun"
                   :interpersonal-method "face-to-face"})
          att (:attestation r)]
      (is (= :commit (:disposition r)))
      (is (= "did:web:etzhayyim.com:adherent:jun" (:adherentDid att)))
      (is (= "face-to-face" (:interpersonalMethod att)))
      (is (not (contains? att :actorDid))))))

;; ---------------------------------------------------------------------------
;; Lexicon-schema cross-check (reads the REAL lexicon file, not a hand-copy)
;; ---------------------------------------------------------------------------

(def ^:private lexicon-path
  "../root/00-contracts/lexicons/com/etzhayyim/apps/etzhayyim/evangelismActivityAttestation.json")

(deftest attestation-matches-real-lexicon-required-fields
  (testing "tomoshibi.store/lexicon-required-fields matches the actual lexicon file's `required` array"
    (let [lex (json/parse-string (slurp (io/file lexicon-path)) true)
          required (set (map keyword (get-in lex [:defs :main :record :required])))]
      (is (seq required) "sanity: the lexicon actually declares required fields")
      (is (= required store/lexicon-required-fields)))))

(deftest attestation-matches-real-lexicon-const-values
  (testing "the four STRUCTURAL const fields in the real lexicon match what tomoshibi writes"
    (let [lex (json/parse-string (slurp (io/file lexicon-path)) true)
          props (get-in lex [:defs :main :record :properties])
          const-of (fn [k] (get-in props [k :const]))]
      (is (= true  (const-of :optOutAffordancePresent)))
      (is (= false (const-of :coercionAttested)))
      (is (= false (const-of :minorSoloSolicitationAttested)))
      (is (= true  (const-of :voluntaryAttested))))))
