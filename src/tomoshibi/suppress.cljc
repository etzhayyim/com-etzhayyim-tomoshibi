(ns tomoshibi.suppress
  "Suppression (opt-out) ledger — the structural half of §1.16(d).

  The evangelism gate checks that every reply CARRIES an opt-out affordance;
  this namespace makes the affordance REAL: any inbound that reads as a stop
  request adds its sender to an append-only suppression journal, and a
  suppressed sender is never drafted for, never replied to, ever again (the
  check runs before drafting AND immediately before send). 執拗な繰り返し勧誘
  (repeated unsolicited follow-up) is additionally impossible by construction —
  replies are keyed 1:1 to inbound message-ids (tomoshibi.journal) — but
  suppression is the sender-level guarantee that survives new inbound too:
  a suppressed sender who writes again gets silence, not another invitation.

  File format: EDN lines (one map per line), append-only — an audit trail of
  when/why an address was suppressed, mirroring the corpus's journal ethos."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def stop-patterns
  "A stop request in either doctrine language. Deliberately generous — a false
  positive silences the actor toward that sender (safe direction); a false
  negative is the harm."
  [#"(?i)\bunsubscribe\b"
   #"(?i)\bopt[ -]?out\b"
   #"(?i)\bstop( e?-?mail(ing)?| contact(ing)?| send(ing)?)?\b"
   #"(?i)\bremove me\b"
   #"(?i)\bdo not (contact|email|reply|write)\b"
   #"(?i)\bno more (e?mail|messages)\b"
   #"(?i)\bleave me alone\b"
   #"配信停止" #"配信不要" #"受信拒否" #"購読解除"
   #"送(ら|信し)ないで" #"連絡(しないで|不要|無用)"
   #"返信不要です.*停止" #"やめてください" #"迷惑です"])

(defn stop-request?
  "Does this inbound subject/body read as an opt-out request?"
  [subject text]
  (let [s (str (or subject "") "\n" (or text ""))]
    (boolean (some #(re-find % s) stop-patterns))))

(defn- normalize [email]
  (some-> email str/trim str/lower-case not-empty))

(defn load-suppressed
  "Read the suppression journal → set of lowercased addresses. `read-file` is
  injected ((fn [path] string-or-nil)) so this stays pure/testable."
  [read-file path]
  (into #{}
        (keep (fn [line]
                (when-not (str/blank? line)
                  (normalize (:email (edn/read-string line))))))
        (str/split-lines (or (read-file path) ""))))

(defn suppression-entry
  "One append-only journal line for suppressing `email`."
  [email now reason]
  (pr-str {:email (normalize email) :at now :reason reason}))

(defn suppressed?
  [suppressed-set email]
  (contains? suppressed-set (normalize email)))
