#!/usr/bin/env bb

(ns eamonnsullivan.backup
  {:author "Eamonn Sullivan"}
  (:import (java.time.format DateTimeFormatter)
           (java.time LocalDateTime))
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]))

(def base-path "/home/eamonn/backup-test")
(def rsync ["rsync" "-avzpH" "--partial" "--delete" "--exclude-from=/etc/rsync-backup-excludes.txt"])
(def month-formatter (DateTimeFormatter/ofPattern "yyyy-MM"))
(def now-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-MM"))

(defn get-current-month []
  (.format (LocalDateTime/now) month-formatter))

(defn get-now-as-string []
  (.format (LocalDateTime/now) now-formatter))

(defn write-check-month-file [content]
  (spit (format "%s/checkMonth.txt" base-path) content))

(defn new-month?
  [last]
  (let [now (get-current-month)]
    (not= now last)))

(defn delete-files-recursively
  [f1 & [silently]]
  (when (.isDirectory (io/file f1))
    (doseq [f2 (.listFiles (io/file f1))]
      (delete-files-recursively f2 silently)))
  (io/delete-file f1 silently))

(defn get-backup-usage
  "How much disk space is this back up using?"
  [backup]
  (as-> (io/file (format "%s/%s" base-path backup)) $
    (file-seq $)
    (map #(.length %) $)
    (reduce + $)))

(defn link
  "Create a hard link from path to target."
  [path target]
  (sh "cp" "-al" path target))

(defn check-month
  "Check whether we've started a new month. If we have, create a new
  backup from scratch."
  [backup]
  (let [check (io/as-file (format "%s/checkMonth.txt" base-path))
        last (if (.exists check) (slurp check) "")]
    (when (new-month? last)
      (println "Staring a new monthly backup set...")
      (let [backupdir (io/as-file (format "%s/%s/." base-path backup))]
        (when (.exists backupdir)
          (delete-files-recursively (io/as-file (format "%s/%s" base-path backup))))
        (io/make-parents backupdir))
      (write-check-month-file (get-current-month)))))

(defn make-hard-link
  "Make a hard link of the current back up."
  [backup]
  (let [bdir (format "%s-%s" (get-now-as-string) backup)
        linkto (format "%s/old/%s" base-path bdir)
        linkfrom (format "%s/%s/" base-path backup)]
    (io/make-parents (io/as-file linkto))
    (link linkfrom linkto)))

(defn get-oldest-backup-dir
  "Find the oldest backup in /old."
  []
  (let [old-backups (.listFiles (io/as-file (format "%s/old" base-path)))
        sorted (sort-by #(.lastModified %) old-backups)]
    (first sorted)))

(defn check-free
  "Check the free space on the backup device. If it isn't at
  least twice the size of the last (current) backup, delete the oldest
  backup and try again. Repeat until enough space."
  [backup]
  (let [curr-used (get-backup-usage backup)
        est-need (* curr-used 2)]
    (loop [curr-free (.getFreeSpace (io/as-file base-path))]
      (if (> curr-free est-need)
        true
        (do
          (println "Not enough disk space available, so removing the oldest backup.")
          (delete-files-recursively (get-oldest-backup-dir))
          (recur (.getFreeSpace (io/as-file base-path))))))))

(let [[backup-from backup-to] *command-line-args*]
  (when (or (not backup-from) (not backup-to))
    (println "Usage: <user@hostname.local:/home> <backup-name>")
    (System/exit 1))
  (println "Backing up from:" backup-from)
  (println "Backing up to:" backup-to)
  (check-month backup-to)
  (let [rsync-command (into [] (conj rsync backup-from (format "%s/%s" base-path backup-to)))
        _ (println "Running command:" rsync-command)
        result (apply sh rsync-command)]
    (println "Result:" (:out result))
    (println "Errors:" (:err result)))
  (make-hard-link backup-to)
  (check-free backup-to))
(println (get-current-month))

(comment
  (import (java.time.format DateTimeFormatter))
  (import (java.time LocalDateTime))
  (require '[clojure.java.io :as io])
  (require '[clojure.java.shell :refer [sh]])
  (def base-path "/home/eamonn/backup-test/")
  )
