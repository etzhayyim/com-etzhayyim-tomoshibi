(ns tomoshibi.run-tests
  "Test runner for com-etzhayyim-tomoshibi (new actors ship run_tests.clj, not
  .sh — per etzhayyim/root CLAUDE.md). Canonical path:
  `bb run_tests.clj` (bb.edn wires the classpath, including the sibling
  etzhayyim/root sensors checkout and the kotoba-lang mail/mailer libs)."
  (:require [clojure.test :refer [run-tests]]
            [tomoshibi.agent-test]
            [tomoshibi.attest-sign-test]
            [tomoshibi.governor-test]
            [tomoshibi.graph-test]
            [tomoshibi.journal-test]
            [tomoshibi.kotoba-store-test]
            [tomoshibi.leash-test]
            [tomoshibi.mail-test]
            [tomoshibi.operation-test]
            [tomoshibi.organizer-test]
            [tomoshibi.suppress-test]))

(defn -main [& _args]
  (let [res (run-tests 'tomoshibi.governor-test
                       'tomoshibi.operation-test
                       'tomoshibi.mail-test
                       'tomoshibi.suppress-test
                       'tomoshibi.journal-test
                       'tomoshibi.leash-test
                       'tomoshibi.organizer-test
                       'tomoshibi.agent-test
                       'tomoshibi.attest-sign-test
                       'tomoshibi.kotoba-store-test
                       'tomoshibi.graph-test)]
    (when (pos? (+ (:fail res 0) (:error res 0)))
      (System/exit 1))))

(-main)
