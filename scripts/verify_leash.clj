(ns verify-leash
  "Verify a v1 leash file's Ed25519 signature against the member did:key in
  its :by field. JVM-only (same constraint as tomoshibi.cacao) — invoked by
  the bb daemon ONLY when the leash file's content changes (result cached),
  so per-tick cost stays pure-bb.

    clojure -M -m verify-leash <leash-path> <expected-issuer-did> <expected-aud>

  Prints {:valid bool :reason kw}. Exit 0 on :valid true, 1 otherwise.
  did:key decoding: multibase base58btc (z…) → varint multicodec 0xED 0x01 +
  32 raw Ed25519 bytes → X.509 wrap (RFC 8410 prefix) → JDK KeyFactory."
  (:require [clojure.edn :as edn]
            [tomoshibi.cacao :as cacao]
            [tomoshibi.leash :as leash])
  (:import (java.security KeyFactory)
           (java.security.spec X509EncodedKeySpec)))

(def ^:private b58-alphabet "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

(defn- b58-decode ^bytes [^String s]
  (let [idx (zipmap b58-alphabet (range))
        n (reduce (fn [^java.math.BigInteger acc c]
                    (let [d (idx c)]
                      (when-not d (throw (ex-info "bad base58 char" {:c c})))
                      (.add (.multiply acc (biginteger 58)) (biginteger d))))
                  java.math.BigInteger/ZERO s)
        raw (.toByteArray ^java.math.BigInteger n)
        ;; strip BigInteger sign byte, re-add leading zeros ('1' chars)
        raw (if (and (> (count raw) 1) (zero? (aget raw 0))) (byte-array (rest raw)) raw)
        zeros (count (take-while #(= \1 %) s))]
    (byte-array (concat (repeat zeros (byte 0)) raw))))

(def ^:private x509-ed25519-prefix
  ;; RFC 8410 SubjectPublicKeyInfo header for a raw 32-byte Ed25519 key
  (byte-array (map unchecked-byte [0x30 0x2a 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x03 0x21 0x00])))

(defn did-key->public-key
  "did:key:z6Mk… → java.security.PublicKey (Ed25519)."
  [did]
  (let [z (subs did (count "did:key:"))
        _ (when-not (.startsWith ^String z "z") (throw (ex-info "not base58btc multibase" {:did did})))
        bs (b58-decode (subs z 1))
        _ (when-not (and (= (unchecked-byte 0xed) (aget bs 0)) (= (byte 1) (aget bs 1)) (= 34 (count bs)))
            (throw (ex-info "not an ed25519 multicodec key" {:did did :len (count bs)})))
        raw (byte-array (drop 2 bs))
        spki (byte-array (concat x509-ed25519-prefix raw))]
    (.generatePublic (KeyFactory/getInstance "Ed25519") (X509EncodedKeySpec. spki))))

(defn- unhex ^bytes [^String s]
  (byte-array (map #(unchecked-byte (Integer/parseInt (subs s % (+ % 2)) 16))
                   (range 0 (count s) 2))))

(defn -main [& [path issuer aud]]
  (let [out (fn [valid? reason] (prn {:valid valid? :reason reason})
              (System/exit (if valid? 0 1)))]
    (try
      (let [{:keys [mode leash reason]}
            (leash/status (slurp path) {:aud aud :issuer issuer
                                        :now (str (java.time.Instant/now))})]
        (cond
          (= :legacy mode) (out false :legacy-unsigned)
          (not= :signed mode) (out false reason)
          :else
          (let [pub (did-key->public-key (:by leash))
                msg (.getBytes ^String (leash/canonical-message leash) "UTF-8")]
            (out (cacao/verify? pub msg (unhex (:sig leash))) :signature))))
      (catch Exception e (out false (keyword (or (ex-message e) "error")))))))
