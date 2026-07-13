(ns tomoshibi.leash
  "The off-switch — now the REAL member-CACAO leash (ADR-2606111400 lineage;
  authoritative design ADR-2606101200 §委任): a MEMBER-signed, expiring
  delegation the actor re-checks every tick, fail-closed.

  leash.edn v1 (member-signed):
    {:v 1
     :aud       \"did:web:etzhayyim.com:actor:tomoshibi\"  ; who is leashed
     :cap       \"evangelism-mail\"                         ; what is delegated
     :issued-at \"2026-07-13T…Z\"
     :expiry    \"2026-08-12T…Z\"                           ; dead-man renewal
     :by        \"did:key:z6Mk…\"                           ; the MEMBER's key
     :sig       \"<hex Ed25519 over (canonical-message …) UTF-8>\"}

  Verification splits by cost:
    every tick (bb, pure — this ns): file exists/parses, :v 1, :aud matches
      the actor, :by matches the PINNED issuer (config, not the file), not
      expired, and :sig present.
    on content change (JVM helper scripts/verify_leash.clj, cached by the
      daemon): the Ed25519 signature actually verifies against the did:key in
      :by. bb cannot do Ed25519 (same constraint as cacao.clj) — the split
      keeps per-tick cost trivial while making a forged/tampered leash
      unable to survive one content-change check.

  Revocation, in order of immediacy: delete the file (fail-closed, next
  tick) · overwrite/expire it · let :expiry lapse (a leash the member does
  not renew dies on its own). Legacy v0 files ({:status \"active\"}) are
  still honored during migration but reported :legacy so the daemon can log
  the deprecation.

  Signing/minting lives OFF-node with the member: scripts/leash_mint.clj
  (owner machine, member key at ~/.etzhayyim/member/ — never on the fleet
  node, never in the repo)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn canonical-message
  "The exact string the member signs. Deterministic from the leash fields —
  a leash whose :message diverges from its fields is invalid by construction
  (we rebuild, never trust an embedded copy)."
  [{:keys [v aud cap issued-at expiry by]}]
  (str "etzhayyim leash v" v "\n"
       "aud: " aud "\n"
       "cap: " cap "\n"
       "issued-at: " issued-at "\n"
       "expiry: " expiry "\n"
       "by: " by))

(defn- parse [content]
  (try (let [m (edn/read-string content)] (when (map? m) m))
       (catch #?(:clj Exception :cljs :default) _ nil)))

(defn status
  "Classify the leash file content (string or nil) without touching crypto.
  opts {:aud <actor did:web> :issuer <pinned member did:key> :now <ISO str>}.
  → {:mode :signed  :leash m}        v1, all pure checks pass — the daemon
                                      must still hold a cached signature
                                      verification for this content
    {:mode :legacy}                   v0 {:status \"active\"} (deprecated)
    {:mode :revoked :reason …}        anything else — fail closed."
  [content {:keys [aud issuer now]}]
  (let [m (some-> content parse)]
    (cond
      (nil? m)
      {:mode :revoked :reason :missing-or-unreadable}

      (= "active" (:status m))
      {:mode :legacy}

      (not= 1 (:v m))
      {:mode :revoked :reason :unknown-version}

      (not= aud (:aud m))
      {:mode :revoked :reason :aud-mismatch}

      (or (str/blank? (str issuer)) (not= issuer (:by m)))
      {:mode :revoked :reason :issuer-mismatch}

      (not (and (string? (:sig m)) (re-matches #"[0-9a-f]+" (:sig m))))
      {:mode :revoked :reason :missing-signature}

      (not (and (string? (:expiry m)) (pos? (compare (:expiry m) now))))
      {:mode :revoked :reason :expired}

      :else
      {:mode :signed :leash m})))

(defn active?
  "Legacy v0 entry point (kept for the offline suite + migration): true iff
  the file parses to {:status \"active\"}. The daemon's real path is `status`
  + a cached signature verification — see tomoshibi.daemon/leash-ok-fn."
  [read-file path]
  (let [content (try (read-file path) (catch #?(:clj Exception :cljs :default) _ nil))]
    (= :legacy (:mode (status content {:aud nil :issuer nil :now ""})))))

(defn leash-line
  "Canonical content for a LEGACY ACTIVE leash file (v0, deprecated —
  bootstrap/testing only; mint a signed v1 with scripts/leash_mint.clj)."
  [granted-by now]
  (pr-str {:status "active" :granted-by granted-by :at now
           :revoke "set :status to \"revoked\" (or delete this file) — the agent checks every tick, fail-closed"}))
