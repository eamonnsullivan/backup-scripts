#!/usr/bin/env bb

(ns eamonnsullivan.backup
  "A wrapper around rsync that manages multiple backups of multiple
  hosts. After each backup, it creates a hard link (effectively
  creating a snapshot of the current difference since the previous
  backup) and checks the free space available on the storage
  device. If free space is less than double the size of the most
  recent backup, we remove older backups.

  At the start of a new month, we begin a fresh backup. This is to
  avoid any potential issues with bad sectors.

  Usage: <backup-from> <name-of-backup>

  Examples: backup.clj root@thinkpad.local:/home thinkpad
            backup.clj sullie09@mc-s105910.local:~ workmac"
  {:author "Eamonn Sullivan"}
  (:import (java.time.format DateTimeFormatter)
           (java.time LocalDateTime))
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as string]
            [babashka.fs :as fs]))

(def ^:dynamic *base-path* "/media/backup")
(def ^:dynamic *rsync-command* ["rsync" "-avzpH" "--partial" "--delete" "--exclude-from=/etc/rsync-backup-excludes.txt"])

(def month-formatter (DateTimeFormatter/ofPattern "yyyy-MM"))
(def date-time-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-mm-s"))

(defn retry
  "Retry function f, n times and check whether it succeeded with the
  predicate success?"
  ([f success? num-retries]
   (retry f success? num-retries 30000))
  ([f success? num-retries wait-time]
  (if (zero? num-retries)
    (f)
    (let [result (f)]
      (if (not (success? result))
        (do
          (println (format "Function failed with error [%s], retrying" (:err result)))
          (Thread/sleep wait-time)
          (retry f success? (dec num-retries) wait-time))
        result)))))

(defn get-current-month
  "Get a string that includes the year and month."
  []
  (.format (LocalDateTime/now) month-formatter))

(defn link
  "Create a hard link from path to target."
  [path target]
  (sh "cp" "-al" path target))

(defn write-check-month-file
  "Write out the current year and month into a backup-specific file that
  we can compare against. Used so that we can more easily tell when
  we've changed months since the last backup."
  [content backup]
  (spit (format "%s/checkMonth_%s.txt" *base-path* backup) content))

(defn new-month?
  "Predicate: Given the contents of this backup's checkMonth file, are
  we creating the first back up of this month?"
  [last]
  (let [now (get-current-month)]
    (not= now last)))

(defn delete-backup
  "Remove the current named backup. This is normally done when we are
  starting the first backup of the month."
  [backup]
  (let [target (format "%s/%s" *base-path* backup)]
    (println "Removing: " target)
    (fs/delete-tree target)))

(defn get-backup-usage
  "How much disk space is this backup using?"
  [backup]
  (let [path (format "%s/%s" *base-path* backup)
        fsize (sh "du" "-sb" path)
        outsize (first (string/split (:out fsize) #"\s"))]
    (println "Size of current backup: " outsize)
    (bigint (string/trim outsize))))

(defn check-month
  "Check whether we've started a new month. If we have, create a new
  backup from scratch."
  [backup]
  (let [check (io/as-file (format "%s/checkMonth_%s.txt" *base-path* backup))
        last (if (.exists check) (slurp check) nil)]
    (when (new-month? last)
      (println (format "Starting a new monthly backup set for %s" (format "%s/%s" *base-path* backup)))
      (let [backupdir (io/as-file (format "%s/%s" *base-path* backup))]
        (when (.exists backupdir)
          (println "Existing backup found, so removing..."  )
          (delete-backup backup))
        (io/make-parents (format "%s/.keep" (.getPath backupdir))))
      (write-check-month-file (get-current-month) backup))))

(defn make-hard-link
  "Make a hard link of the current back up."
  [backup bdir]
  (let [linkto (format "%s/old/%s" *base-path* bdir)
        linkfrom (format "%s/%s/" *base-path* backup)]
    (io/make-parents (io/as-file linkto))
    (link linkfrom linkto)))

(defn oldest-dir
  "Find the oldest (least most recently modified) directory in parent."
  [parent]
  (let [dirs (.listFiles (io/as-file parent))
        sorted (sort-by #(.lastModified %) dirs)]
    (first sorted)))

(defn delete-oldest-backup
  "Find the oldest backup hard linked in <base-path>/old and remove it."
  []
  (let [dir (.getName (oldest-dir (format "%s/old" *base-path*)))]
    (println "Oldest backup: " dir)
    (if (not (nil? dir))
      (sh "bash" "-c" (format "rm -rf %s/old/%s" *base-path* dir))
      (throw (ex-info "Could not remove any more old backups!" {:base-path *base-path*})))))

(defn check-free
  "Check the free space on the backup device. If it isn't at
  least twice the size of the last (current) backup, delete the oldest
  backup and try again. Repeat until enough space."
  [backup]
  (let [curr-used (get-backup-usage backup)
        est-need (* curr-used 2)]
    (println "The current back up is using " curr-used)
    (println "We're estimating that we need: " est-need)
    (loop [curr-free (.getFreeSpace (io/as-file *base-path*))]
      (println "Current free space on this device: " curr-free)
      (if (> curr-free est-need)
        true
        (do
          (println "Not enough disk space available, so removing the oldest backup.")
          (delete-oldest-backup)
          (recur (.getFreeSpace (io/as-file *base-path*))))))))

(defn rsync!
  "The actual rsync of files from somewhere, to somewhere else. This
  returns a map of the :exit code, standard output (:out) and any :err
  output."
  [backup-from backup-to]
  (let [rsync-command (into [] (conj *rsync-command* backup-from (format "%s/%s" *base-path* backup-to)))]
    (println "Starting rsync: " (string/join " " rsync-command))
    (apply sh rsync-command)))

(defn -main
  []
  (let [[backup-from backup-to] *command-line-args*]
    (when (or (not backup-from) (not backup-to))
      (println "Usage: <user@hostname.local:/home> <backup-name>")
      (System/exit 1))
    (println (format "Starting backup of %s to %s on %s" backup-from backup-to (.toString (LocalDateTime/now))))
    (check-month backup-to)
    (let [result (retry #(rsync! backup-from backup-to) #(= 0 (:exit %)) 3)]
      (if (= 0 (:exit result))
        (do
          (println (:out result))
          (make-hard-link backup-to (format "%s-%s" (.format (LocalDateTime/now) date-time-formatter) backup-to))
          (check-free backup-to)
          (println (format "Successfully finished backup of %s at %s" backup-from (.toString (LocalDateTime/now)))))
        (do
          (println (format "The backup of %s ended in an error: %s" backup-from (:err result)))
          (println "Not making a hard link or checking free space.")))
      (System/exit (:exit result)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))


(comment
  (import (java.time.format DateTimeFormatter))
  (import (java.time LocalDateTime))
  (require '[clojure.java.io :as io])
  (require '[clojure.java.shell :refer [sh]])
  (def *base-path* "/home/eamonn/backup-test")
  )
