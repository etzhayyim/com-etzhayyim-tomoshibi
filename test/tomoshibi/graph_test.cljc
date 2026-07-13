(ns tomoshibi.graph-test
  "The StateGraph is decision-equivalent to the plain propose! pipeline — the
  load-bearing property that makes it a safe orchestration swap."
  (:require [clojure.test :refer [deftest is testing]]
            [tomoshibi.graph :as graph]
            [tomoshibi.operation :as operation]
            [tomoshibi.store :as store]))

(def now "2026-07-13T03:00:00Z")
(def did "did:web:etzhayyim.com:actor:tomoshibi")
(def request {:op :mail-reply})
(def context {:actor-id did})

(defn- clean-proposal [text]
  {:effect :assessment :text text :opt-out-present? true :mode "digital" :actor-did did})

(deftest graph-commit-matches-pipeline
  (let [prop (clean-proposal "You are warmly invited — no pressure at all.")
        g-store (store/seed-db)
        p-store (store/seed-db)
        g-res (graph/run (graph/build g-store) request context prop now did)
        p-res (operation/propose! p-store request context prop now did)]
    (testing "same disposition + same attestation, both ledgers hold one row"
      (is (= :commit (:disposition g-res) (:disposition p-res)))
      (is (= (:attestation p-res) (:attestation g-res)))
      (is (= (store/all-attestations p-store) (store/all-attestations g-store)))
      (is (= 1 (count (store/all-attestations g-store)))))))

(deftest graph-hold-matches-pipeline
  (let [prop (clean-proposal "You must join now or else you will suffer.")
        g-store (store/seed-db)
        p-store (store/seed-db)
        g-res (graph/run (graph/build g-store) request context prop now did)
        p-res (operation/propose! p-store request context prop now did)]
    (testing "both HOLD, neither writes an attestation, hold-fact matches"
      (is (= :hold (:disposition g-res) (:disposition p-res)))
      (is (= (:hold-fact p-res) (:hold-fact g-res)))
      (is (empty? (store/all-attestations g-store)))
      (is (empty? (store/all-attestations p-store))))))

(deftest graph-holds-missing-opt-out
  (let [prop (assoc (clean-proposal "Come join us today.") :opt-out-present? false)
        g-store (store/seed-db)]
    (is (= :hold (:disposition (graph/run (graph/build g-store) request context prop now did))))
    (is (empty? (store/all-attestations g-store)))))
