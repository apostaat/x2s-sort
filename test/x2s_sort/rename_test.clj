(ns x2s-sort.rename-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [x2s-sort.rename :as rename])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- delete-recursively
  [^File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [child (.listFiles f)]
        (delete-recursively child)))
    (.delete f)))

(defmacro with-temp-dir
  [[sym] & body]
  `(let [dir# (.toFile (Files/createTempDirectory "x2s-sort-test" (make-array FileAttribute 0)))]
     (try
       (let [~sym dir#]
         ~@body)
       (finally
         (delete-recursively dir#)))))


(deftest pad-name-classic
  (let [names ["1" "2" "10" "11"]
        max-lens (rename/max-run-lengths names)]
    (is (= "01" (rename/pad-name "1" max-lens)))
    (is (= "02" (rename/pad-name "2" max-lens)))
    (is (= "10" (rename/pad-name "10" max-lens)))
    (is (= "11" (rename/pad-name "11" max-lens)))))

(deftest pad-name-multiple-runs
  (let [names ["S1E2" "S10E3" "S2E10"]
        max-lens (rename/max-run-lengths names)]
    (is (= "S01E02" (rename/pad-name "S1E2" max-lens)))
    (is (= "S10E03" (rename/pad-name "S10E3" max-lens)))
    (is (= "S02E10" (rename/pad-name "S2E10" max-lens)))))

(deftest rename-tree-classic
  (with-temp-dir [dir]
    (doseq [n ["1" "2" "10" "11"]]
      (spit (io/file dir (str n ".mp3")) ""))
    (rename/rename-tree! dir {:dry-run? false})
    (is (= ["01.mp3" "02.mp3" "10.mp3" "11.mp3"]
           (sort (seq (.list dir)))))))

(deftest rename-tree-nested
  (with-temp-dir [dir]
    (let [disc1 (io/file dir "Disc 1")
          disc10 (io/file dir "Disc 10")]
      (.mkdirs disc1)
      (.mkdirs disc10)
      (spit (io/file disc1 "Track 1.flac") "")
      (spit (io/file disc1 "Track 10.flac") "")
      (spit (io/file disc10 "Track 2.flac") "")
      (spit (io/file disc10 "Track 10.flac") ""))
    (rename/rename-tree! dir {:dry-run? false})
    (is (= ["Disc 01" "Disc 10"]
           (sort (seq (.list dir)))))
    (is (= ["Track 01.flac" "Track 10.flac"]
           (sort (seq (.list (io/file dir "Disc 01"))))))
    (is (= ["Track 02.flac" "Track 10.flac"]
           (sort (seq (.list (io/file dir "Disc 10"))))))))

(deftest reorder-directory-order
  (with-temp-dir [dir]
    (doseq [n ["B 02.txt" "A 01.txt" "C 10.txt"]]
      (spit (io/file dir n) ""))
    (let [moves (atom [])
          real-move @#'rename/move-file!]
      (binding [rename/*move-fn* (fn [from to]
                                   (swap! moves conj [(.getPath ^File from) (.getPath ^File to)])
                                   (real-move from to))]
        (let [{:keys [reordered-dirs]} (rename/rename-tree! dir {:dry-run? false :reorder? true})]
          (is (= 1 reordered-dirs))))
      (let [dir-path (.getPath dir)
            final-moves (->> @moves
                             (filter (fn [[_ to]]
                                       (= dir-path (.getPath (.getParentFile (io/file to)))))))
            final-names (mapv (fn [[_ to]] (.getName (io/file to))) final-moves)]
        (is (= ["A 01.txt" "B 02.txt" "C 10.txt"]
               final-names))))))
