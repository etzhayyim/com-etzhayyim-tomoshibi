(ns tomoshibi.mail
  "Email capability layer — PURE request/response builders only (the mailer
  convention: build a host-executable request, let the caller's injected
  :http-fn execute it). No namespace here ever opens a socket; tomoshibi.agent
  is the single place network I/O actually happens.

  Two directions:

    inbound  — pull-drain from the tomoshibi-mail Cloudflare Email Worker
               (infra/mail-worker/): GET /inbox → JSON records staged by the
               Worker's email() handler, POST /ack → delete after processing.
               Record shape mirrors cloud-itonami's mail-inbound Worker and is
               re-hydrated through kotoba-lang mail.inbound/from-parts.

    outbound — REPLY-ONLY. `reply-message` is the only constructor and it
               REQUIRES an inbound record: the recipient is structurally the
               sender of a mail that arrived first (inbound-initiated
               dialogue, root ADR-2607121830). There is no API in this actor
               to send to an arbitrary address — cold outreach is not a
               policy setting, it is unrepresentable. Wire format is
               kotoba-lang mailer.core's :resend request, augmented with the
               RFC headers mailer.core does not emit (In-Reply-To /
               References / List-Unsubscribe / Auto-Submitted)."
  (:require [clojure.string :as str]
            [mail.inbound :as inbound]
            [mail.message :as message]
            [mailer.core :as mailer]))

;; ---------------------------------------------------------------------------
;; inbound: worker pull API (pure builders)
;; ---------------------------------------------------------------------------

(defn fetch-request
  "Request to list up to `limit` staged inbound mails from the Worker."
  [{:keys [pull-url pull-token]} limit]
  {:method :get
   :url (str pull-url "/inbox?limit=" (long limit))
   :headers {"Authorization" (str "Bearer " pull-token)}})

(defn ack-request
  "Request to delete one staged inbound mail (after processing) by KV key."
  [{:keys [pull-url pull-token]} kv-key]
  {:method :post
   :url (str pull-url "/ack")
   :headers {"Authorization" (str "Bearer " pull-token)
             "Content-Type" "application/json"}
   :json {:key kv-key}})

(defn- addr-spec
  "Extract the bare addr-spec from a possibly display-form address string
  (\"Name <a@b.c>\" → \"a@b.c\"). Cloudflare's envelope `from` is normally
  bare already; this is defense for display-form values."
  [s]
  (when s
    (if-let [[_ inner] (re-find #"<([^<>\s]+)>" (str s))]
      inner
      (str s))))

(defn parse-inbox
  "Parse the Worker's GET /inbox response body (already json-read into a map)
  → seq of {:kv-key … :record …} where :record is a mail.inbound record."
  [body]
  (for [{:keys [key value]} (:messages body)]
    {:kv-key key
     :record (inbound/from-parts
              {:provider (:provider value "cloudflare-email-routing")
               :provider-message-id (:provider_message_id value)
               :from (addr-spec (:from value))
               :to (:to value)
               :cc (:cc value)
               :subject (:subject value)
               :text (:text value)
               :html (:html value)
               :headers (:headers value)
               :received-at (:received_at value)
               :spf (keyword (:spf value "none"))
               :dkim (keyword (:dkim value "none"))
               :dmarc (keyword (:dmarc value "none"))
               :attachments (:attachments value [])})}))

(defn sender-email
  "The envelope sender of an inbound record (lowercased address string)."
  [inb]
  (get-in inb [:mail.inbound/message :mail/from :mail.address/email]))

(defn inbound-text
  "Plain-text body of an inbound record (falls back to html-stripped-ish or
  empty). Truncated to `max-chars` so a hostile 10MB body can't blow the
  organizer's context."
  [inb max-chars]
  (let [m (:mail.inbound/message inb)
        text (or (some #(when (str/starts-with? (:mail.part/content-type %) "text/plain")
                          (:mail.part/body %))
                       (:mail/parts m))
                 "")]
    (subs text 0 (min (count text) max-chars))))

(defn auto-generated?
  "True when the inbound mail is itself machine-generated (bounces, vacation
  autoresponders, list traffic). Replying to these creates mail loops — they
  are acked and never answered. RFC 3834 Auto-Submitted, Precedence
  bulk/junk/list, and the mailer-daemon/no-reply sender conventions."
  [inb]
  (let [m (:mail.inbound/message inb)
        headers (into {} (map (fn [[k v]] [(str/lower-case (name k)) (str v)])
                              (:mail/headers m)))
        auto-submitted (str/lower-case (get headers "auto-submitted" "no"))
        precedence (str/lower-case (get headers "precedence" ""))
        from (or (sender-email inb) "")]
    (boolean
     (or (not= auto-submitted "no")
         (contains? #{"bulk" "junk" "list"} precedence)
         (re-find #"(?i)^(mailer-daemon|postmaster|no-?reply|bounce)[@+.-]" from)))))

;; ---------------------------------------------------------------------------
;; outbound: reply construction (REPLY-ONLY, the structural invariant)
;; ---------------------------------------------------------------------------

(def opt-out-footer
  "Attached verbatim to every reply. This is the §1.16(d) opt-out affordance
  the governor is told about via :opt-out-present? true — the flag is never
  set without this footer actually being present (see reply-message)."
  (str "--\n"
       "tomoshibi (灯) — etzhayyim (エツ・ハイム) の招待 actor / AI agent です。\n"
       "This is tomoshibi, an AI agent of the etzhayyim religious association.\n"
       "返信不要です。今後の連絡が不要な場合は「配信停止」とだけ返信してください — 即時に停止します。\n"
       "No pressure — if you'd rather not hear from us, reply \"unsubscribe\" and we will stop immediately.\n"
       "https://etzhayyim.com"))

(defn- re-subject [subject]
  (let [s (str/trim (str subject))]
    (if (re-find #"(?i)^re:" s) s (str "Re: " (if (str/blank? s) "(no subject)" s)))))

(defn reply-message
  "Build the reply to ONE inbound record. The recipient is derived from the
  inbound sender and nothing else — this function signature is the reply-only
  invariant. Returns a kotoba-lang mail.message with threading headers and
  the opt-out footer appended."
  [inb draft-text {:keys [from-email from-name]}]
  (let [to (sender-email inb)
        msg-id (:mail.inbound/provider-message-id inb)
        subject (get-in inb [:mail.inbound/message :mail/subject])]
    (when (str/blank? to)
      (throw (ex-info "reply requires an inbound sender" {:inbound-id msg-id})))
    (when (str/blank? (str draft-text))
      (throw (ex-info "reply requires a non-empty draft" {:inbound-id msg-id})))
    (message/message
     {:from {:email from-email :mail.address/name (or from-name "tomoshibi (灯)")}
      :to [to]
      :subject (re-subject subject)
      :text (str (str/trim draft-text) "\n\n" opt-out-footer)
      :headers (cond-> {"Auto-Submitted" "auto-replied"
                        "List-Unsubscribe"
                        (str "<mailto:" from-email "?subject=unsubscribe>")}
                 msg-id (assoc "In-Reply-To" msg-id "References" msg-id))})))

(defn send-request
  "Turn a reply message into the host-executable Resend request
  (mailer.core/request :resend), re-attaching the RFC threading headers that
  mailer.core's wire format drops."
  [reply-msg]
  (let [req (mailer/request :resend {:mail.effect/type :mail/send
                                     :mail.effect/message reply-msg})
        headers (:mail/headers reply-msg)]
    (cond-> req
      (seq headers) (update :http/json assoc :headers headers))))

(defn parse-send-response
  "Resend's POST /emails response body (json-read) → {:ok? bool :id …}."
  [status body]
  (if (and status (< status 300))
    {:ok? true :id (:id body)}
    {:ok? false :status status :error (or (:message body) (pr-str body))}))
