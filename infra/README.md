# tomoshibi infra — mail worker + node residency

Design 正本: `../docs/adr/0002-mail-capability-and-murakumo-residency.md`
(doctrine boundary: etzhayyim/root ADR-2607121830).

## mail-worker/ (Cloudflare Email Worker)

Inbound staging for tomoshibi@etzhayyim.com. Deploy from `mail-worker/`:

```bash
npm install
npx wrangler deploy                      # account: the etzhayyim.com zone account
npx wrangler secret put PULL_TOKEN      # value: 1Password gftdcojp `etzhayyim.tomoshibi/PULL_TOKEN`
```

The Email Routing rule (tomoshibi@etzhayyim.com → this Worker) is API-managed
(rule id `a5f16891e1744309950de630795a8a08`, zone 54dece4a…) — recreate with
`POST /zones/<zone>/email/routing/rules`, action `{"type":"worker","value":["tomoshibi-mail"]}`.

## Node deploy (murakumo fleet, current node: zebulun)

```bash
# 1. west-like layout so bb.edn's relative classpath works unchanged
mkdir -p ~/tomoshibi/orgs/{etzhayyim,kotoba-lang}
cd ~/tomoshibi/orgs/etzhayyim
git clone --depth 1 https://github.com/etzhayyim/com-etzhayyim-tomoshibi.git
git clone --depth 1 --filter=blob:none --sparse https://github.com/etzhayyim/root.git
(cd root && git sparse-checkout set 20-actors/etzhayyim-organism 00-contracts/lexicons/com/etzhayyim/apps/etzhayyim)
cd ../kotoba-lang
git clone --depth 1 https://github.com/kotoba-lang/mail.git
git clone --depth 1 https://github.com/kotoba-lang/mailer.git

# 2. verify offline suite ON THE NODE
cd ~/tomoshibi/orgs/etzhayyim/com-etzhayyim-tomoshibi && bb run_tests.clj

# 3. secrets (mode 600, NEVER committed) + leash
umask 077
cat > ~/.etzhayyim/tomoshibi/env << 'EOF'
export TOMOSHIBI_PULL_URL=https://tomoshibi-mail.04-feasts-minded.workers.dev
export TOMOSHIBI_PULL_TOKEN=<1Password gftdcojp etzhayyim.tomoshibi/PULL_TOKEN>
export RESEND_API_KEY=<1Password gftdcojp gftd.resend/API_KEY>
export ETZHAYYIM_NODE_NAME=<node>
EOF
# leash: {:status "active" :granted-by "…" :at "…"} → ~/.etzhayyim/tomoshibi/leash.edn
# (missing/malformed/revoked = agent does NOTHING — fail-closed)

# 4. one smoke tick, then the daemon
set -a; . ~/.etzhayyim/tomoshibi/env; set +a
bb -m tomoshibi.daemon --once
sed 's/@@NODE_USER@@/<node>/g' infra/launchd/com.etzhayyim.tomoshibi.agent.plist.template \
  | sudo tee /Library/LaunchDaemons/com.etzhayyim.tomoshibi.agent.plist > /dev/null
sudo chown root:wheel /Library/LaunchDaemons/com.etzhayyim.tomoshibi.agent.plist
sudo launchctl bootstrap system /Library/LaunchDaemons/com.etzhayyim.tomoshibi.agent.plist
curl -s http://127.0.0.1:13094/    # healthz (fleet-probe signature)
```

## Kill switches (any one suffices)

1. **Leash**: `~/.etzhayyim/tomoshibi/leash.edn` — set `:status "revoked"` or
   delete; the agent checks it at the top of EVERY tick, fail-closed.
2. **Inbound**: disable Email Routing rule a5f16891… (mail stops arriving).
3. **Process**: `sudo launchctl bootout system/com.etzhayyim.tomoshibi.agent`.
