(ns tomoshibi.kotoba-store-test
  "KotobaStore ≡ MemStore contract (the corpus's swap-not-rewrite guarantee),
  over a real temp Datom journal via etzhayyim.kotoba.engine."
  (:require [clojure.test :refer [deftest is testing]]
            [tomoshibi.kotoba-store :as ks]
            [tomoshibi.store :as store]))

(defn- temp-journal []
  #?(:clj (str (java.io.File/createTempFile "tomoshibi-kotoba-test" ".journal.edn"))))

(def att1 {:createdAt "2026-07-13T01:00:00Z" :mode "digital"
           :optOutAffordancePresent true :coercionAttested false
           :minorSoloSolicitationAttested false :voluntaryAttested true
           :attestingCellDid "did:web:etzhayyim.com:actor:tomoshibi"
           :actorDid "did:web:etzhayyim.com:actor:tomoshibi"})
(def att2 (assoc att1 :createdAt "2026-07-13T02:00:00Z"))

(deftest kotoba-store-honors-store-contract
  (let [{:keys [store migrated]} (ks/open (temp-journal))]
    (is (zero? migrated))
    (is (= [] (store/all-attestations store)))
    (store/record-attestation! store att2)
    (store/record-attestation! store att1)
    (testing "round-trips exactly, oldest first (createdAt order, not insert order)"
      (is (= [att1 att2] (store/all-attestations store))))
    (testing "content-addressed entities → re-recording is read-idempotent"
      (store/record-attestation! store att1)
      (is (= [att1 att2] (store/all-attestations store))))))

(deftest kotoba-store-survives-reconnect
  (let [path (temp-journal)]
    (store/record-attestation! (:store (ks/open path)) att1)
    (testing "a NEW connection over the same journal sees the datoms (durable)"
      (is (= [att1] (store/all-attestations (:store (ks/open path))))))))

(deftest kotoba-store-migrates-file-ledger-once
  (let [path (temp-journal)
        first-open (ks/open path [att1 att2])]
    (is (= 2 (:migrated first-open)))
    (is (= [att1 att2] (store/all-attestations (:store first-open))))
    (testing "second open with the same seed migrates nothing (no duplication)"
      (let [again (ks/open path [att1 att2])]
        (is (zero? (:migrated again)))
        (is (= [att1 att2] (store/all-attestations (:store again))))))))
