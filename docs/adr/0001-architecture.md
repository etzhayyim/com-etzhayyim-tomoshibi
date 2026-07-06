# ADR-0001: tomoshibi (灯) — architecture (child-repo design 正本)

**Status**: accepted (R0 scaffold landed 2026-07-06)
**Parent**: `etzhayyim/root` ADR-2607061700 (Mission Charter §1.16, Active
Evangelism Doctrine), Open Question 4.

## Context

ADR-2607061700 §1.16 carves a narrow exception into ADR-2606281500's
("種をまく" / seed-and-grow) rule 4 — "no person-targeting / no
manipulation" — for actor-authored invitational content. It shipped the
carve-out's judgment logic (`etzhayyim_organism.sensors.evangelism-gate`,
Open Question 2) and the activity ledger schema
(`evangelismActivityAttestation`, Open Question 3), but left Open Question
4 — "which actor wires this gate into a real decision path" — open.

A domain survey of existing digital-publication actors found none fit:
kouhou curates external public-sector info (wrong domain), kataribe is
structurally cross-doctrinal (G6 `doctrinalMonopolyAttested const false`,
in tension with promoting etzhayyim's own doctrine), tashikame/yomi are
fact-check/news actors whose neutrality a recruitment pitch would
undermine, and `com-etzhayyim-recruit` is an unrelated secular job-board
aggregator. Per this corpus's own one-actor-one-role convention (stated in
the kouhou ADR), a new, narrowly-scoped actor is the correct call.

## Decision

New Tier-B actor **tomoshibi (灯)** — invitational-content publication,
digital half of §1.16 evangelism only (interpersonal evangelism remains a
human Adherent's own practice, never an actor's).

### R0 scope: governor first, orchestration later

Unlike kouhou/tashikame/yomi (which pair an organizer/advisor LLM node
with a governor inside a full `langgraph.graph` StateGraph), tomoshibi R0
ships **only** `src/tomoshibi/governor.cljc` — a pure function, no
StateGraph, no LLM node, no publisher, no deploy. This mirrors an existing
pattern in this corpus (e.g. kataribe's R0: cells path-reserved but not
runtime-wired) rather than inventing a new one.

The governor is the load-bearing contract:

```
(gov/check request context {:effect :assessment
                             :text "..."
                             :opt-out-present? bool})
  → {:ok? bool :violations [...] :gate-result {...}}
```

It calls `etzhayyim-organism.sensors.evangelism-gate/gate` directly — a
**genuine wiring**, not an R0-illustrative marker placeholder like
`tashikame.governor`'s `<DOXING>` denylist (whose docstring explicitly
defers real wiring to "production"). tomoshibi's `bb.edn` adds
`../root/20-actors/etzhayyim-organism/src` as an extra classpath entry,
reusing that sensor's real regex-based §1.16(a)-(d) rules plus its
delegation to `charter-rider/scan` for §2 catastrophe-veto categories —
zero duplication of judgment logic.

### Deferred to R1+ (see MATURITY.md for the full list)

- LangGraph StateGraph orchestration (organizer/advisor node, phase, store)
- A publisher writing to app-aozora (a distinct lexicon from the ledger)
- Self-sovereign `did:key` identity + revocable CACAO leash
- `evangelismActivityAttestation` ledger writes (governor holds are
  currently in-memory maps, not yet persisted)
- RAD identity minting (separate repo/commit, per the kouhou precedent)
- Live `did:web:etzhayyim.com:actor:tomoshibi` hosting

## Consequences

- (+) ADR-2607061700 Open Question 4 is closed at the governor layer with
  a real, tested wiring (9 tests / 19 assertions green) rather than a
  domain-mismatched bolt-on or another "future ADR" deferral.
- (+) The one-actor-one-role boundary is preserved — kouhou/kataribe/
  tashikame/yomi are untouched.
- (−) tomoshibi cannot yet publish anything; it can only judge a proposal
  that some other process (human-drafted, for now) hands it. This is an
  honest, explicit limitation, not a hidden gap.
- (−) `bb.edn`'s relative-path dependency on the sibling `etzhayyim/root`
  checkout means tomoshibi is not a standalone-clonable library; it
  assumes the west-managed monorepo layout (same assumption kouhou makes
  of `langgraph-clj`).

## References

- `../root/90-docs/adr/2607061700-etzhayyim-active-evangelism-doctrine.md`
- `../root/90-docs/adr/2606281500-actor-autonomous-publication-seed-and-grow-doctrine.md`
- `../root/20-actors/etzhayyim-organism/src/etzhayyim_organism/sensors/evangelism_gate.cljc`
- `../root/00-contracts/lexicons/com/etzhayyim/apps/etzhayyim/evangelismActivityAttestation.json`
- `com-etzhayyim-kouhou` — the one-actor-one-role convention source; the governor/publisher split precedent
- `com-etzhayyim-tashikame/src/tashikame/governor.cljc` — the `person-targeting?` R0-illustrative-marker precedent this ADR deliberately diverges from (real wiring instead)
