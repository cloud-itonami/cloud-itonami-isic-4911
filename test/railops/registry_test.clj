(ns railops.registry-test
  (:require [clojure.test :refer [deftest is]]
            [railops.registry :as r]))

;; ----------------------------- register-service-log -----------------------------

(deftest service-log-is-a-draft-not-a-real-record
  (let [result (r/register-service-log "service-1" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest service-log-assigns-log-number
  (let [result (r/register-service-log "service-1" 7 {:kind :ridership :data {:boardings 100}})]
    (is (= (get result "log_number") "LOG-000007"))
    (is (= (get-in result ["record" "service_id"]) "service-1"))
    (is (= (get-in result ["record" "kind"]) "service-log-draft"))
    (is (= (get-in result ["record" "log_kind"]) "ridership"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest service-log-validation-rules
  (is (thrown? Exception (r/register-service-log "" 0)))
  (is (thrown? Exception (r/register-service-log "service-1" -1))))

;; ----------------------------- register-schedule-proposal -----------------------------

(deftest schedule-proposal-is-a-draft-never-a-real-dispatch
  (let [result (r/register-schedule-proposal "service-1" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["record" "kind"]) "schedule-operation-proposal-draft"))))

(deftest schedule-proposal-assigns-proposal-number
  (let [result (r/register-schedule-proposal "service-1" 7 {:change-summary "shift 08:10 to 08:15"})]
    (is (= (get result "proposal_number") "SCH-000007"))
    (is (= (get-in result ["record" "change_summary"]) "shift 08:10 to 08:15"))))

;; ----------------------------- register-safety-concern-flag -----------------------------

(deftest safety-concern-flag-always-requires-human-review-and-is-advisory-only
  (let [result (r/register-safety-concern-flag "service-1" 0 {:concern-kind :signal-fault
                                                               :description "platform 2 signal fault"})]
    (is (= (get-in result ["record" "kind"]) "passenger-safety-concern-flag"))
    (is (true? (get-in result ["record" "requires_human_review"])))
    (is (true? (get-in result ["record" "advisory_only"])))
    (is (= (get-in result ["record" "concern_kind"]) "signal-fault"))
    (is (= (get result "concern_number") "SFC-000000"))))

;; ----------------------------- register-maintenance-coordination -----------------------------

(deftest maintenance-coordination-is-a-draft-never-a-release
  (let [result (r/register-maintenance-coordination "service-1" 0 {:target :track
                                                                    :coordination-note "inspect track km 4-9"})]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["record" "kind"]) "maintenance-coordination-draft"))
    (is (= (get-in result ["record" "target"]) "track"))
    (is (= (get result "coordination_number") "MNT-000000"))))

;; ----------------------------- append is history is append-only -----------------------------

(deftest history-is-append-only
  (let [c1 (r/register-service-log "service-1" 0)
        hist (r/append [] c1)
        c2 (r/register-service-log "service-1" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "LOG-000000" (get-in hist2 [0 "record_id"])))
    (is (= "LOG-000001" (get-in hist2 [1 "record_id"])))))
