(ns tomoshibi.attest-sign-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [tomoshibi.attest-sign :as attest]))

(deftest sha256-hex-known-vector
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (attest/sha256-hex "abc"))))

(deftest attestation-head-is-deterministic-over-pr-str
  (let [att {:createdAt "t" :mode "digital" :optOutAffordancePresent true}]
    (is (= (attest/attestation-head att) (attest/attestation-head att)))
    (is (= (attest/sha256-hex (pr-str att)) (attest/attestation-head att)))
    (testing "any field change moves the head"
      (is (not= (attest/attestation-head att)
                (attest/attestation-head (assoc att :mode "interpersonal")))))))

(deftest sigref-shapes
  (testing "signed"
    (let [s (attest/sigref "abc123" {:by "did:key:zX" :sig "00ff"} "did:web:fallback" "now")]
      (is (= {:t :sigref :of :attestation :alg "Ed25519"
              :head "abc123" :by "did:key:zX" :sig "00ff" :at "now"} s))
      (is (= s (edn/read-string (pr-str s))))))
  (testing "unsigned records :sig nil under the fallback did (honest journal)"
    (let [s (attest/sigref "abc123" nil "did:web:etzhayyim.com:actor:tomoshibi" "now")]
      (is (nil? (:sig s)))
      (is (= "did:web:etzhayyim.com:actor:tomoshibi" (:by s))))))

(deftest signer-shape-validation
  (is (attest/valid-signer? {:by "did:key:z6MkvqXd" :sig "0a1b2c"}))
  (is (not (attest/valid-signer? {:by "did:key:z6Mk" :sig "NOT-HEX"})))
  (is (not (attest/valid-signer? {:by "https://evil" :sig "0a1b"})))
  (is (not (attest/valid-signer? nil))))
