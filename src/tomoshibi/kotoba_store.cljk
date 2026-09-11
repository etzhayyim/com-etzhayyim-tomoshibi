(ns tomoshibi.kotoba-store
  "The kotoba-Datom-log-backed Store — the R0 'swap, not rewrite' promise
  completed (store.cljc → MemStore, journal.cljc → FileStore, here → the
  canonical substrate per the repo-wide rule 'state = kotoba Datom log',
  ADR-2605312345 lineage; engine = etzhayyim.kotoba.engine, the same bb
  engine the organism heartbeat persists through).

  One attestation = one entity whose id is content-addressed
  (\"attestation:\" + sha256 of the canonical pr-str line — the SAME head the
  sigref journal signs, so a ledger row, its Datom entity, and its Ed25519
  sigref all cross-link by one hash). Attributes are the lexicon field names
  under the :evangelism.attestation/* namespace. Content-addressed ids make
  re-recording the same attestation naturally idempotent at read time.

  Requires the sibling etzhayyim/root checkout's 70-tools/src on the
  classpath (bb.edn). tomoshibi.daemon loads this namespace via
  requiring-resolve and falls back to journal/FileStore when the engine is
  not on the classpath — a storage upgrade must never take the actor down."
  (:require [etzhayyim.kotoba.engine :as kt]
            [tomoshibi.attest-sign :as attest]
            [tomoshibi.store :as store]))

(def attr-ns "evangelism.attestation")

(defn- ->entity [attestation]
  (into {:db/id (str "attestation:" (attest/attestation-head attestation))}
        (map (fn [[k v]] [(keyword attr-ns (name k)) v]))
        attestation))

(defn- entity-> [m]
  (into {}
        (keep (fn [[k v]]
                (when (= attr-ns (namespace k))
                  [(keyword (name k)) v])))
        (dissoc m :db/id)))

(defrecord KotobaStore [conn]
  store/Store
  (all-attestations [_]
    (->> (kt/q conn '{:find [?e]
                      :where [[?e :evangelism.attestation/mode ?mode]]})
         (map first)
         distinct
         (map #(entity-> (kt/entity conn %)))
         (sort-by :createdAt)
         vec))
  (record-attestation! [s attestation]
    (kt/transact conn [(->entity attestation)])
    s))

(defn open
  "Connect a KotobaStore over an EDN-lines Datom journal at `journal-path`.
  `seed` (optional, seq of attestations — e.g. journal/FileStore's existing
  rows) is transacted once when the Datom log holds no attestations yet, so
  switching backends never orphans the ledger history. Returns
  {:store KotobaStore :migrated <n>}."
  [journal-path & [seed]]
  (let [conn (kt/connect {:journal journal-path})
        s (->KotobaStore conn)
        existing (store/all-attestations s)
        migrate (when (and (empty? existing) (seq seed)) (vec seed))]
    (doseq [att migrate] (store/record-attestation! s att))
    {:store s :migrated (count migrate)}))
