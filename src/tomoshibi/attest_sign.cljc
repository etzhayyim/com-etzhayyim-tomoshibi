(ns tomoshibi.attest-sign
  "Signed-head sigrefs over the attestation ledger — the RAD sigref pattern
  (etzhayyim.kotoba-rad/sigref-datom precedent) applied to this actor's own
  evangelismActivityAttestation journal.

  The ledger rows stay EXACTLY lexicon-shaped (tomoshibi.store's contract with
  evangelismActivityAttestation.json is untouched); signatures live in a
  PARALLEL append-only journal (attestations.sigrefs.journal.edn): one sigref
  per committed attestation, `:head` = sha256 of the attestation's canonical
  pr-str line, `:sig` = the actor's node-held Ed25519 (did:key z6MkvqXd…) over
  the head string's UTF-8 bytes — or nil when the signing helper is
  unavailable (unsigned + warned, the kotoba-rad pilot posture; fail-open on
  SIGNING only, never on sending/attesting).

  Pure builders here; the JVM subprocess boundary (scripts/sign_head.clj) and
  file I/O live in tomoshibi.daemon."
  (:require [clojure.string :as str]))

(defn sha256-hex
  "Lowercase hex sha256 of a UTF-8 string."
  [s]
  #?(:clj (let [md (java.security.MessageDigest/getInstance "SHA-256")]
            (->> (.digest md (.getBytes ^String s "UTF-8"))
                 (map #(format "%02x" %))
                 (apply str)))
     :cljs (throw (ex-info "sha256-hex: cljs impl not wired (bb/JVM only for now)" {}))))

(defn attestation-head
  "The signable head of one attestation record: sha256 over its canonical
  pr-str line (the exact bytes appended to attestations.journal.edn)."
  [attestation]
  (sha256-hex (pr-str attestation)))

(defn sigref
  "One sigref journal entry. `signer` is {:by did :sig hex} from the signing
  helper, or nil → an explicit unsigned entry (:sig nil) so the journal
  honestly records that the attestation was made while signing was down."
  [head signer fallback-by now]
  {:t :sigref
   :of :attestation
   :alg "Ed25519"
   :head head
   :by (or (:by signer) fallback-by)
   :sig (:sig signer)
   :at now})

(defn valid-signer?
  "Shape check on the signing helper's output before trusting it."
  [{:keys [by sig]}]
  (boolean (and (string? by) (str/starts-with? by "did:key:z")
                (string? sig) (re-matches #"[0-9a-f]+" sig))))
