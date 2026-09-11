(ns tomoshibi.graph
  "OperationActor as a langgraph-clj StateGraph — the orchestration layer the
  R0 scaffold explicitly deferred ('No LangGraph StateGraph orchestration').
  Mirrors kouhou.operation's containment topology (intake → govern → decide →
  commit | hold) but for tomoshibi's evangelism-mail domain: the contained
  intelligence (the Murakumo organizer) has ALREADY produced the draft off-graph
  (the draft is inbound-reply text, and sending is a durable-outer-loop effect,
  not a graph node — cloud-itonami's 'bounded StateGraph run inside a durable
  outer loop' shape), so the graph's job is the GOVERNED DECISION + commit of
  one proposal, checkpointed and auditable.

  The graph is decision-equivalent to tomoshibi.operation/propose! BY
  CONSTRUCTION — both :govern and :decide call the SAME governor/check and
  governor/verdict->disposition, and :commit calls the SAME store/->attestation
  + record-attestation!. The graph adds: a checkpointer (per-superstep audit),
  an :audit channel (the hold-fact / commit-fact trace), and a single seam an
  outer loop can invoke per approved message. No interrupt-before — publication
  is autonomous by default (ADR-2606281500), narrowed for invitational content
  by ADR-2607061700; the EvangelismGovernor's HARD holds are the only withhold.

  Requires langgraph + langchain on the classpath (bb.edn) — the daemon keeps
  the plain-pipeline path (tomoshibi.agent) as the proven default and can inject
  a graph-backed decide via :decide-fn; this module is the orchestration
  option, not a forced replacement (swap, not rewrite)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [tomoshibi.governor :as governor]
            [tomoshibi.store :as store]))

(defn build
  "Compile the tomoshibi OperationActor graph bound to `store`. opts:
    :checkpointer — langgraph checkpointer (default in-mem).
  Run input: {:request … :context … :proposal … :now … :attesting-cell-did …}.
  Final state carries :disposition (:commit|:hold), :attestation (commit only),
  :hold-fact (hold only), and :audit."
  [store & [{:keys [checkpointer] :or {checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request            {:default nil}
         :context            {:default nil}
         :proposal           {:default nil}
         :now                {:default nil}
         :attesting-cell-did {:default nil}
         :verdict            {:default nil}
         :disposition        {:default nil}
         :attestation        {:default nil}
         :hold-fact          {:default nil}
         :audit              {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; EvangelismGovernor — the independent censor (evangelism-gate +
      ;; charter-rider), identical call to the plain pipeline.
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal)}))

      ;; Decide — HARD hit → :hold (records the basis); else :commit.
      (g/add-node :decide
        (fn [{:keys [request context verdict]}]
          (if (:ok? verdict)
            {:disposition :commit}
            {:disposition :hold
             :hold-fact (governor/hold-invitation request context verdict)
             :audit [(governor/hold-invitation request context verdict)]})))

      ;; Commit — the ONLY node that writes the attestation ledger. Identical
      ;; to operation/propose!'s commit arm (a committed proposal, and ONLY a
      ;; committed one, produces an attestation).
      (g/add-node :commit
        (fn [{:keys [proposal now attesting-cell-did]}]
          (let [att (store/->attestation proposal now attesting-cell-did)]
            (store/record-attestation! store att)
            {:attestation att
             :audit [{:t :committed :disposition :commit}]})))

      ;; Hold — no ledger write (a held proposal must never get an attestation).
      (g/add-node :hold (fn [_] {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}] disposition)
        {:commit :commit :hold :hold})
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph {:checkpointer checkpointer})))

(defn run
  "Invoke the graph for one proposal; returns the same map shape as
  tomoshibi.operation/propose! ({:disposition … :verdict … :attestation|
  :hold-fact}) so it is a drop-in orchestration swap."
  [compiled request context proposal now attesting-cell-did]
  (let [s (g/invoke compiled
                    {:request request :context context :proposal proposal
                     :now now :attesting-cell-did attesting-cell-did}
                    {:thread-id (str "tomoshibi:" (hash [request proposal now]))})]
    (cond-> {:disposition (:disposition s) :verdict (:verdict s)}
      (= :commit (:disposition s)) (assoc :attestation (:attestation s))
      (= :hold (:disposition s))   (assoc :hold-fact (:hold-fact s)))))
