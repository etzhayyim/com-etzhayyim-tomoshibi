(ns leash-mint
  "Mint a member-signed v1 leash for the tomoshibi actor (the real revocable
  leash, ADR-2606111400 lineage). RUN ON THE MEMBER'S OWN MACHINE — the member
  Ed25519 key lives at ~/.etzhayyim/member/member.identity.edn (created on
  first run, chmod it 600 and mirror to 1Password), NEVER on the fleet node
  and NEVER in a repo.

    clojure -M -m leash-mint [days] [aud] [member-identity-path]

  Prints the leash.edn content to stdout — install it at the node's
  ~/.etzhayyim/tomoshibi/leash.edn. Renewal = run again (dead-man: an
  unrenewed leash expires on its own)."
  (:require [tomoshibi.cacao :as cacao]
            [tomoshibi.leash :as leash])
  (:import (java.time Instant Duration)))

(defn- hex [^bytes bs] (apply str (map #(format "%02x" %) bs)))

(defn -main [& [days aud path]]
  (let [days (or (some-> days parse-long) 30)
        aud (or aud "did:web:etzhayyim.com:actor:tomoshibi")
        path (or path (str (System/getProperty "user.home")
                           "/.etzhayyim/member/member.identity.edn"))
        member (cacao/load-or-create-identity! path)
        now (Instant/now)
        m {:v 1 :aud aud :cap "evangelism-mail"
           :issued-at (str now)
           :expiry (str (.plus now (Duration/ofDays days)))
           :by (:did member)}
        sig (#'cacao/ed-sign (:private-key member)
             (.getBytes ^String (leash/canonical-message m) "UTF-8"))]
    (binding [*out* *err*]
      (println "member did:" (:did member) " key:" path)
      (println "leash valid" days "days — install stdout at ~/.etzhayyim/tomoshibi/leash.edn"))
    (prn (assoc m :sig (hex sig)))))
