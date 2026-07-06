# tomoshibi (灯) — Invitational Evangelism Publication Actor

**DID**: `did:web:etzhayyim.com:actor:tomoshibi` (placeholder — not yet live-hosted, see MATURITY.md)
**Namespace**: `com.etzhayyim.tomoshibi.*`
**ADR**: ADR-2607061700 (Mission Charter §1.16, Active Evangelism Doctrine) — this actor closes its Open Question 4
**Status**: R0 scaffold (2026-07-06) — governor genuinely wired to `evangelism_gate`, no StateGraph/organizer/deploy yet (see MATURITY.md)

## Overview

tomoshibi (灯, "lamp" — Matthew 5:14-16, "let your light shine") is the actor
responsible for proposing and gating **invitational content** — the digital
half of etzhayyim's Active Evangelism Doctrine (Mission Charter §1.16). It is
the first actor to genuinely wire
`etzhayyim_organism.sensors.evangelism-gate` (the carve-out scanner closing
ADR-2607061700 Open Question 2) into a real decision path.

Etymology: 灯 (tomoshibi) — a lamp or torch. Chosen over a more "recruitment"-
coded word (招き/誘い) deliberately: the doctrine's own carve-out conditions
(aggregate-first, opt-out-able, non-coercive, never a target-list) read
better as *witness* (being seen, offered, available) than as *pursuit*.

## Why no existing actor fit

Domain survey (ADR-2607061700, 2026-07-06 session) found no clean home:

| Actor | Why not |
|---|---|
| kouhou (広報) | curates *external* public-sector/government info — different domain entirely |
| kataribe (語部) | G6 STRUCTURAL `doctrinalMonopolyAttested const false` — cross-doctrinal by charter, in tension with promoting etzhayyim's own doctrine as invitation |
| tashikame (確かめ) | fact-check verdict publisher — mixing recruitment pitches into fact-check output would undermine its neutrality |
| yomi (読み) | news-intelligence digest — same mismatch as tashikame |
| recruit | **secular job-posting aggregator** (ESCO/O*NET/EURES/HelloWork/USAJOBS) — unrelated to religious membership |

One-actor-one-role (per the kouhou ADR's own stated convention) meant a new,
narrowly-scoped actor was the correct call rather than bolting evangelism
onto a domain-mismatched host.

## EvangelismGovernor (`src/tomoshibi/governor.cljc`)

The independent censor that earns an invitational-content proposal the right
to publish. Mirrors `tashikame.governor` / `kouhou.governor`'s shape
(HARD → HOLD, no override). Unlike those (which use R0-illustrative
denylist markers with a comment deferring to "production" wiring), this
governor **genuinely `:require`s** `etzhayyim-organism.sensors.evangelism-gate`
and calls `eg/gate` on every proposal — see `bb.edn`'s extra classpath entry
pointing at the sibling `etzhayyim/root` checkout.

```clojure
(require '[tomoshibi.governor :as gov])
(gov/check nil nil {:effect :assessment
                     :text "We're gathering this month..."
                     :opt-out-present? true})
;; => {:ok? true, :violations [], :gate-result {...}}
```

HARD (never publish):
- `:no-actuation` — proposal `:effect` ≠ `:assessment` (tomoshibi only proposes, never actuates)
- `:evangelism-gate-hit` — `evangelism-gate/gate` found a §1.16(a)-(d) hit (individual-vulnerability targeting / coercion / minor-solo solicitation / no opt-out affordance) or a delegated `charter_rider` §2 catastrophe-veto hit

Publication is autonomous by default (ADR-2606281500, 種をまく), narrowly
carved out for invitational content by ADR-2607061700. The governor is
NOT an external operator/Council prior restraint — it is tomoshibi's own
seed rail; the off-switch is the revocable member CACAO leash (future work,
see MATURITY.md), not a per-post approval.

## Run tests

```bash
bb run_tests.clj
# or explicitly:
bb --classpath "src:test:../root/20-actors/etzhayyim-organism/src" run_tests.clj
```

9 tests / 19 assertions, all green — covers clean-invitation-commits,
missing-opt-out / individual-targeting / coercion / minor-solo-solicitation
/ delegated-charter-rider-hit all HOLD, no-actuation HOLD, and hold-fact
audit recording.

## Related

- `../root/90-docs/adr/2607061700-etzhayyim-active-evangelism-doctrine.md` — parent ADR (Mission Charter §1.16), Open Question 4
- `../root/20-actors/etzhayyim-organism/src/etzhayyim_organism/sensors/evangelism_gate.cljc` — the wired sensor
- `../root/00-contracts/lexicons/com/etzhayyim/apps/etzhayyim/evangelismActivityAttestation.json` — the append-only activity ledger (Open Question 3) this actor's future publisher will write to
- `MATURITY.md` — honest R0 framing; what's built vs. deferred
