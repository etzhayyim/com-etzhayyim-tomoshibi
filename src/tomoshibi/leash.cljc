(ns tomoshibi.leash
  "The off-switch (ADR-2606111400's revocable leash, first REAL wiring in
  this actor family — kouhou/tashikame document the leash in prose but never
  check one; see kouhou.aozora's unwired :leash opt).

  R1 shape: a node-local leash file the resident agent checks at the top of
  EVERY tick, fail-closed — file missing, unreadable, or anything but
  {:status \"active\"} means the actor is leashed OFF: no fetch, no draft,
  no send. Revocation is therefore one command on the node (or deleting the
  file), plus the two outer kill layers documented in the child ADR
  (disable the Email Routing rule; launchctl bootout).

  This is honestly an APPROXIMATION of the member-CACAO leash (a signed,
  delegation-chained revocation is R2+; see MATURITY.md) — but unlike the
  prose-only precedents it actually gates execution."
  (:require [clojure.edn :as edn]))

(defn active?
  "True iff the leash file exists, parses, and says {:status \"active\"}.
  `read-file` is injected ((fn [path] string-or-nil))."
  [read-file path]
  (let [content (try (read-file path) (catch #?(:clj Exception :cljs :default) _ nil))]
    (boolean
     (when content
       (try (= "active" (:status (edn/read-string content)))
            (catch #?(:clj Exception :cljs :default) _ false))))))

(defn leash-line
  "Canonical content for an ACTIVE leash file, with provenance."
  [granted-by now]
  (pr-str {:status "active" :granted-by granted-by :at now
           :revoke "set :status to \"revoked\" (or delete this file) — the agent checks every tick, fail-closed"}))
