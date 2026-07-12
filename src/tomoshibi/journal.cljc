(ns tomoshibi.journal
  "Durable, file-backed operational state for the resident agent — append-only
  EDN-lines journals in the corpus's journal ethos. Three concerns, three
  files (all under the agent's state dir, e.g. ~/.etzhayyim/tomoshibi/):

    attestations.journal.edn — FileStore: the evangelismActivityAttestation
        ledger behind the SAME tomoshibi.store/Store protocol as MemStore
        (the R0 'swap, not rewrite' promise made in store.cljc, kept here).
        NOTE the ledger stays recipient-free (lexicon rule: never a
        target-list) — operational addressing lives in processed.edn only.

    processed.journal.edn — per-inbound-message lifecycle (dedup + bounded
        retries + the 1-reply-per-inbound invariant). Replayed into a map on
        load; last entry per id wins.

    budget.journal.edn — one line per committed send; the daily send budget
        is COUNTED from the journal (crash-safe: a restart cannot reset the
        day's count).

  All I/O is via injected read-file/append-line fns so every fold here is
  pure/testable; tomoshibi.agent binds them to real files."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [tomoshibi.store :as store]))

(defn- edn-lines [content]
  (keep (fn [line]
          (when-not (str/blank? line)
            (try (edn/read-string line)
                 (catch #?(:clj Exception :cljs :default) _ nil))))
        (str/split-lines (or content ""))))

;; ---------------------------------------------------------------------------
;; FileStore — tomoshibi.store/Store over an append-only EDN-lines file
;; ---------------------------------------------------------------------------

(defrecord FileStore [read-file append-line path]
  store/Store
  (all-attestations [_] (vec (edn-lines (read-file path))))
  (record-attestation! [s attestation]
    (append-line path (pr-str attestation))
    s))

(defn file-store [read-file append-line path]
  (->FileStore read-file append-line path))

;; ---------------------------------------------------------------------------
;; processed: inbound message lifecycle
;; ---------------------------------------------------------------------------

(defn load-processed
  "Fold processed.journal.edn → {message-id {:status … :attempts … :at …}}.
  Later lines win (append-only update semantics)."
  [read-file path]
  (reduce (fn [m {:keys [id] :as entry}]
            (if id (assoc m id (dissoc entry :id)) m))
          {}
          (edn-lines (read-file path))))

(defn processed-entry [id status attempts now]
  (pr-str {:id id :status status :attempts attempts :at now}))

(defn attempts [processed id]
  (get-in processed [id :attempts] 0))

(defn done?
  "Terminal statuses: this inbound will never be drafted again. This is the
  one-reply-per-inbound invariant (no follow-up without new inbound)."
  [processed id]
  (contains? #{:replied :held :suppressed :skipped :gave-up}
             (get-in processed [id :status])))

;; ---------------------------------------------------------------------------
;; budget: committed sends per UTC day, counted from the journal
;; ---------------------------------------------------------------------------

(defn sends-today
  "Count committed sends whose :at starts with `today` (ISO date string)."
  [read-file path today]
  (count (filter #(str/starts-with? (str (:at %)) today)
                 (edn-lines (read-file path)))))

(defn send-entry [inbound-id resend-id now]
  (pr-str {:inbound-id inbound-id :resend-id resend-id :at now}))

(defn send-allowed?
  [read-file path today max-per-day]
  (< (sends-today read-file path today) max-per-day))
