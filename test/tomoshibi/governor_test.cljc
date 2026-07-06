;; The invitational-content publication contract as executable tests.
;; Invariant: tomoshibi NEVER publishes an invitation the EvangelismGovernor
;; rejects; every published invitation is aggregate-first, opt-out-able,
;; non-coercive, and never solicits a minor alone (ADR-2607061700 §1.16).
;; Run via run_tests.clj (bb run_tests.clj) — this ns has no standalone
;; -main; it is aggregated by the shared runner (kouhou/tashikame convention).
(ns tomoshibi.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [tomoshibi.governor :as gov]))

(defn- check [text opts]
  (gov/check nil nil (merge {:effect :assessment :text text} opts)))

(deftest clean-invitation-with-opt-out-flag-commits
  (testing "a clean, opt-out-flagged invitation passes the governor"
    (let [v (check "We're gathering this month to share what our community has been building. Everyone is welcome."
                    {:opt-out-present? true})]
      (is (:ok? v) (str "expected commit, got: " v))
      (is (= :commit (gov/verdict->disposition v))))))

(deftest clean-invitation-with-textual-opt-out-commits
  (testing "textual opt-out language alone also satisfies the gate"
    (let [v (check "We're gathering this month to share what our community has been building. Everyone is welcome — no pressure, and feel free to say no."
                    {})]
      (is (:ok? v))
      (is (= :commit (gov/verdict->disposition v))))))

(deftest missing-opt-out-is-held
  (testing "an invitation with no opt-out affordance → HOLD"
    (let [v (check "Come join our community gathering this weekend, everyone welcome." {})]
      (is (false? (:ok? v)))
      (is (= :hold (gov/verdict->disposition v)))
      (is (some #{:evangelism-gate-hit} (mapv :rule (:violations v)))))))

(deftest individual-targeting-is-held
  (testing "individual-vulnerability targeting → HOLD, even with opt-out present"
    (let [v (check "Since you're going through a divorce, I picked you specifically to join us. No pressure though."
                    {:opt-out-present? true})]
      (is (false? (:ok? v)))
      (is (= :hold (gov/verdict->disposition v))))))

(deftest coercion-is-held
  (testing "coercive language → HOLD"
    (let [v (check "You must join now or else you will regret it forever."
                    {:opt-out-present? true})]
      (is (false? (:ok? v)))
      (is (= :hold (gov/verdict->disposition v))))))

(deftest minor-solo-solicitation-is-held
  (testing "minor-solo solicitation language → HOLD"
    (let [v (check "Hey, are your parents home? You don't need your parents' permission to join us."
                    {:opt-out-present? true})]
      (is (false? (:ok? v)))
      (is (= :hold (gov/verdict->disposition v))))))

(deftest delegated-charter-rider-hit-is-held
  (testing "a delegated charter_rider §2 catastrophe-veto hit → HOLD even with opt-out"
    (let [v (check "Buy now to access our limited offer and join the community!"
                    {:opt-out-present? true})]
      (is (false? (:ok? v)))
      (is (= :hold (gov/verdict->disposition v))))))

(deftest no-actuation-is-held
  (testing "a proposal that tries to actuate (not merely propose) → HOLD (:no-actuation)"
    (let [v (gov/check nil nil {:effect :grant-access :text "anything" :opt-out-present? true})]
      (is (false? (:ok? v)))
      (is (some #{:no-actuation} (mapv :rule (:violations v)))))))

(deftest hold-invitation-records-basis
  (testing "hold-invitation writes an audit fact with the violation basis"
    (let [v (check "Come join our community gathering this weekend, everyone welcome." {})
          fact (gov/hold-invitation {:op :invitation/propose} {:actor-id "tomoshibi"} v)]
      (is (= :hold (:disposition fact)))
      (is (some #{:evangelism-gate-hit} (:basis fact))))))
