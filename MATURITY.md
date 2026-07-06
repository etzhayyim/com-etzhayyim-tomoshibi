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

## Explicitly NOT done (R1+ future work)

- **No LangGraph StateGraph orchestration.** Unlike kouhou/tashikame (which
  wrap an `organizer`/`advisor` LLM node + governor + publisher in a full
  `langgraph.graph` StateGraph, per `../langgraph-clj`), tomoshibi ships
  only the governor as a pure function. There is no proposal-generation
  node, no `phase.cljc`, no `store.cljc`, no `publisher.cljc`.
- **No organizer/advisor LLM.** Nothing drafts invitational text yet —
  `governor/check` takes `:text` as a plain argument. What drafts it (a
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
- **No `evangelismActivityAttestation` writer.** The governor's `hold-invitation`
  fact is an in-memory map, not yet persisted to the append-only ledger
  (Open Question 3's lexicon) or the kotoba Datom log.
- **No deploy / Murakumo inference wiring.**

The **decision to build a governor first, orchestration later** mirrors
this corpus's own convention (e.g. kataribe's R0: "cell 非実行, import時
RuntimeError" — many R0 scaffolds ship structural gates + tests before
runtime wiring). The governor is the load-bearing contract; everything
above it is replaceable plumbing.
