#!/usr/bin/env bb

(ns eamonnsullivan.backup
  {:author "Eamonn Sullivan"}
  (:import (java.time.format DateTimeFormatter)
           (java.time LocalDateTime))
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]))

(def base-path "/media/backup")
(def date (LocalDateTime/now))
(def month-formatter (DateTimeFormatter/ofPattern "yyyy-MM"))
(def now-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-MM"))

(defn get-current-month []
  (.format date month-formatter))

(defn get-now-as-string []
  (.format (LocalDateTime/now) now-formatter))

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
  "Create a \"hard\" link from path to target."
  [existing-file new-file]
  (sh "cp" "-al" existing-file new-file))

(defn check-month
  "Check whether we've started a new month. If we have, create a new
  backup from scratch."
  [backup]
  (let [now (get-current-month)
        check (io/as-file (format "%s/checkMonth.txt" base-path))
        last (if (.exists check) (slurp check) "")]
    (when (not= now last)
      (println "Staring a new monthly backup set...")
      (let [backupdir (io/as-file (format "%s/%s/." base-path backup))]
        (when (.exists backupdir)
          (delete-files-recursively (io/as-file (format "%s/%s" base-path backup))))
        (io/make-parents backupdir))
      (spit (format "%s/checkMonth.txt" base-path) now))))

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

(defn check-free [backup]
  "Check the free space on the back up filesystem. If it isn't at
  least twice the size of the last (current) backup, delete the oldest
  backup and try again. Repeat until enough space."
  (let [curr-used (get-backup-usage backup)
        est-need (* curr-used 2)]
    (loop [curr-free (.getFreeSpace (io/as-file base-path))]
      (if (> curr-free est-need)
        true
        (do
          (println "Not enough disk space available, so removing the oldest backups.")
          (delete-files-recursively (get-oldest-backup-dir))
          (recur (.getFreeSpace (io/as-file base-path))))))))

(println (get-current-month))

(comment
  (import (java.time.format DateTimeFormatter))
  (import (java.time LocalDateTime))
  (require '[clojure.java.io :as io])
  (require '[clojure.java.shell :refer [sh]])
  (def base-path ".")
  )
