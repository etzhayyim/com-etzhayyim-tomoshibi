(ns sign-head
  "Print {:by <did:key> :sig <hex>} for a head string, signed with the actor's
  node-held Ed25519 identity (.tomoshibi/identity.edn — gitignored, mode 600).

  JVM-only (java.security Ed25519, same constraint as tomoshibi.cacao) —
  invoked by the bb daemon as a short-lived subprocess ONCE per committed
  attestation (bounded by the daily send budget, ≤20/day), so JVM startup
  cost is irrelevant. Usage:

    clojure -M -m sign-head <head-hex> [identity-path]

  Reuses tomoshibi.cacao's private ed-sign var rather than duplicating the
  Signature plumbing (cacao.clj is a keep-in-sync family port — we deliberately
  do not widen its public surface)."
  (:require [tomoshibi.cacao :as cacao]))

(defn- hex [^bytes bs] (apply str (map #(format "%02x" %) bs)))

(defn -main [& [head path]]
  (if (or (nil? head) (empty? head))
    (do (binding [*out* *err*]
          (println "usage: clojure -M -m sign-head <head> [identity-path]"))
        (System/exit 2))
    (let [id (cacao/load-or-create-identity! (or path ".tomoshibi/identity.edn"))
          sig (#'cacao/ed-sign (:private-key id) (.getBytes ^String head "UTF-8"))]
      (prn {:by (:did id) :sig (hex sig)}))))
