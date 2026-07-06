(ns tomoshibi.operation
  "One invitational-content proposal = one supervised run, expressed as a
  plain function pipeline (NOT a langgraph-clj StateGraph — that
  orchestration layer, matching kouhou/tashikame's `organizer ⊣ governor`
  topology, is explicit R1+ future work; see MATURITY.md 'No LangGraph
  StateGraph orchestration'). This keeps R0 dependency-free (no
  `langgraph-clj` / `langchain-clj` checkout required) while still
  genuinely closing the loop between the EvangelismGovernor (decision) and
  the evangelismActivityAttestation store (record) — the two pieces
  ADR-2607061700 Open Questions 2/3/4 already shipped independently.

  propose! → governor/check → :commit  → store/->attestation + record!
                             → :hold    → governor/hold-invitation (NOT
                                          written to the attestation store —
                                          see tomoshibi.store's own docstring
                                          on why a held proposal never gets
                                          an attestation)."
  (:require [tomoshibi.governor :as governor]
            [tomoshibi.store :as store]))

(defn propose!
  "Runs one proposal through the EvangelismGovernor and, on :commit, persists
  an evangelismActivityAttestation to `store`. Returns
  {:disposition :commit|:hold :verdict verdict :attestation (:commit only)
   :hold-fact (:hold only)}.

  proposal: {:effect :assessment :text \"...\" :opt-out-present? bool
             :mode \"digital\"|\"interpersonal\" :actor-did ... :adherent-did ...
             :interpersonal-method ... :evangelism-gate-scan-cid ...}
  `now` (ISO-8601 datetime string) and `attesting-cell-did` are
  caller-supplied — see tomoshibi.store/->attestation."
  [store request context proposal now attesting-cell-did]
  (let [verdict (governor/check request context proposal)
        disposition (governor/verdict->disposition verdict)]
    (case disposition
      :commit
      (let [att (store/->attestation proposal now attesting-cell-did)]
        (store/record-attestation! store att)
        {:disposition :commit :verdict verdict :attestation att})
      :hold
      {:disposition :hold
       :verdict verdict
       :hold-fact (governor/hold-invitation request context verdict)})))
