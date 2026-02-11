(ns x2s-sort.rename
  (:require [clojure.java.io :as io])
  (:import (java.io File)
           (java.nio.file Files)
           (java.util UUID)))

(defn digit-run-strings
  "Returns all digit runs in s, in order."
  [^String s]
  (vec (re-seq #"\d+" s)))

(defn max-run-lengths
  "Given a collection of names, returns a vector of max digit-run lengths
   by run index."
  [names]
  (letfn [(merge-max [acc lens]
            (let [n (max (count acc) (count lens))]
              (vec (for [i (range n)]
                     (max (get acc i 0) (get lens i 0))))))]
    (reduce (fn [acc name]
              (merge-max acc (mapv count (digit-run-strings name))))
            []
            names)))

(defn left-pad
  "Left-pad s with zeros to width."
  [^String s width]
  (let [n (count s)]
    (if (>= n width)
      s
      (str (apply str (repeat (- width n) \0)) s))))

(defn pad-name
  "Pads digit runs in name according to max-lens (by run index)."
  [^String name max-lens]
  (let [m (re-matcher #"\d+" name)]
    (loop [sb (StringBuilder.)
           last-idx 0
           run-idx 0]
      (if (.find m)
        (let [start (.start m)
              end (.end m)
              run (.group m)
              width (get max-lens run-idx (count run))]
          (.append sb (subs name last-idx start))
          (.append sb (left-pad run width))
          (recur sb end (inc run-idx)))
        (do
          (.append sb (subs name last-idx))
          (.toString sb))))))

(defn split-filename
  "Splits name into [base ext]. ext includes the dot, or empty string if none.
   Dotfiles like .gitignore are treated as having no extension."
  [^String name]
  (let [idx (.lastIndexOf name ".")]
    (if (and (pos? idx) (< idx (dec (count name))))
      [(subs name 0 idx) (subs name idx)]
      [name ""])))

(defn entry-name-for-padding
  "Returns the name portion to use for padding (base for files)."
  [^File f]
  (let [name (.getName f)]
    (if (.isFile f)
      (first (split-filename name))
      name)))

(defn padded-entry-name
  "Returns the new name for entry based on max-lens."
  [^File f max-lens]
  (let [name (.getName f)]
    (if (.isFile f)
      (let [[base ext] (split-filename name)]
        (str (pad-name base max-lens) ext))
      (pad-name name max-lens))))

(defn compute-renames
  "Returns a vector of rename maps for entries in dir.
   Each map: {:from File :to File :to-name <string>}."
  [^File dir]
  (let [entries (seq (.listFiles dir))]
    (if (empty? entries)
      []
      (let [names (map entry-name-for-padding entries)
            max-lens (max-run-lengths names)]
        (->> entries
             (map (fn [f]
                    (let [new-name (padded-entry-name f max-lens)]
                      (when (not= new-name (.getName f))
                        {:from f
                         :to-name new-name
                         :to (io/file dir new-name)}))))
             (remove nil?)
             vec)))))

(defn- compute-mapping
  "Returns a vector of mapping maps for entries in dir.
   Each map: {:from File :to-name <string> :to File}."
  [^File dir]
  (let [entries (seq (.listFiles dir))]
    (if (empty? entries)
      []
      (let [names (map entry-name-for-padding entries)
            max-lens (max-run-lengths names)]
        (mapv (fn [f]
                (let [new-name (padded-entry-name f max-lens)]
                  {:from f
                   :to-name new-name
                   :to (io/file dir new-name)}))
              entries)))))

(defn- duplicate-targets
  [renames]
  (->> renames
       (map :to-name)
       frequencies
       (filter (fn [[_ v]] (> v 1)))
       (map first)
       seq))

(defn- move-file!
  [^File from ^File to]
  (Files/move (.toPath from) (.toPath to) (make-array java.nio.file.CopyOption 0)))

(def ^:dynamic *move-fn* move-file!)

(defn apply-renames!
  "Applies renames within a directory using a two-pass temp rename."
  [^File dir renames]
  (when (seq renames)
    (when-let [dups (duplicate-targets renames)]
      (throw (ex-info "Rename would create duplicate names"
                      {:dir (.getPath dir) :duplicates dups})))
    (let [temp-renames (mapv (fn [{:keys [from to-name]}]
                               (let [tmp-name (str (.getName from) ".x2s-tmp-" (UUID/randomUUID))]
                                 {:from from
                                  :tmp (io/file dir tmp-name)
                                  :to (io/file dir to-name)}))
                             renames)]
      (doseq [{:keys [from tmp]} temp-renames]
        (*move-fn* from tmp))
      (doseq [{:keys [tmp to]} temp-renames]
        (*move-fn* tmp to)))))

(defn- reorder-directory!
  "Recreates directory entry order by moving entries into a temp dir and back
   in sorted order. Uses mapping to apply padding renames as well."
  [^File dir mapping]
  (when (seq mapping)
    (when-let [dups (duplicate-targets mapping)]
      (throw (ex-info "Rename would create duplicate names"
                      {:dir (.getPath dir) :duplicates dups})))
    (let [tmp-dir (io/file dir (str ".x2s-sort-tmp-" (UUID/randomUUID)))]
      (when-not (.mkdir tmp-dir)
        (throw (ex-info "Failed to create temp directory"
                        {:dir (.getPath dir) :tmp (.getPath tmp-dir)})))
      (try
        (doseq [{:keys [from to-name]} mapping]
          (*move-fn* from (io/file tmp-dir to-name)))
        (doseq [{:keys [to-name]} (sort-by :to-name mapping)]
          (*move-fn* (io/file tmp-dir to-name) (io/file dir to-name)))
        (finally
          (when (.exists tmp-dir)
            (.delete tmp-dir)))))))

(defn- depth
  [^File f]
  (.getNameCount (.toPath f)))

(defn rename-tree!
  "Renames all files and subdirectories under root so lexicographic order
   matches numeric order. Options: {:dry-run? true :reorder? false}.
   If reorder? is true, recreates directory entry order for players that
   ignore lexical sorting. Returns {:operations [...]}."
  [root {:keys [dry-run? reorder?] :or {dry-run? false reorder? false}}]
  (let [root-file (io/file root)]
    (when-not (.isDirectory root-file)
      (throw (ex-info "Root must be a directory" {:root root})))
    (let [dirs (->> (file-seq root-file)
                    (filter #(.isDirectory ^File %))
                    (sort-by depth >))
          ops (transient [])
          reordered (atom 0)]
      (doseq [dir dirs]
        (if reorder?
          (let [mapping (compute-mapping dir)
                renames (->> mapping
                             (filter (fn [{:keys [from to]}]
                                       (not= (.getName ^File from) (.getName ^File to))))
                             vec)]
            (when (seq mapping)
              (swap! reordered inc))
            (doseq [{:keys [from to]} renames]
              (conj! ops {:from (.getPath from) :to (.getPath to)}))
            (when-not dry-run?
              (reorder-directory! dir mapping)))
          (let [renames (compute-renames dir)]
            (when (seq renames)
              (doseq [{:keys [from to]} renames]
                (conj! ops {:from (.getPath from) :to (.getPath to)}))
              (when-not dry-run?
                (apply-renames! dir renames))))))
      {:root (.getPath root-file)
       :operations (persistent! ops)
       :reordered-dirs @reordered})))
