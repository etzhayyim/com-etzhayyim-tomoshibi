(ns tomoshibi.mail-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tomoshibi.mail :as mail]))

(def worker-body
  {:ok true
   :messages
   [{:key "inbox:000001752300000000-abcd1234"
     :value {:provider "cloudflare-email-routing"
             :provider_message_id "<q1@example.com>"
             :from "Seeker <seeker@example.com>"
             :to ["tomoshibi@etzhayyim.com"]
             :subject "教えてください"
             :text "etzhayyim について知りたいです。"
             :headers {}
             :received_at "2026-07-12T09:00:00Z"
             :spf "pass" :dkim "pass" :dmarc "pass"
             :attachments []}}]})

(deftest parse-inbox-hydrates-inbound-records
  (let [[{:keys [kv-key record]}] (vec (mail/parse-inbox worker-body))]
    (is (= "inbox:000001752300000000-abcd1234" kv-key))
    (is (= "<q1@example.com>" (:mail.inbound/provider-message-id record)))
    (is (= "seeker@example.com" (mail/sender-email record)))
    (is (str/includes? (mail/inbound-text record 4000) "知りたい"))))

(deftest inbound-text-truncates
  (let [[{:keys [record]}] (vec (mail/parse-inbox
                                 (assoc-in worker-body [:messages 0 :value :text]
                                           (apply str (repeat 100 "a")))))]
    (is (= 10 (count (mail/inbound-text record 10))))))

(deftest auto-generated-detection
  (let [mk (fn [overrides]
             (-> worker-body
                 (update-in [:messages 0 :value] merge overrides)
                 mail/parse-inbox vec first :record))]
    (testing "human mail is not auto"
      (is (not (mail/auto-generated? (mk {})))))
    (testing "Auto-Submitted header"
      (is (mail/auto-generated? (mk {:headers {:auto-submitted "auto-replied"}}))))
    (testing "Precedence bulk"
      (is (mail/auto-generated? (mk {:headers {:precedence "bulk"}}))))
    (testing "mailer-daemon sender"
      (is (mail/auto-generated? (mk {:from "MAILER-DAEMON@mx.example.com"}))))
    (testing "no-reply sender"
      (is (mail/auto-generated? (mk {:from "noreply@corp.example.com"}))))
    (testing "machine ENVELOPE sender (header From looks human)"
      (is (mail/auto-generated? (mk {:envelope_from "bounce-xyz@ses.example.com"}))))
    (testing "provider envelope (SES return-path) alone is NOT auto"
      (is (not (mail/auto-generated?
                (mk {:envelope_from "0106abc-000@send.provider.example"})))))))

(deftest reply-address-prefers-reply-to-never-envelope
  (let [mk (fn [overrides]
             (-> worker-body
                 (update-in [:messages 0 :value] merge overrides)
                 mail/parse-inbox vec first :record))]
    (testing "Reply-To wins over header From"
      (is (= "human@example.com"
             (mail/reply-address (mk {:reply_to "Human <human@example.com>"})))))
    (testing "header From when no Reply-To"
      (is (= "seeker@example.com" (mail/reply-address (mk {})))))
    (testing "the SMTP envelope sender is NEVER the reply target"
      (let [rec (mk {:from "Real Person <person@example.com>"
                     :envelope_from "0106abc@send.ses.example"})]
        (is (= "person@example.com" (mail/reply-address rec)))
        (is (= "0106abc@send.ses.example" (:mail.inbound/envelope-from rec)))))))

(deftest reply-message-threads-and-carries-opt-out
  (let [[{:keys [record]}] (vec (mail/parse-inbox worker-body))
        reply (mail/reply-message record "喜んでお答えします。"
                                  {:from-email "tomoshibi@etzhayyim.com"})]
    (testing "reply goes to the inbound sender and no one else"
      (is (= ["seeker@example.com"]
             (mapv :mail.address/email (:mail/to reply))))
      (is (empty? (:mail/cc reply)))
      (is (empty? (:mail/bcc reply))))
    (testing "threading + auto-reply + unsubscribe headers"
      (is (= "<q1@example.com>" (get (:mail/headers reply) "In-Reply-To")))
      (is (= "auto-replied" (get (:mail/headers reply) "Auto-Submitted")))
      (is (str/includes? (get (:mail/headers reply) "List-Unsubscribe")
                         "tomoshibi@etzhayyim.com")))
    (testing "subject is Re:-prefixed exactly once"
      (is (= "Re: 教えてください" (:mail/subject reply)))
      (is (= "Re: 教えてください"
             (:mail/subject (mail/reply-message
                             (assoc-in record [:mail.inbound/message :mail/subject]
                                       "Re: 教えてください")
                             "x" {:from-email "tomoshibi@etzhayyim.com"})))))
    (testing "the opt-out footer is REALLY in the body (the :opt-out-present?
              flag the governor sees is never asserted without it)"
      (let [body (get-in reply [:mail/parts 0 :mail.part/body])]
        (is (str/includes? body "配信停止"))
        (is (str/includes? body "unsubscribe"))
        (is (str/includes? body "No pressure"))))))

(deftest reply-message-requires-sender-and-draft
  (let [[{:keys [record]}] (vec (mail/parse-inbox worker-body))]
    (is (thrown? #?(:clj Exception :cljs :default)
                 (mail/reply-message record "  " {:from-email "t@e.com"})))
    (is (thrown? #?(:clj Exception :cljs :default)
                 (mail/reply-message
                  (assoc-in record [:mail.inbound/message :mail/from] {})
                  "text" {:from-email "t@e.com"})))))

(deftest send-request-is-resend-wire-with-headers
  (let [[{:keys [record]}] (vec (mail/parse-inbox worker-body))
        reply (mail/reply-message record "body" {:from-email "tomoshibi@etzhayyim.com"})
        req (mail/send-request reply)]
    (is (= "https://api.resend.com/emails" (:http/url req)))
    (is (= :post (:http/method req)))
    (is (= ["seeker@example.com"] (get-in req [:http/json :to])))
    (testing "Resend requires STRING addresses — a named from must be
              flattened to \"Name <addr>\", never an {:email :name} object
              (live 422 otherwise)"
      (is (= "tomoshibi (灯) <tomoshibi@etzhayyim.com>"
             (get-in (mail/send-request
                      (mail/reply-message record "body"
                                          {:from-email "tomoshibi@etzhayyim.com"
                                           :from-name "tomoshibi (灯)"}))
                     [:http/json :from])))
      (is (string? (get-in req [:http/json :from])))
      (is (every? string? (get-in req [:http/json :to]))))
    (testing "threading headers survive into the Resend payload (mailer.core
              alone drops them — send-request re-attaches)"
      (is (= "<q1@example.com>" (get-in req [:http/json :headers "In-Reply-To"]))))))

(deftest parse-send-response-cases
  (is (= {:ok? true :id "re_123"} (mail/parse-send-response 200 {:id "re_123"})))
  (is (false? (:ok? (mail/parse-send-response 422 {:message "nope"}))))
  (is (false? (:ok? (mail/parse-send-response nil nil)))))
