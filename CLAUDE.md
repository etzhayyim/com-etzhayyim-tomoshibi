# com-etzhayyim-tomoshibi

tomoshibi (灯) — invitational evangelism digital-publication actor. See
`README.md` for the core contract and `../root/CLAUDE.md` "Actors" section
for the pattern this follows (containment + independent governor +
append-only ledger). Parent decision record:
`../root/90-docs/adr/2607061700-etzhayyim-active-evangelism-doctrine.md`
(Open Question 4). Design 正本: `docs/adr/0001-architecture.md`.

## Invariant

tomoshibi NEVER publishes invitational content the EvangelismGovernor
rejects. Every governed proposal is scanned by
`etzhayyim-organism.sensors.evangelism-gate/gate` (which itself composes
with `charter-rider/scan`). No-opt-out / individual-vulnerability-targeting
/ coercion / minor-solo-solicitation / catastrophe-veto proposals are
HELD — recorded as a hold, never published. Publication (once the
publisher layer exists, R1+) is AUTONOMOUS by default (ADR-2606281500,
種をまく) — no per-post operator/Council prior restraint; the off-switch
will be the revocable member CACAO leash. Aggregate-first: never a
target-list (ADR-2606281500 rule 4).

## Conventions

- `.cljc` for anything portable — `governor.cljc` (the HARD gate),
  `store.cljc` (append-only `Store` protocol + `MemStore`),
  `operation.cljc` (`propose!` — governor → commit-or-hold, no langgraph).
  `.clj` only for JVM-only I/O — `cacao.clj` (self-sovereign Ed25519
  identity; faithful port of `kouhou.cacao`, `KeyPairGenerator "Ed25519"`
  needs a real JDK, confirmed not resolvable the same way from bb's SCI).
- `bb.edn` adds `../root/20-actors/etzhayyim-organism/src` as an extra
  classpath entry — this actor depends on that sibling checkout being
  present at the expected relative path (west-managed monorepo layout,
  same pattern as kouhou's `:local/root "../../com-junkawasaki/langgraph-clj"`).
- The actor's own Ed25519 identity lives in `.tomoshibi/identity.edn`
  (gitignored) — NEVER commit a private key. Generated on first
  `tomoshibi.cacao/load-or-create-identity!` call.
- `bb run_tests.clj` for the bb-based suite (18 tests / 48 assertions —
  governor + store/operation, `.cljc` only). `clojure -M -m cacao-smoke`
  for the JVM-only cacao smoke script (no automated `clojure.test` for
  cacao, matching kouhou/tashikame — see MATURITY.md). No clj-kondo lint
  config yet beyond the placeholder `:lint` alias.
- Test files (`test/tomoshibi/*_test.cljc`) do NOT call `-main`/`System/exit`
  themselves — only `run_tests.clj` does. A per-file eager `(-main)` would
  `System/exit` before the next namespace in a shared runner ever loads
  (hit this bug once already; kept as a note so it isn't reintroduced).
