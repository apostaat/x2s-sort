(ns x2s-sort.core
  (:require [clojure.string :as str]
            [x2s-sort.gui :as gui]
            [x2s-sort.rename :as rename]))

(defn- usage []
  (str/join
   "\n"
   ["Usage: clojure -M -m x2s-sort.core [--gui] [--dry-run] [--reorder]"
    "       clojure -M -m x2s-sort.core <path> [--dry-run] [--reorder]"
    ""
    "Options:"
    "  --dry-run   Print planned renames without changing files"
    "  --reorder   Recreate directory entry order (for players that ignore sorting)"
    "  --gui       Choose a folder via dialog (default if no args)"]))

(defn- parse-args [args]
  (loop [args args
         opts {:dry-run? false
               :reorder? false
               :gui? false
               :path nil}]
    (if (empty? args)
      opts
      (let [arg (first args)]
        (cond
          (= arg "--dry-run") (recur (rest args) (assoc opts :dry-run? true))
          (= arg "--gui") (recur (rest args) (assoc opts :gui? true))
          (= arg "--reorder") (recur (rest args) (assoc opts :reorder? true))
          (= arg "--help") (recur (rest args) (assoc opts :help? true))
          (str/starts-with? arg "-") (recur (rest args) (assoc opts :unknown arg))
          :else (if (:path opts)
                  (recur (rest args) (assoc opts :extra arg))
                  (recur (rest args) (assoc opts :path arg))))))))

(defn- print-ops [ops]
  (doseq [{:keys [from to]} ops]
    (println from "->" to)))

(defn -main [& args]
  (let [parsed (parse-args args)
        {:keys [dry-run? reorder? gui? path help? unknown extra]} (if (empty? args)
                                                                    (assoc parsed :gui? true)
                                                                    parsed)]
    (cond
      help?
      (println (usage))

      unknown
      (do
        (binding [*out* *err*]
          (println "Unknown option:" unknown))
        (println (usage))
        (System/exit 1))

      extra
      (do
        (binding [*out* *err*]
          (println "Too many arguments:" extra))
        (println (usage))
        (System/exit 1))

      gui?
      (gui/launch!)

      :else
      (let [root path]
        (if (nil? root)
          (do
            (binding [*out* *err*]
              (println "No folder selected."))
            (println (usage))
            (System/exit 1))
          (try
            (let [{:keys [operations reordered-dirs]} (rename/rename-tree! root {:dry-run? dry-run? :reorder? reorder?})]
              (if (seq operations)
                (do
                  (println (if dry-run? "Planned renames:" "Renamed:"))
                  (print-ops operations)
                  (println "Total:" (count operations)))
                (println (if reorder? "No renames." "Nothing to rename.")))
              (when reorder?
                (println "Reordered directories:" reordered-dirs)))
            (catch Exception e
              (binding [*out* *err*]
                (println "Error:" (.getMessage e)))
              (when-let [data (ex-data e)]
                (binding [*out* *err*]
                  (println "Details:" data)))
              (System/exit 1))))))))
