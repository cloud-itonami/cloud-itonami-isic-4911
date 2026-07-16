(ns railops.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the
  sibling actor, and `cloud-itonami-isic-4920`'s own
  `freightops.store-contract-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [railops.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Tokyo-Nagano Express" (:route (store/service s "service-1"))))
      (is (true? (:route-schedule-registered? (store/service s "service-1"))))
      (is (false? (:route-schedule-registered? (store/service s "service-2"))))
      (is (= ["service-1" "service-2"] (mapv :id (store/all-services s))))
      (is (= [] (store/ledger s)))
      (is (= [] (store/service-log-history s)))
      (is (= [] (store/schedule-proposal-history s)))
      (is (= [] (store/safety-concern-history s)))
      (is (= [] (store/maintenance-coordination-history s)))
      (is (zero? (store/next-sequence s :log)))
      (is (zero? (store/next-sequence s :schedule)))
      (is (zero? (store/next-sequence s :concern)))
      (is (zero? (store/next-sequence s :maintenance))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "log-service-record drafts a record and advances the log sequence"
        (store/commit-record! s {:op :log-service-record :path ["service-1"]
                                 :payload {:kind :ridership :data {:boardings 100}}})
        (is (= "LOG-000000" (get (first (store/service-log-history s)) "record_id")))
        (is (= "service-log-draft" (get (first (store/service-log-history s)) "kind")))
        (is (= 1 (count (store/service-log-history s))))
        (is (= 1 (store/next-sequence s :log))))
      (testing "schedule-service-operation drafts a proposal and advances the schedule sequence"
        (store/commit-record! s {:op :schedule-service-operation :path ["service-1"]
                                 :payload {:change-summary "shift departure"}})
        (is (= "SCH-000000" (get (first (store/schedule-proposal-history s)) "record_id")))
        (is (= 1 (count (store/schedule-proposal-history s))))
        (is (= 1 (store/next-sequence s :schedule))))
      (testing "flag-passenger-safety-concern drafts a concern flag and advances the concern sequence"
        (store/commit-record! s {:op :flag-passenger-safety-concern :path ["service-1"]
                                 :payload {:concern-kind :signal-fault :description "d"}})
        (is (= "SFC-000000" (get (first (store/safety-concern-history s)) "record_id")))
        (is (true? (get (first (store/safety-concern-history s)) "requires_human_review")))
        (is (= 1 (count (store/safety-concern-history s))))
        (is (= 1 (store/next-sequence s :concern))))
      (testing "coordinate-maintenance drafts a coordination request and advances the maintenance sequence"
        (store/commit-record! s {:op :coordinate-maintenance :path ["service-1"]
                                 :payload {:target :rolling-stock :coordination-note "n"}})
        (is (= "MNT-000000" (get (first (store/maintenance-coordination-history s)) "record_id")))
        (is (= 1 (count (store/maintenance-coordination-history s))))
        (is (= 1 (store/next-sequence s :maintenance))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/service s "nope")))
    (is (= [] (store/all-services s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/service-log-history s)))
    (is (zero? (store/next-sequence s :log)))
    (store/with-services s {"x" {:id "x" :route "r" :consist "c" :operator "o"
                                 :route-schedule-registered? true}})
    (is (= "r" (:route (store/service s "x"))))
    (is (true? (:route-schedule-registered? (store/service s "x"))))))
