(ns tomoshibi.journal-test
  (:require [clojure.test :refer [deftest is testing]]
            [tomoshibi.journal :as journal]
            [tomoshibi.store :as store]))

(defn- mem-fs []
  (let [files (atom {})]
    {:read-file (fn [path] (get @files path))
     :append-line (fn [path line] (swap! files update path str line "\n"))}))

(deftest file-store-honors-store-contract
  (let [{:keys [read-file append-line]} (mem-fs)
        fs (journal/file-store read-file append-line "att.journal.edn")
        att {:createdAt "2026-07-12T00:00:00Z" :mode "digital"
             :optOutAffordancePresent true :coercionAttested false
             :minorSoloSolicitationAttested false :voluntaryAttested true
             :attestingCellDid "did:web:etzhayyim.com:actor:tomoshibi"}]
    (is (= [] (store/all-attestations fs)))
    (store/record-attestation! fs att)
    (store/record-attestation! fs (assoc att :createdAt "2026-07-12T00:00:01Z"))
    (testing "append-only, oldest first, round-trips through the file"
      (is (= 2 (count (store/all-attestations fs))))
      (is (= att (first (store/all-attestations fs)))))
    (testing "the ledger rows carry no recipient (never a target-list)"
      (is (not-any? #(or (contains? % :to) (contains? % :recipient) (contains? % :email))
                    (store/all-attestations fs))))))

(deftest processed-lifecycle
  (let [{:keys [read-file append-line]} (mem-fs)
        path "processed.journal.edn"]
    (is (= {} (journal/load-processed read-file path)))
    (append-line path (journal/processed-entry "<m1>" :pending 1 "t0"))
    (append-line path (journal/processed-entry "<m1>" :pending 2 "t1"))
    (append-line path (journal/processed-entry "<m2>" :replied 0 "t2"))
    (let [p (journal/load-processed read-file path)]
      (testing "last entry per id wins"
        (is (= 2 (journal/attempts p "<m1>")))
        (is (not (journal/done? p "<m1>"))))
      (testing "terminal statuses are done (one reply per inbound, forever)"
        (is (journal/done? p "<m2>"))
        (is (zero? (journal/attempts p "<m3>")))))))

(deftest budget-counted-from-journal
  (let [{:keys [read-file append-line]} (mem-fs)
        path "budget.journal.edn"]
    (is (journal/send-allowed? read-file path "2026-07-12" 2))
    (append-line path (journal/send-entry "<m1>" "re_1" "2026-07-12T01:00:00Z"))
    (append-line path (journal/send-entry "<m2>" "re_2" "2026-07-12T02:00:00Z"))
    (testing "cap reached for today"
      (is (not (journal/send-allowed? read-file path "2026-07-12" 2))))
    (testing "yesterday's sends don't count against today"
      (is (journal/send-allowed? read-file path "2026-07-13" 2)))
    (is (= 2 (journal/sends-today read-file path "2026-07-12")))))
