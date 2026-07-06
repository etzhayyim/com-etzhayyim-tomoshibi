(ns tomoshibi.store
  "The append-only evangelismActivityAttestation ledger behind a `Store`
  protocol (MemStore for R0 — a swap to a kotoba-Datom-log-backed store is
  R1+ future work, see MATURITY.md; the protocol boundary is what makes that
  a swap, not a rewrite, per the corpus's `:db-api` convention).

  Only a COMMITTED proposal (EvangelismGovernor `:ok? true`) ever produces an
  attestation. A HELD proposal never does — writing e.g.
  `coercionAttested: false` for content the gate flagged as coercive would be
  a false attestation, not merely an incomplete one. See `tomoshibi.operation`
  for the propose!/hold split.

  The record shape mirrors
  `00-contracts/lexicons/com/etzhayyim/apps/etzhayyim/evangelismActivityAttestation.json`
  in the sibling etzhayyim/root checkout exactly — every STRUCTURAL const
  field in the lexicon (optOutAffordancePresent/coercionAttested/
  minorSoloSolicitationAttested/voluntaryAttested) is pinned here too, not
  merely documented.")

(defprotocol Store
  (all-attestations [s] "every committed evangelismActivityAttestation, oldest first")
  (record-attestation! [s attestation] "append one immutable attestation"))

(defrecord MemStore [a]
  Store
  (all-attestations [_] (:attestations @a))
  (record-attestation! [s attestation] (swap! a update :attestations conj attestation) s))

(defn seed-db
  "An empty MemStore."
  []
  (->MemStore (atom {:attestations []})))

(defn ->attestation
  "Build an evangelismActivityAttestation-shaped record for a COMMITTED
  (governor `:ok? true`) proposal. `now` is caller-supplied (ISO-8601
  datetime string) to keep this function pure/testable — no
  `(java.time.Instant/now)` inside. `attesting-cell-did` is the actor's own
  DID (`did:web:etzhayyim.com:actor:tomoshibi` once live; a placeholder
  string is fine for R0, see MATURITY.md)."
  [proposal now attesting-cell-did]
  (let [mode (:mode proposal "digital")]
    (cond-> {:createdAt now
             :mode mode
             :optOutAffordancePresent true
             :coercionAttested false
             :minorSoloSolicitationAttested false
             :voluntaryAttested true
             :attestingCellDid attesting-cell-did}
      (= mode "digital")
      (assoc :actorDid (:actor-did proposal))
      (and (= mode "digital") (:evangelism-gate-scan-cid proposal))
      (assoc :evangelismGateScanCid (:evangelism-gate-scan-cid proposal))
      (= mode "interpersonal")
      (assoc :adherentDid (:adherent-did proposal))
      (and (= mode "interpersonal") (:interpersonal-method proposal))
      (assoc :interpersonalMethod (:interpersonal-method proposal)))))

;; The lexicon's REQUIRED fields (per evangelismActivityAttestation.json
;; `record.required`), mirrored here so tests can assert against a single
;; source rather than re-deriving the list ad hoc.
(def lexicon-required-fields
  #{:createdAt :mode :optOutAffordancePresent :coercionAttested
    :minorSoloSolicitationAttested :voluntaryAttested :attestingCellDid})
