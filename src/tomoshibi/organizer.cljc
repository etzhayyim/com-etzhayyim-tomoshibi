(ns tomoshibi.organizer
  "The contained-intelligence node: drafts ONE reply to ONE inbound inquiry
  via Murakumo fleet inference (node-local Ollama, gemma4 family — the
  DEFAULT-PREFERRED compute path per Rider v3.3 §2(i)). Proposal-only: the
  draft goes to the EvangelismGovernor via tomoshibi.operation/propose!;
  nothing here can send.

  Pure builders + injected :http-fn, same seam as kouhou.advisor. The host
  allowlist mirrors kouhou.advisor/allowed-infer-hosts — inference may only
  go to Murakumo endpoints (node-local Ollama / LiteLLM / evo-x2), never an
  arbitrary URL.

  Fail-closed: a failed/empty draft returns nil and the agent SKIPS the
  message this tick (bounded retries in tomoshibi.journal) — there is no
  template fallback, because a canned reply to a personal inquiry would be
  worse than silence (contrast: the organism's narration fail-open template,
  where the audience is a public log, not a correspondent)."
  (:require [clojure.string :as str]))

(def allowed-infer-hosts
  "Murakumo-only inference endpoints (kouhou.advisor precedent + the
  zebulun/fleet tailnet Ollama)."
  #{"127.0.0.1:11434" "localhost:11434"
    "127.0.0.1:4000" "localhost:4000"
    "192.168.1.70:4000" "192.168.1.16:11434"})

(defn assert-murakumo!
  "Throw unless `url`'s host:port is an allowed Murakumo inference endpoint."
  [url]
  (let [[_ host-port] (re-find #"^https?://([^/]+)" (str url))]
    (when-not (contains? allowed-infer-hosts host-port)
      (throw (ex-info "inference host is not a Murakumo endpoint"
                      {:url url :allowed allowed-infer-hosts})))
    url))

(def system-prompt
  "The persona + hard content constraints. These duplicate the evangelism
  gate's prohibitions ON PURPOSE — the prompt is the first line, the gate is
  the enforcement; a draft that ignores the prompt is HELD by the governor,
  never sent."
  (str
   "You are tomoshibi (灯), the invitational digital agent of etzhayyim "
   "(エツ・ハイム / עץ חיים, Tree of Life) — a small religious voluntary association "
   "whose mission is the structural liberation of humanity from compelled labor, "
   "multi-generational wellbecoming (children and grandchildren first), and "
   "non-individualist mutuality. It synthesizes Japanese values (八百万・縁起・産霊・和) "
   "with Protestant Christianity. It is NON-eschatological: never speak of end times, "
   "rapture, or damnation. The Kingdom it invites people into is here and now.\n\n"
   "You are replying by email to a person who wrote to tomoshibi@etzhayyim.com first. "
   "Rules, absolute:\n"
   "- Reply in the sender's language (Japanese or English; mirror them).\n"
   "- Be warm, brief (under 250 words), and honest. Answer their actual question.\n"
   "- Say plainly you are an AI agent of the association if relevance arises; never pretend to be human.\n"
   "- NEVER pressure, threaten, or create urgency or fear. No 'you must', no consequences, "
   "no soul-at-risk language. An invitation is open or it is nothing.\n"
   "- NEVER reference or exploit personal vulnerability (grief, loneliness, debt, illness) "
   "as a reason to join, even if the sender mentions it. Compassion yes; leverage never.\n"
   "- If the sender appears to be a minor, answer factually and kindly, and suggest they "
   "explore this together with their family or guardian. Do not invite a minor alone.\n"
   "- NEVER ask for money, donations, personal data, credentials, or a meeting.\n"
   "- Respect refusal instantly and gracefully; if they seem uninterested, thank them and close.\n"
   "- You may point to https://etzhayyim.com for more.\n"
   "- Output ONLY the reply body text. No subject line, no signature (a footer is appended "
   "for you), no markdown headers."))

(defn draft-request
  "Host-executable Ollama /api/chat request for one inbound inquiry.
  `inb-summary` is {:from … :subject … :text …} (already truncated)."
  [{:keys [ollama-url model temperature num-predict]
    :or {model "gemma4:12b-it-qat" temperature 0.4 num-predict 500}}
   inb-summary]
  {:method :post
   :url (str (assert-murakumo! ollama-url) "/api/chat")
   :headers {"Content-Type" "application/json"}
   :timeout-ms 120000
   :json {:model model
          :think false
          :stream false
          :messages [{:role "system" :content system-prompt}
                     {:role "user"
                      :content (str "From: " (:from inb-summary) "\n"
                                    "Subject: " (:subject inb-summary) "\n\n"
                                    (:text inb-summary))}]
          :options {:temperature temperature :num_predict num-predict}}})

(defn parse-draft
  "Ollama /api/chat response body (json-read) → trimmed draft text, or nil
  when empty/failed (fail-closed)."
  [body]
  (let [text (some-> body :message :content str/trim)]
    (when-not (str/blank? text) text)))
