#!/usr/bin/env bb

(ns eamonnsullivan.backup-test
  {:author "Eamonn Sullivan"}
  (:import (java.time LocalDateTime))
  (:require [eamonnsullivan.backup :as sut]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :as t :refer [deftest testing is]]))

(def cwd (fs/real-path "."))

(defn temp-dir []
  (-> (fs/create-temp-dir)
      (fs/delete-on-exit)))

(deftest test-new-month?
  (with-redefs [sut/get-current-month (fn [] "2020-01")]
    (testing "compares the year and month"
      (is (= false
             (sut/new-month? "2020-01"))))))

(deftest retry-test
  (testing "should retry the specified number of times"
    (is (= 4 ;; first, then three retries.
           (let [attempts-count (volatile! 0)
                 _ (sut/retry #(vswap! attempts-count inc) :continue 3 100)]
             @attempts-count))))
  (testing "should stop retrying when predicate returns true"
    (is (= 3
           (let [attempts-count (volatile! 0)
                 _ (sut/retry #(vswap! attempts-count inc) (fn [_] (< 2 @attempts-count)) 5 100)]
             @attempts-count)))))

(deftest test-link
  (let [tmp-dir (temp-dir)
        dir (str (fs/create-dir (fs/path tmp-dir "foo")))
        _ (spit (fs/file tmp-dir "foo/" "dudette.txt") "some content")
        linkto (str tmp-dir "/bar")
        _ (sut/link dir linkto)]
    (is (.exists (fs/file tmp-dir "foo")))
    (is (.exists (fs/file tmp-dir "bar")))
    (is (.exists (fs/file tmp-dir "bar/" "dudette.txt")))
    (is (= (slurp (fs/file tmp-dir "foo/" "dudette.txt"))
           (slurp (fs/file tmp-dir "bar/" "dudette.txt"))))
    (is (= 2 (fs/get-attribute (fs/file tmp-dir "bar/" "dudette.txt")
                               "unix:nlink")))))

(deftest test-delete-backup
  (let [tmp-dir (temp-dir)
        _ (fs/create-dir (fs/path tmp-dir "foo"))
        _ (fs/create-dir (fs/path tmp-dir "bar"))
        _ (spit (fs/file tmp-dir "bar/" "something.txt") "with content")
        _ (spit (fs/file tmp-dir "foo/" "dude.txt") "some content")]
    (binding [sut/*base-path* tmp-dir]
      (sut/delete-backup "foo")
      (is (.exists (fs/file tmp-dir "bar")))
      (is (not (.exists (fs/file tmp-dir "foo")))))))

(deftest test-get-backup-usage
  (let [tmp-dir (temp-dir)
        _ (fs/create-dir (fs/path tmp-dir "foo"))
        _ (spit (fs/file tmp-dir "foo/" "content.txt") "123\n")]
    (binding [sut/*base-path* tmp-dir]
      (is (= 4100
             (sut/get-backup-usage "foo"))))))

(deftest test-check-month
  (let [tmp-dir (temp-dir)
        _ (fs/create-dir (fs/path tmp-dir "foo"))
        _ (spit (fs/file tmp-dir "foo/" "content.txt") "123")
        _ (spit (fs/file tmp-dir "checkMonth_foo.txt") "456")]
    (binding [sut/*base-path* tmp-dir]
      (with-redefs [sut/new-month? (fn [_] false)]
        (testing "does not delete current backup if the month hasn't changed"
          (sut/check-month "foo")
          (is (.exists (fs/file tmp-dir "foo")))))
      (with-redefs [sut/new-month? (fn [_] true)
                    sut/get-current-month (fn [] "789")]
        (testing "removes current backup and writes new checkMonth file when month changes"
          (sut/check-month "foo")
          (is (not (.exists (fs/file tmp-dir "foo/" "content.txt"))))
          (is (= "789"
                 (slurp (fs/file tmp-dir "checkMonth_foo.txt")))))))))

(deftest test-make-hard-link
  (let [tmp-dir (temp-dir)
        _ (fs/create-dir (fs/path tmp-dir "foo"))
        _ (spit (fs/file tmp-dir "foo/" "content.txt") "123")]
    (binding [sut/*base-path* tmp-dir]
      (testing "makes a hard-link under /old of the current backup"
        (sut/make-hard-link "foo" "foo-123")
        (is (.exists (fs/file tmp-dir "old/foo-123/" "content.txt")))))))

(deftest test-oldest-dir
  ;; My slower Linux system seems to need the sleeps...
  (let [tmp-dir (temp-dir)
        _ (fs/create-dir (fs/path tmp-dir "one"))
        _ (Thread/sleep 10)
        _ (fs/create-dir (fs/path tmp-dir "two"))
        _ (Thread/sleep 10)
        _ (fs/create-dir (fs/path tmp-dir "three"))]
    (testing "should find oldest directory by last modified"
      (is (= (fs/path tmp-dir "one")
             (fs/path (sut/oldest-dir (str tmp-dir))))))))

(deftest test-delete-oldest-backup
  (let [tmp-dir (temp-dir)
        _ (fs/create-dir (fs/path tmp-dir "old"))
        _ (fs/create-dir (fs/path tmp-dir "old/" "one"))
        _ (Thread/sleep 10)
        _ (fs/create-dir (fs/path tmp-dir "old/" "two"))
        _ (Thread/sleep 10)
        _ (fs/create-dir (fs/path tmp-dir "old/" "three"))]
    (testing "should remove the old directory under /old"
      (binding [sut/*base-path* tmp-dir]
        (sut/delete-oldest-backup)
        (is (not (.exists (fs/file tmp-dir "old/" "one"))))
        (is (.exists (fs/file tmp-dir "old/" "two")))
        (is (.exists (fs/file tmp-dir "old/" "three")))))))
