(ns tomoshibi.organizer-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tomoshibi.organizer :as organizer]))

(deftest murakumo-allowlist-is-enforced
  (is (= "http://127.0.0.1:11434" (organizer/assert-murakumo! "http://127.0.0.1:11434")))
  (is (thrown? #?(:clj Exception :cljs :default)
               (organizer/assert-murakumo! "https://api.openai.com")))
  (is (thrown? #?(:clj Exception :cljs :default)
               (organizer/assert-murakumo! "http://127.0.0.1:9999"))))

(deftest draft-request-shape
  (let [req (organizer/draft-request {:ollama-url "http://127.0.0.1:11434"}
                                     {:from "s@example.com" :subject "q" :text "body"})]
    (is (= "http://127.0.0.1:11434/api/chat" (:url req)))
    (testing "gemma think tokens off, bounded output, non-streaming"
      (is (false? (get-in req [:json :think])))
      (is (false? (get-in req [:json :stream])))
      (is (pos? (get-in req [:json :options :num_predict]))))
    (testing "system prompt carries the hard prohibitions"
      (let [sys (get-in req [:json :messages 0 :content])]
        (is (str/includes? sys "NEVER pressure"))
        (is (str/includes? sys "minor"))
        (is (str/includes? sys "NEVER ask for money"))))
    (is (str/includes? (get-in req [:json :messages 1 :content]) "s@example.com"))))

(deftest parse-draft-fail-closed
  (is (= "text" (organizer/parse-draft {:message {:content "  text  "}})))
  (is (nil? (organizer/parse-draft {:message {:content "   "}})))
  (is (nil? (organizer/parse-draft {})))
  (is (nil? (organizer/parse-draft nil))))
