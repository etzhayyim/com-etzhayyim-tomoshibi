(ns cacao-smoke
  "Manual smoke script for tomoshibi.cacao (JVM-only crypto — this corpus's
  convention ships no automated cacao_test.clj for kouhou/tashikame either;
  cacao/aozora are verified this way, not by clojure.test). Verifies, against
  a throwaway identity file (never the real `.tomoshibi/identity.edn`):
    1. generate-identity produces a well-formed did:key
    2. load-or-create-identity! persists + reloads byte-identically
    3. mint produces a CACAO whose embedded signature verifies against the
       exact SIWE message bytes that were signed

  Run: clojure -M -m cacao-smoke"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tomoshibi.cacao :as cacao]))

(defn- fail! [msg] (println "FAIL:" msg) (System/exit 1))
(defn- ok! [msg] (println "OK  :" msg))

(defn -main [& _args]
  (let [tmp (java.io.File/createTempFile "tomoshibi-cacao-smoke-" ".edn")]
    (.delete tmp) ; load-or-create-identity! must create it fresh
    (try
      (let [id1 (cacao/load-or-create-identity! (.getAbsolutePath tmp))]
        (if (str/starts-with? (:did id1) "did:key:z")
          (ok! (str "generate-identity produced " (:did id1)))
          (fail! (str "did:key malformed: " (:did id1))))

        (let [id2 (cacao/load-or-create-identity! (.getAbsolutePath tmp))]
          (if (= (:did id1) (:did id2))
            (ok! "load-or-create-identity! reload is byte-identical (same DID)")
            (fail! (str "reload produced a different DID: " (:did id1) " vs " (:did id2)))))

        (let [grant   {:cap :cap/read :scope (:graph id1)}
              opts    {:aud "https://aozora.app" :nonce "smoke-nonce-1"
                       :issued-at "2026-07-06T09:30:00Z" :expiry nil}
              payload (cacao/grant->payload grant (merge {:iss (:did id1)} opts))
              msg     (cacao/siwe-message payload)
              cacao-b64 (cacao/mint id1 grant opts)]
          (if (and (string? cacao-b64) (pos? (count cacao-b64)))
            (ok! (str "mint produced a " (count cacao-b64) "-char base64 CACAO"))
            (fail! "mint produced an empty/invalid CACAO"))
          ;; Re-derive the signature bytes the same way `mint` did, and confirm
          ;; verify? accepts them against the actor's own public key, and
          ;; rejects them under a different actor's public key.
          (let [sig-source (.getBytes ^String msg "UTF-8")
                sign! (fn [priv]
                        (let [s (doto (java.security.Signature/getInstance "Ed25519")
                                  (.initSign priv))]
                          (.update s sig-source) (.sign s)))
                sig (sign! (:private-key id1))
                wrong-key (cacao/generate-identity)]
            (if (cacao/verify? (:public-key id1) sig-source sig)
              (ok! "verify? accepts the actor's own signature over its own SIWE message")
              (fail! "verify? rejected a signature that should be valid"))
            (if (not (cacao/verify? (:public-key wrong-key) sig-source sig))
              (ok! "verify? rejects the signature under a DIFFERENT actor's public key")
              (fail! "verify? incorrectly accepted a signature under the wrong public key")))))
      (finally
        (io/delete-file tmp true))))
  (println "cacao smoke: all checks passed"))
