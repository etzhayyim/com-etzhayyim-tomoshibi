# ADR 0002: Email send/receive capability + autonomous murakumo residency (R1)

- Status: accepted (landed 2026-07-12)
- Deciders: Jun Kawasaki (founder directive, session 2026-07-12: 「メールアドレスの
  送受信の capability を持つように, murakumo で自律的永続的に ai agent が動くようにして」)
- Parent doctrine: etzhayyim/root ADR-2607061700 (§1.16 Active Evangelism) +
  root ADR-2607121830 (email invitational-reply channel boundaries — the
  doctrinal companion to this architecture ADR)
- Superproject registration: com-junkawasaki ADR-2607121900

## Context

R0 (ADR 0001) shipped the EvangelismGovernor genuinely wired to
`etzhayyim-organism.sensors.evangelism-gate`, an attestation writer, and a
self-sovereign Ed25519 identity — but no publication channel, no organizer,
and no residency. The founder directed R1 to be: a real mailbox
(`tomoshibi@etzhayyim.com`, send AND receive) and a persistent autonomous
agent on the Murakumo fleet.

Doctrinal note (full reasoning in root ADR-2607121830): the §1.16 actor
carve-out authorizes *collective, public* invitational content, NOT
actor-initiated 1:1 messaging. Email is therefore admitted **reply-only**:
the actor answers people who wrote to it first (invited speech — the human
self-selected), and cold outreach is left *unrepresentable in the API*, not
merely prohibited by policy.

## Decision

### Topology

```
sender ── SMTP ──▶ Cloudflare Email Routing (MX, live since 2026-05-19)
                       │ rule: tomoshibi@etzhayyim.com → worker  (rule id a5f16891…)
                       ▼
              tomoshibi-mail Worker (infra/mail-worker/)
                 email(): postal-mime parse → KV TOMOSHIBI_INBOX
                          (id 98a1f3a8…, key "inbox:<ts>-…", TTL 60d)
                 fetch(): Bearer-authed pull API (GET /inbox, POST /ack)
                       ▲
                       │ HTTPS pull (TOMOSHIBI_PULL_TOKEN)
              resident agent on murakumo node zebulun
                 launchd LaunchDaemon com.etzhayyim.tomoshibi.agent
                 bb -m tomoshibi.daemon  (tick every 5 min, healthz :13094)
                 draft: node-local Ollama gemma4:12b-it-qat (Murakumo-only
                        allowlist, kouhou precedent)
                 gate:  tomoshibi.governor → evangelism-gate (+ charter-rider)
                       │
                       ▼ committed replies only
              Resend API (etzhayyim.com verified, region ap-northeast-1,
                          domain id 4f4d2bc2…) ── SMTP ──▶ sender
```

### The pipeline can only make the actor QUIETER

Per inbound message (src/tomoshibi/agent.cljc): leash → dedup →
auto-generated? (RFC 3834 loop guard) → stop-request? (suppress forever) →
suppressed? → bounded retries → Murakumo draft (fail-closed, no template
fallback) → daily send budget (default 20) → **governor on the full outgoing
text incl. footer** → Resend send → **attest only after a confirmed send**
(a ledger row must never claim an activity a failed send didn't perform;
propose!'s re-derived verdict is deterministic so it cannot disagree with
the pre-send check).

### Structural guarantees (not policy — shape)

- **Reply-only**: `mail/reply-message` is the sole outbound constructor and
  requires an inbound record; recipient ≡ inbound sender. No API exists to
  address anyone else.
- **One reply per inbound, no follow-up**: processed.journal.edn terminal
  states are permanent; nothing initiates.
- **Opt-out is real**: every reply body carries the bilingual opt-out footer
  (mail/opt-out-footer) + List-Unsubscribe header; `:opt-out-present? true`
  is only ever asserted because the footer is unconditionally appended. Any
  inbound reading as a stop request (JA/EN patterns) suppresses the sender
  forever, checked before drafting and at send.
- **Leash actually gates** (first real wiring in this actor family —
  kouhou/tashikame document a leash in prose only): fail-closed leash file
  checked every tick; plus two outer kill layers (disable routing rule
  a5f16891…; `sudo launchctl bootout system/com.etzhayyim.tomoshibi.agent`).
- **Ledger is never a target-list**: evangelismActivityAttestation rows stay
  recipient-free; operational addressing lives only in the node-local
  processed/budget journals.

### Autonomy basis

Autonomous send (no per-reply human approval) rests on: ADR-2606281500
(種をまく, autonomous publication default) as narrowed by ADR-2607061700, plus
the owner's standing directive of 2026-07-10 (outbound communications after
propose→govern may execute on agent judgment) and the founder session
directive of 2026-07-12 (this feature). Publication ≠ actuation gates are
unaffected — sending an invitation email moves no funds, grants no access.

### Residency & ops

- Dedicated LaunchDaemon (organism-heartbeat shape: `KeepAlive` +
  `RunAtLoad`, absolute bb path, secrets via plist EnvironmentVariables on
  the node only — fleet.edn `:operator_token_source` convention; file mode
  600 root:wheel). NOT the lite_runner cron path: the agent is a resident
  loop with its own healthz, like the organism, and deliberately does
  external I/O, which the lite_runner `fire` contract forbids.
- Registered in root fleet.edn (zebulun `:cells` + catalog) and cells.edn
  (`lan-api` kind, healthz 13094) so `bb fleet:probe` verifies residency.
- Node layout mirrors the west superproject
  (`~/tomoshibi/orgs/{etzhayyim/{root,com-etzhayyim-tomoshibi},kotoba-lang/{mail,mailer}}`)
  so bb.edn's relative classpath works unchanged.
- State: `~/.etzhayyim/tomoshibi/*.journal.edn` (append-only EDN lines) +
  `leash.edn`. Crash-safe: budget/dedup are counted from journals, so a
  restart cannot re-send or double-count.

### Reuse (nothing reinvented)

kotoba-lang `mail` (message/inbound model) + `mailer` (:resend wire) —
`mail/send-request` re-attaches the threading headers mailer.core drops;
cloud-itonami's mail-inbound Worker pattern (postal-mime → KV staging,
staging-only so a bad parse can never corrupt ledgers) extended with the
authed pull API so the node needs no wrangler credentials; kouhou's
Murakumo host allowlist; organism's crash-isolated tick.

### Role boundary vs kotoba-lang/tayori (one-actor-one-role)

tayori (ADR-2607061500) is general correspondence *drafting* across
email/Slack/WhatsApp with human-approved sends, org kotoba-lang. tomoshibi
is evangelism-domain-only, replies only on its own mailbox, autonomous under
the evangelism gate, org etzhayyim. No overlap in mailbox, org, governor, or
speech-act.

## Consequences

- 43 tests / 164 assertions green (`bb run_tests.clj`), including: HELD
  drafts are never sent and never attested; stop requests suppress forever;
  auto-generated mail is never answered; budget halts the tick; send failure
  leaves no attestation; leash revocation stops everything.
- Known limits (honest): minors cannot be age-verified over email — mitigated
  by prompt + gate content rules, documented in root ADR-2607121830; the
  leash is a file, not yet a member-CACAO delegation (R2+); attestations are
  unsigned (cacao.clj still unwired — JVM-only, node runs bb); DatomicStore
  swap remains future work (FileStore implements the same Store protocol).
- New external dependencies: Cloudflare KV/Worker (staging only, 60-day TTL)
  and Resend (send only) — both objective-function-scored engineering
  choices (実装 layer), swappable behind tomoshibi.mail's pure builders.

## Alternatives considered

- **openmail-smtp-gateway (self-hosted SMTP)**: tested cores but no live
  daemon, no deliverability story from residential fleet IPs. Rejected for
  R1; remains the sovereign end-state candidate.
- **lite_runner cron cell**: violates the fire contract (no external I/O);
  a 1-minute cron granularity also fits a mailbox poorly. Dedicated daemon
  chosen (organism precedent).
- **M365 Graph sendMail**: gftd policy deprecates it in favor of Resend
  rails; and the actor's identity domain is etzhayyim.com.
- **Actor-initiated outreach (cold email)**: rejected as outside the §1.16
  carve-out (aggregate-first), and deliberately left unrepresentable.
