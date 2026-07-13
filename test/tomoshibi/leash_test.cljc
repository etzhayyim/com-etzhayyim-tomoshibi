(ns tomoshibi.leash-test
  (:require [clojure.test :refer [deftest is testing]]
            [tomoshibi.leash :as leash]))

(deftest leash-is-fail-closed
  (testing "missing file → NOT active"
    (is (not (leash/active? (constantly nil) "leash.edn"))))
  (testing "malformed file → NOT active"
    (is (not (leash/active? (constantly "{{{") "leash.edn"))))
  (testing "revoked → NOT active"
    (is (not (leash/active? (constantly "{:status \"revoked\"}") "leash.edn"))))
  (testing "reader that throws → NOT active"
    (is (not (leash/active? (fn [_] (throw (ex-info "io" {}))) "leash.edn"))))
  (testing "active leash-line round-trips"
    (is (leash/active? (constantly (leash/leash-line "owner" "2026-07-12T00:00:00Z"))
                       "leash.edn"))))

(deftest v1-status-classification
  (let [now "2026-07-13T02:00:00Z"
        base {:v 1 :aud "did:web:etzhayyim.com:actor:tomoshibi" :cap "evangelism-mail"
              :issued-at "2026-07-13T00:00:00Z" :expiry "2026-08-12T00:00:00Z"
              :by "did:key:z6MkMember" :sig "00ff"}
        opts {:aud "did:web:etzhayyim.com:actor:tomoshibi"
              :issuer "did:key:z6MkMember" :now now}
        st (fn [m o] (tomoshibi.leash/status (pr-str m) o))]
    (testing "well-formed v1 → :signed (crypto handled by the cached verifier)"
      (is (= :signed (:mode (st base opts)))))
    (testing "fail-closed classifications"
      (is (= :missing-or-unreadable (:reason (tomoshibi.leash/status nil opts))))
      (is (= :aud-mismatch (:reason (st (assoc base :aud "did:web:other") opts))))
      (is (= :issuer-mismatch (:reason (st base (assoc opts :issuer "did:key:z6MkEvil")))))
      (is (= :issuer-mismatch (:reason (st base (assoc opts :issuer nil))))
          "no pinned issuer configured → NOT ok (never trust the file alone)")
      (is (= :expired (:reason (st (assoc base :expiry "2026-07-12T00:00:00Z") opts))))
      (is (= :missing-signature (:reason (st (dissoc base :sig) opts))))
      (is (= :missing-signature (:reason (st (assoc base :sig "NOT-HEX") opts))))
      (is (= :unknown-version (:reason (st (assoc base :v 2) opts)))))
    (testing "legacy v0 detected for the migration window"
      (is (= :legacy (:mode (tomoshibi.leash/status
                             (tomoshibi.leash/leash-line "owner" now) opts)))))
    (testing "canonical message is deterministic and field-derived"
      (is (= (tomoshibi.leash/canonical-message base)
             (tomoshibi.leash/canonical-message (assoc base :sig "ff00")))
          "sig is not part of the signed message")
      (is (not= (tomoshibi.leash/canonical-message base)
                (tomoshibi.leash/canonical-message (assoc base :cap "other")))))))
