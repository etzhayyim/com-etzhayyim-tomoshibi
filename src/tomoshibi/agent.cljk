(ns tomoshibi.agent
  "One bounded tick of the resident mail agent — the durable-outer-loop
  pattern (cloud-itonami business_loop precedent): the loop that REPEATS this
  lives outside (tomoshibi.daemon under launchd); this namespace is the pure
  orchestration of a single pass, every effect injected, so the whole decision
  surface is testable offline.

  Per inbound message, in order (each step can only make the actor QUIETER):

    leash          revoked/missing → the tick does nothing at all
    dedup          already terminal for this message-id → never again
    auto-loop      machine-generated mail (bounces, autoresponders) → ack, silence
    stop-request   sender asked to stop → suppress forever, ack, silence
    suppression    sender previously opted out → ack, silence
    retries        drafting failed max-attempts times → give up, ack, silence
    draft          Murakumo organizer; nil (inference down) → retry next tick
    budget         daily send cap reached → leave message for tomorrow
    GOVERNOR       evangelism-gate on the FULL outgoing text (draft + opt-out
                   footer); HOLD → never sent, logged, done
    send           Resend; failure → no attestation, retry next tick
    attest         ONLY after a confirmed send does propose! run to write the
                   evangelismActivityAttestation (a ledger row must never
                   claim an activity that a failed send didn't perform; the
                   gate verdict propose! re-derives is deterministic, so the
                   pre-send check and the attest-time check cannot disagree)

  The reply-only invariant is upstream of all of this: the only send path is
  mail/reply-message over a fetched inbound record (see tomoshibi.mail)."
  (:require [tomoshibi.governor :as governor]
            [tomoshibi.journal :as journal]
            [tomoshibi.leash :as leash]
            [tomoshibi.mail :as mail]
            [tomoshibi.operation :as operation]
            [tomoshibi.suppress :as suppress]))

(defn- proposal [text actor-did]
  {:effect :assessment
   :text text
   :opt-out-present? true   ; true because mail/reply-message ALWAYS appends the footer
   :mode "digital"
   :actor-did actor-did})

(defn process-message!
  "Run one inbound {:kv-key … :record …} through the pipeline. Returns
  {:id … :outcome …} where outcome ∈ #{:skipped :auto :suppressed
  :already-suppressed :gave-up :draft-failed :budget-exhausted :held
  :replied :send-failed}."
  [{:keys [now-fn ack! draft! send! store read-file append-line paths cfg
           attest-sign! propose-fn]
    :or {propose-fn operation/propose!}}
   processed suppressed {:keys [kv-key record]}]
  (let [now (now-fn)
        today (subs now 0 10)
        id (:mail.inbound/provider-message-id record)
        sender (mail/reply-address record)
        subject (get-in record [:mail.inbound/message :mail/subject])
        n-attempts (journal/attempts processed id)
        finish! (fn [status & {:keys [ack?] :or {ack? true}}]
                  (append-line (:processed paths)
                               (journal/processed-entry id status n-attempts now))
                  (when ack? (ack! kv-key))
                  {:id id :outcome status})]
    (cond
      (journal/done? processed id)
      (do (ack! kv-key) {:id id :outcome :skipped})

      (nil? sender)
      (finish! :skipped)

      (mail/auto-generated? record)
      (finish! :auto)

      (suppress/stop-request? subject (mail/inbound-text record 2000))
      (do (append-line (:suppress paths)
                       (suppress/suppression-entry sender now :stop-request))
          (finish! :suppressed))

      (suppress/suppressed? suppressed sender)
      (finish! :already-suppressed)

      (>= n-attempts (:max-attempts cfg 3))
      (finish! :gave-up)

      :else
      (let [draft (draft! {:from sender
                           :subject subject
                           :text (mail/inbound-text record (:body-max-chars cfg 4000))})]
        (cond
          (nil? draft)
          (do (append-line (:processed paths)
                           (journal/processed-entry id :pending (inc n-attempts) now))
              {:id id :outcome :draft-failed})

          (not (journal/send-allowed? read-file (:budget paths) today
                                      (:max-per-day cfg 20)))
          {:id id :outcome :budget-exhausted}

          :else
          (let [reply (mail/reply-message record draft
                                          {:from-email (:from-email cfg)
                                           :from-name (:from-name cfg)})
                full-text (get-in reply [:mail/parts 0 :mail.part/body])
                prop (proposal full-text (:actor-did cfg))
                verdict (governor/check {:op :mail-reply}
                                        {:actor-id (:actor-did cfg)} prop)]
            (if-not (:ok? verdict)
              (do (append-line (:ops paths)
                               (pr-str (governor/hold-invitation
                                        {:op :mail-reply}
                                        {:actor-id (:actor-did cfg)} verdict)))
                  (finish! :held))
              (let [res (send! reply)]
                (if (:ok? res)
                  (let [{:keys [attestation]}
                        (propose-fn store {:op :mail-reply}
                                    {:actor-id (:actor-did cfg)}
                                    prop now (:actor-did cfg))]
                    ;; sigref AFTER the attestation exists; signing failure can
                    ;; never block the reply path (fail-open on signing only)
                    (when (and attest-sign! attestation)
                      (attest-sign! attestation))
                    (append-line (:budget paths)
                                 (journal/send-entry id (:id res) now))
                    (finish! :replied))
                  (do (append-line (:processed paths)
                                   (journal/processed-entry id :pending (inc n-attempts) now))
                      (append-line (:ops paths)
                                   (pr-str {:t :send-failed :id id :at now
                                            :error (:error res) :status (:status res)}))
                      {:id id :outcome :send-failed}))))))))))

(defn tick!
  "One bounded pass. Fetches at most :max-per-tick staged inbound mails and
  runs each through process-message!. Stops early when the daily budget is
  exhausted. Returns {:leash … :outcomes [...]}"
  [{:keys [read-file fetch! paths cfg leash-ok?] :as ctx}]
  ;; leash-ok? (injected) = the member-signed v1 path (daemon caches the JVM
  ;; signature verification); absent → legacy v0 file check (offline suite).
  (if-not (if leash-ok? (leash-ok?) (leash/active? read-file (:leash paths)))
    {:leash :revoked :outcomes []}
    (let [processed (journal/load-processed read-file (:processed paths))
          suppressed (suppress/load-suppressed read-file (:suppress paths))
          msgs (fetch! (:max-per-tick cfg 5))
          outcomes (reduce (fn [acc msg]
                             (let [r (process-message! ctx processed suppressed msg)]
                               (if (= :budget-exhausted (:outcome r))
                                 (reduced (conj acc r))
                                 (conj acc r))))
                           [] msgs)]
      {:leash :active :outcomes outcomes})))
