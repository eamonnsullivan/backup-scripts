#!/usr/bin/env bb

(ns eamonnsullivan.backup-test
  {:author "Eamonn Sullivan"}
  (:import (java.time LocalDateTime))
  (:require [eamonnsullivan.backup :as sut]
            [clojure.test :refer :all]))

(deftest test-new-month?
  (with-redefs [sut/get-current-month (fn [] "2020-01")]
    (testing "compares the year and month"
      (is (= false
             (sut/new-month? "2020-01"))))))
