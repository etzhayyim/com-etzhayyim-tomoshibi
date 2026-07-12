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
