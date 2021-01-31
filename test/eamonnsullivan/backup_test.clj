#!/usr/bin/env bb

(ns eamonnsullivan.backup-test
  {:author "Eamonn Sullivan"}
  (:import (java.time LocalDateTime))
  (:require [eamonnsullivan.backup :as sut]
            [clojure.test :refer :all]))

(deftest test-get-current-month
  (with-redefs [sut/date (fn [] (LocalDateTime/of 2020 1 8 10 15))]
    (testing "returns the year and month"
      (is (= "2020-01"
             (sut/get-current-month))))))
