# tomoshibi (灯) — Maturity Ledger

honest framing: できていないことは「未」と明記する。

- Actor: `did:web:etzhayyim.com:actor:tomoshibi` (placeholder, not live-hosted) · ADR-2607061700 (parent) · **R0 scaffold**
- 不変条件(全イテレーション厳守): 対人ではなく digital 招待発信のみを扱う(対人伝道は信者個人の実践であり本 actor の対象外) · governor 拒否のコンテンツは決して publish しない · aggregate-first(集合的発信のみ) · opt-out 常時提供 · coercion / 未成年単独勧誘は構造的に不可能 · コミットはユーザー明示時のみ

## 2026-07-06 — R0: EvangelismGovernor genuinely wired

`src/tomoshibi/governor.cljc` — HARD gate calling
`etzhayyim-organism.sensors.evangelism-gate/gate` directly (not an
R0-illustrative marker placeholder like `tashikame.governor`/`kouhou.governor`'s
denylists — this is the real sensor, imported via `bb.edn`'s extra classpath
entry `../root/20-actors/etzhayyim-organism/src`). 9 tests / 19 assertions
green (`bb run_tests.clj`): clean-invitation-with-opt-out-flag commits,
clean-invitation-with-textual-opt-out commits, missing-opt-out held,
individual-vulnerability-targeting held, coercion held,
minor-solo-solicitation held, delegated-charter_rider-hit held,
no-actuation held, hold-invitation records basis. Closes ADR-2607061700
Open Question 4 at the governor layer.

## 2026-07-06 — R0+R1-partial: evangelismActivityAttestation writer

`src/tomoshibi/store.cljc` (append-only `Store` protocol + `MemStore`) and
`src/tomoshibi/operation.cljc` (`propose!` — governor → commit-or-hold,
no langgraph) close the loop between the two pieces ADR-2607061700 already
shipped independently: the governor's decision and the
`evangelismActivityAttestation` lexicon. Only a COMMITTED proposal ever
produces an attestation — a HELD one never does (writing e.g.
`coercionAttested: false` for gate-flagged coercive content would be a
false attestation). 9 new tests / 29 new assertions (18 tests / 48
assertions total, `bb run_tests.clj`), including two that read the
**actual** lexicon JSON file (via `cheshire.core`, not a hand-copied list)
and assert `tomoshibi.store/lexicon-required-fields` and the four
STRUCTURAL const values match it exactly — schema drift between the
lexicon and this actor's writer would fail CI, not just go unnoticed.

This is deliberately a plain function pipeline, not a langgraph-clj
StateGraph (see below) — `propose!` has zero new external dependencies.

## Explicitly NOT done (R1+ future work)

- **No LangGraph StateGraph orchestration.** Unlike kouhou/tashikame (which
  wrap an `organizer`/`advisor` LLM node + governor + publisher in a full
  `langgraph.graph` StateGraph, per `../langgraph-clj`), tomoshibi ships
  only `governor.cljc` + `store.cljc` + `operation.cljc` as plain functions.
  There is no proposal-generation node, no `phase.cljc`, no `publisher.cljc`.
- **No organizer/advisor LLM.** Nothing drafts invitational text yet —
  `operation/propose!` takes `:text` as a plain argument. What drafts it (a
  human Adherent? a Murakumo-inference node?) is undecided.
- **No app-aozora publication wiring.** No `com.atproto.repo.createRecord`
  call, no `aozora.clj`, no lexicon for the published record shape
  (distinct from `evangelismActivityAttestation`, which is the *ledger*,
  not the *public post* record).
- **No self-sovereign identity / CACAO leash.** No `.tomoshibi/identity.edn`,
  no `cacao.clj`. `did.json` in `.well-known/` is a static placeholder,
  not resolvable — `did:web:etzhayyim.com:actor:tomoshibi` is not live.
- **No RAD identity minting.** No `80-data/kotoba-rad/tomoshibi.identity.journal.edn`
  entry in etzhayyim/root — deferred, matching the kouhou precedent
  ("RAD identity は...別 repo・別 commit").
- **`store.cljc` is MemStore only — no kotoba Datom log backend yet.**
  Attestations live in an in-process atom; they do not survive a process
  restart. A `DatomicStore` (mirroring kouhou's `langchain.db`-backed
  record) is future work, same `Store` protocol, no rewrite.
- **No deploy / Murakumo inference wiring.**

The **decision to build a governor first, orchestration later** mirrors
this corpus's own convention (e.g. kataribe's R0: "cell 非実行, import時
RuntimeError" — many R0 scaffolds ship structural gates + tests before
runtime wiring). The governor is the load-bearing contract; everything
above it is replaceable plumbing.
