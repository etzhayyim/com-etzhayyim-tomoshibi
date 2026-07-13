# tomoshibi (灯) — Maturity Ledger

honest framing: できていないことは「未」と明記する。

- Actor: `did:web:etzhayyim.com:actor:tomoshibi` (placeholder, not live-hosted) · ADR-2607061700 (parent) · **R1: email channel + murakumo residency**
- 不変条件(全イテレーション厳守): 対人ではなく digital 招待発信のみを扱う(対人伝道は信者個人の実践であり本 actor の対象外) · governor 拒否のコンテンツは決して publish しない · aggregate-first — actor 起点の発信は集合的発信のみ。**email は reply-only の narrow 追加**(2026-07-12 founder 指示 + root ADR-2607121830): 受信起点の応答だけを許し、actor 起点の cold outreach は API 上表現不能に保つ · opt-out 常時提供(email では全返信に footer + List-Unsubscribe + suppression 永続) · coercion / 未成年単独勧誘は構造的に不可能 · コミットはユーザー明示時のみ

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

## 2026-07-06 — self-sovereign Ed25519 identity (`tomoshibi.cacao`)

Faithful port of `kouhou.cacao` (itself ported from `tsumugu.cacao` /
`tashikame.cacao` — the "keep in sync" family; NOT reimplemented from
scratch, to avoid introducing a subtle crypto bug in a proven, byte-exact
CBOR/SIWE/did:key implementation). `.clj` (not `.cljc`) — JVM-only, matching
kouhou's own convention: `KeyPairGenerator/getInstance "Ed25519"` needs a
JDK 15+ provider not resolvable from bb's SCI environment for the full
Signature/KeyFactory round-trip (confirmed by testing, not assumed).

`load-or-create-identity!` generates + persists a fresh Ed25519 key to
`.tomoshibi/identity.edn` (gitignored) on first run, or reloads it
byte-identically thereafter. `mint` produces a depth-1 self-minted CACAO
(SIWE/EIP-4361 message, Ed25519-signed, minimal-CBOR-wire-encoded,
base64). No automated `clojure.test` suite for this module — matching the
kouhou/tashikame precedent (neither ships a `cacao_test.clj` either);
verified instead by `scripts/cacao_smoke.clj` (`clojure -M -m cacao-smoke`),
which checks: a well-formed `did:key:z...`, byte-identical reload, a
non-empty minted CACAO, `verify?` accepting the actor's own signature, and
— the negative case — `verify?` rejecting that same signature under a
**different** actor's public key. All 5 checks pass.

Not yet used by anything — `operation/propose!` does not mint a CACAO for
committed proposals yet (that's the aozora publisher's job, still absent;
see below).

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
  not the *public post* record). `tomoshibi.cacao/mint` exists but nothing
  calls it yet — the publisher is what would.
- **No revocable CACAO leash wiring.** The identity/mint primitives exist;
  the *leash* (a member-held revocation reference carried on each publish
  call, per ADR-2606111400) does not — there is no publish call yet for it
  to be carried on.
- **`did:web:etzhayyim.com:actor:tomoshibi` is still not live.** `did.json`
  in `.well-known/` remains a static placeholder; the `did:key` this actor
  now genuinely generates is a *different* DID method from the `did:web`
  the manifest advertises (this mirrors kouhou/tashikame's own two-DID
  shape — did:key = actor's own signing identity; did:web = the
  human-facing discovery address once hosted).
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

## 2026-07-12 — R1: email send/receive + autonomous murakumo residency

Founder session directive (2026-07-12): 「メールアドレスの送受信の capability を
持つように, murakumo で自律的永続的に ai agent が動くようにして」。設計正本:
`docs/adr/0002-mail-capability-and-murakumo-residency.md` + root
ADR-2607121830(doctrine 境界)。

New: `mail.cljc`(pure builders — worker pull / reply-only construction /
Resend wire + threading headers)· `suppress.cljc`(JA/EN stop-request 検出 +
append-only suppression journal)· `organizer.cljc`(node-local Ollama
gemma4:12b-it-qat、Murakumo allowlist、fail-closed)· `leash.cljc`(fail-closed
leash file — この actor family で初の「実際に実行を止める」leash 配線)·
`journal.cljc`(FileStore = Store protocol の durable 実装 + processed/budget
journals)· `agent.cljc`(1 bounded tick、全 effect 注入)· `daemon.clj`(bb
常駐 entrypoint、healthz 127.0.0.1:13094)· `infra/mail-worker/`(CF Email
Worker: postal-mime → KV staging + Bearer pull API)。

Infra (live): Email Routing rule `tomoshibi@etzhayyim.com → tomoshibi-mail`
(rule a5f16891…)· KV `TOMOSHIBI_INBOX`(98a1f3a8…)· Resend domain
etzhayyim.com(4f4d2bc2…、ap-northeast-1)· DNS 追加4件(resend._domainkey
TXT / send MX+TXT / _dmarc TXT)。residency: zebulun 上の LaunchDaemon
`com.etzhayyim.tomoshibi.agent`(root fleet.edn / cells.edn 登録、
`bb fleet:probe` で検証可能)。

Tests: 43 tests / 164 assertions green(R0 の 18/48 から拡張)— HELD は送信も
attest もされない / stop-request は永続 suppression / auto-generated mail
(bounce/autoresponder)には決して返信しない / budget 超過で tick 停止 / 送信
失敗時は attest しない(台帳は「実際に行われた活動」だけを主張する)/ leash
revoked で全停止、を含む。

R0 の「Explicitly NOT done」の現状(item ごと):

- ~~No app-aozora publication wiring~~ → **publication channel は email に
  なった**(aozora publisher は依然未実装のまま — email が R1 の channel)。
- ~~No organizer/advisor LLM~~ → **organizer.cljc**(Ollama、fail-closed)。
  langchain.model 経由ではなく直接 /api/chat(bb runtime、依存ゼロ)。
- ~~store は MemStore のみ~~ → **FileStore**(同一 Store protocol、
  append-only EDN lines)。kotoba Datom log backend は依然 R2+。
- ~~No revocable CACAO leash wiring~~ → **file-based leash が実際に gate する**
  (fail-closed、毎 tick)。ただし member-CACAO 署名つき delegation では
  まだない(R2+)— 「approximation だが実効」と明記。
- No LangGraph StateGraph orchestration — **依然 未**(plain pipeline のまま。
  kouhou 型 StateGraph 化は R2+)。
- `did:web:…:actor:tomoshibi` live 化 — **依然 未**。
- No RAD identity minting — **依然 未**。
- cacao.clj の attestation 署名への wiring — **依然 未**(JVM-only モジュール、
  node runtime は bb のため R1 では見送り。attestation は unsigned)。

## 2026-07-12 — R2 (identity wave): 実鍵 + did:web live + RAD identity

R2 loop iteration 1-3(founder「next, setup loop」指示)。R1 の「依然 未」の
うち3項目がここで閉じた:

- **actor 実鍵**: zebulun 上で `tomoshibi.cacao/load-or-create-identity!` により
  永続 Ed25519 identity を生成 — `did:key:z6MkvqXdDba3CZ96nRzYBYiDrnoHP8DtCSgW7duzwFPGnf9Z`
  (秘密鍵は node の `.tomoshibi/identity.edn`、mode 600、gitignored。
  reload byte-identical 検証済み。前提として zebulun に openjdk 26 + Clojure CLI
  を導入 — 将来の attestation 署名も node 上で可能に)。
- **did:web live**: `did:web:etzhayyim.com:actor:tomoshibi` が
  https://etzhayyim.com/actor/tomoshibi/did.json で解決(515 actor roster で初の
  実 verificationMethod 入り。alsoKnownAs に did:key と mailto)。
- **RAD identity**(ADR-2606231200): `etzhayyim/root` の
  `80-data/kotoba-rad/tomoshibi.identity.journal.edn` —
  RID `bafkreice23tmjwuymn4ronuilzrdedupdf2qlcek2xk37t2myd2krvuasi`
  (`rad:bafkreice23tmj…`)、tx2 で node-held did:key を cross-link。
  unsigned pilot posture(既存 325 journal と同じ)。root PR #3028。

依然 未(R2 続き): attestation への did:key 署名(iteration 4 予定)/
member-CACAO leash 本実装 / kotoba Datom log store / StateGraph 化。

## 2026-07-13 — R2 iteration 4: signed attestations(sigref journal)

attestation 台帳の row は lexicon 形のまま一切触れず、**並行 append-only journal
`attestations.sigrefs.journal.edn`** に RAD sigref パターン
(etzhayyim.kotoba-rad/sigref-datom 前例)で署名を積む: 1 committed attestation
= 1 sigref、`:head` = attestation 行(pr-str)の sha256、`:sig` = node 保持
did:key(z6MkvqXd…)の Ed25519 署名 hex。署名は JVM helper
`scripts/sign_head.clj`(clojure -M -m sign-head <head>)を send 成功時のみ
subprocess 起動(日次 budget ≤20 なので起動コストは無視可)。**fail-open は
署名のみ** — helper 不調時は `:sig nil` の unsigned sigref + `:sign-failed`
ops 行を明示的に残し、返信と attestation 本体は決してブロックしない
(kotoba-rad unsigned pilot と同じ honest posture)。

`tomoshibi.attest-sign`(pure builders + signer shape 検証)/ agent.cljc は
注入 `:attest-sign!`(optional)を **propose! 成功後にのみ** 呼ぶ。
49 tests / 190 assertions green — sigref は :replied でのみ発火し、HELD /
send-failure では決して発火しないことを含む。

## 2026-07-13 — R2 iteration 5: kotoba Datom log store(canonical substrate)

`tomoshibi.kotoba-store` — `etzhayyim.kotoba.engine`(organism が使う同じ bb
engine、repo-wide「state = kotoba Datom log」規則の canonical 経路)上の
`Store` protocol 実装。1 attestation = 1 entity、entity id は
`attestation:<sha256-head>`(sigref journal が署名するのと**同一 hash** —
台帳 row・Datom entity・Ed25519 sigref が1つの content hash で相互リンク)。
attrs は `:evangelism.attestation/*`。content-addressed id により再記録は
read-idempotent。daemon は `TOMOSHIBI_STORE=kotoba`(既定)で opening 時に
旧 FileStore rows を**一回だけ** seed migration(orphan なし)、engine が
classpath に無い場合は FileStore に fail-open fallback(`:store-opened`
ops 行で backend/migrated/fallback を明示)。node 側は root sparse checkout
に 70-tools/src を追加して engine を供給。52 tests / 199 assertions green
(temp Datom journal での durable reconnect / migration 一回性を含む)。

これで R0 から持ち越しの store 課題は closed。残: member-CACAO leash 本実装 /
StateGraph 化(kouhou 型 orchestration)。

## 2026-07-13 — R2 iteration 6: member-CACAO leash 本実装(ADR-2606111400)

leash が file-flag approximation から **member 署名付き delegation** に昇格。
`leash.edn` v1 = `{:v 1 :aud <actor did:web> :cap "evangelism-mail"
:issued-at :expiry :by <member did:key> :sig <Ed25519 hex>}`。署名対象は
`leash/canonical-message`(フィールドから決定論的に再構築 — 埋め込み文言は
信用しない)。検査は二層: 毎 tick の純検査(bb — aud/issuer-pin/expiry/形)+
内容変更時のみ `scripts/verify_leash.clj`(JVM、did:key multibase→raw→X.509
復元で Ed25519 検証)を実行し content-hash でキャッシュ。**issuer は file
でなく設定に pin**(`TOMOSHIBI_LEASH_ISSUER`)— 鍵ごと差し替えた偽 leash は
issuer-mismatch で死ぬ。**leash は fail-closed**(helper 不調 = NOT ok —
sigref の fail-open と対称)。revocation: file 削除(即時)/上書き/expiry
放置(dead-man: 30日で自然失効、`scripts/leash_mint.clj` で member が更新)。
member 鍵は owner 機の `~/.etzhayyim/member/member.identity.edn`(600)のみ —
node にも repo にも置かない。legacy v0 は migration 窓の間 active 扱い
(boot 時 `:leash-legacy` 警告)。実測: valid → true / sig 改竄 → 拒否 /
issuer 不一致 → 拒否。54 tests / 215 assertions green。

**これで R2 backlog は StateGraph 化を残すのみ。**

## 2026-07-13 — R2 iteration 7: StateGraph orchestration(R2 backlog 完了)

`tomoshibi.graph` — langgraph-clj StateGraph で kouhou.operation 同型トポロジ
(intake → govern → decide → commit | hold)を評価。tomoshibi の domain では
起草(Murakumo organizer)と送信は durable outer loop 側の effect なので
(cloud-itonami「durable outer loop 内の有界 StateGraph run」型)、graph の職務は
**1 proposal の governed decision + attestation commit**(checkpointer で
superstep 監査、`:audit` channel に hold-fact/commit-fact)。interrupt-before
なし(招待発信は autonomous default、ADR-2606281500 → 2607061700)。

**decision-equivalent by construction**: :govern/:decide は plain pipeline と
同一の `governor/check` + `verdict->disposition`、:commit は同一の
`store/->attestation` + `record-attestation!`。graph_test が同一 proposal で
propose! と graph/run の disposition・attestation・hold-fact・台帳状態の一致を
実証(clean→commit / coercion→hold / opt-out 欠如→hold)。langgraph+langchain は
bb 上で compile-graph + mem-checkpointer + conditional-edges + invoke 全て動作
確認済み(no-`-clj` naming は既存 repo の redirect のため classpath は
`com-junkawasaki/langgraph-clj` 慣例のまま)。57 tests / 225 assertions green。

daemon は proven な plain-pipeline path(tomoshibi.agent)を既定に保ち、graph は
「swap, not rewrite」の orchestration option として提供(強制置換しない)。

**R2 backlog は全項目 landed**: 実鍵 / did:web live / RAD identity /
signed attestations / kotoba Datom store / member-CACAO leash / StateGraph。

## 2026-07-13 — R3 iteration 8-A: StateGraph を daemon の既定 decision path に

`open-propose!`(daemon)— `TOMOSHIBI_ORCHESTRATION=stategraph`(既定)で
`tomoshibi.graph` の langgraph StateGraph を一度 compile して閉じ込め、
propose! と同一シグネチャの `:propose-fn` として agent tick に注入。=plain、
または langgraph が classpath に無い場合は plain `operation/propose!` に
**fail-open fallback**(`:orchestration-fallback` ops 行 — orchestration upgrade
は actor を落とさない、open-store! と同姿勢)。`:store-opened` ops 行に
`:orchestration` を併記。agent.cljc は `:propose-fn`(既定 operation/propose!)
を destructure。58 tests / 228 assertions green。live 決定経路が checkpointed
StateGraph を通るようになった(graph_test で propose! と decision-equivalent を
実証済みなので挙動不変)。
