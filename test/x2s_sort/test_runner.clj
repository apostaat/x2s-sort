(ns x2s-sort.test-runner
  (:require [clojure.test :as t]
            [x2s-sort.rename-test]))

(defn -main [& _]
  (let [result (t/run-tests 'x2s-sort.rename-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
