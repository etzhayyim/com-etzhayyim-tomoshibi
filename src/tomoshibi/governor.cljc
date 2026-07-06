(ns tomoshibi.governor
  "EvangelismGovernor — the independent censor that earns a tomoshibi
  invitational-content proposal the right to publish. Mirrors
  tashikame.governor / kouhou.governor's shape (HARD → HOLD, no override).

  Doctrine: publication is AUTONOMOUS by default (ADR-2606281500, 種をまく),
  narrowly carved out for invitational content by ADR-2607061700 (Mission
  Charter §1.16, Active Evangelism Doctrine — 'the door was open but never
  invited anyone in'). The EvangelismGovernor is NOT an external
  operator/Council prior restraint — it is tomoshibi's OWN seed rail (the
  off-switch is the revocable member CACAO leash, not a per-post approval).

    HARD violations → HOLD (recorded as a hold, NEVER published)

  HARD (never publish):
    :no-actuation        proposal :effect ≠ :assessment (tomoshibi only
                          proposes invitational content, never actuates)
    :evangelism-gate-hit etzhayyim_organism.sensors.evangelism-gate/gate
                          found a §1.16(a)-(d) hit (individual-vulnerability
                          targeting / coercion / minor-solo solicitation / no
                          opt-out affordance) OR a delegated charter_rider
                          §2 catastrophe-veto hit

  This governor is a REAL wiring, not an R0 illustrative placeholder — it
  requires etzhayyim-organism.sensors.evangelism-gate directly (via the
  extra classpath entry in bb.edn/deps.edn pointing at the sibling
  etzhayyim/root checkout) and calls `eg/gate` on every proposed
  invitational text. This closes ADR-2607061700 Open Question 4."
  (:require [etzhayyim-organism.sensors.evangelism-gate :as eg]))

(defn check
  "Censors a tomoshibi invitational-content proposal. proposal is
  {:effect :assessment, :text \"...\", :opt-out-present? bool}. Returns
  {:ok? :violations [hard] :gate-result}. :ok? is true iff there are no
  HARD violations."
  [_request _context proposal]
  (let [effect      (:effect proposal)
        text        (:text proposal)
        opt-out?    (:opt-out-present? proposal false)
        gate-result (eg/gate text {:opt-out-present? opt-out?})
        hard (cond-> []
               (not= :assessment effect)
               (conj {:rule :no-actuation
                      :detail "tomoshibi only proposes invitational content; :effect must be :assessment"})
               (not (:ok gate-result))
               (conj {:rule :evangelism-gate-hit
                      :detail (eg/reason gate-result)}))]
    {:ok? (empty? hard) :violations hard :gate-result gate-result}))

(defn hold-invitation
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t :governor-hold :op (:op request)
   :actor (:actor-id context) :disposition :hold
   :basis (mapv :rule (:violations verdict)) :violations (:violations verdict)})

(defn verdict->disposition
  "Map an EvangelismGovernor verdict to a base disposition. HARD → :hold, else :commit."
  [verdict]
  (if (:ok? verdict) :commit :hold))
