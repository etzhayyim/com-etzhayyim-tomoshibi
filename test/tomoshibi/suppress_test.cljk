(ns tomoshibi.suppress-test
  (:require [clojure.test :refer [deftest is testing]]
            [tomoshibi.suppress :as suppress]))

(deftest stop-request-detection
  (testing "English"
    (is (suppress/stop-request? "unsubscribe" ""))
    (is (suppress/stop-request? "" "please STOP emailing me"))
    (is (suppress/stop-request? "" "remove me from your list"))
    (is (suppress/stop-request? "" "do not contact me again"))
    (is (suppress/stop-request? "" "opt out")))
  (testing "Japanese"
    (is (suppress/stop-request? "配信停止" ""))
    (is (suppress/stop-request? "" "今後連絡不要です"))
    (is (suppress/stop-request? "" "もう送らないでください"))
    (is (suppress/stop-request? "" "受信拒否します"))
    (is (suppress/stop-request? "" "迷惑です")))
  (testing "ordinary inquiries do NOT trip it"
    (is (not (suppress/stop-request? "質問" "etzhayyim とは何ですか?")))
    (is (not (suppress/stop-request? "hello" "what does your association believe?")))))

(deftest suppression-journal-roundtrip
  (let [state (atom "")
        read-file (fn [_path] @state)
        append (fn [line] (swap! state str line "\n"))]
    (is (empty? (suppress/load-suppressed read-file "p")))
    (append (suppress/suppression-entry "Person@Example.COM" "2026-07-12T00:00:00Z" :stop-request))
    (append (suppress/suppression-entry "other@example.com" "2026-07-12T00:00:01Z" :stop-request))
    (let [s (suppress/load-suppressed read-file "p")]
      (is (= #{"person@example.com" "other@example.com"} s))
      (testing "case-insensitive membership"
        (is (suppress/suppressed? s "PERSON@example.com"))
        (is (not (suppress/suppressed? s "third@example.com")))))))
