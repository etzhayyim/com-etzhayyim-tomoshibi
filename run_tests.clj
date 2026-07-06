(ns tomoshibi.run-tests
  "Test runner for com-etzhayyim-tomoshibi (new actors ship run_tests.clj, not
  .sh — per etzhayyim/root CLAUDE.md). Canonical path:
  `bb run_tests.clj` (bb.edn wires the classpath, including the sibling
  etzhayyim/root sensors checkout)."
  (:require [clojure.test :refer [run-tests]]
            [tomoshibi.governor-test]
            [tomoshibi.operation-test]))

(defn -main [& _args]
  (let [res (run-tests 'tomoshibi.governor-test 'tomoshibi.operation-test)]
    (when (pos? (+ (:fail res 0) (:error res 0)))
      (System/exit 1))))

(-main)
