(ns railops.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:flag-passenger-safety-concern` must NEVER be a member
  of any phase's `:auto` set -- flagging a passenger-safety concern
  always needs a human's eyes."
  (:require [clojure.test :refer [deftest is testing]]
            [railops.phase :as phase]
            [railops.governor :as governor]))

(deftest flag-passenger-safety-concern-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a passenger-safety-concern flag"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-passenger-safety-concern))
          (str "phase " n " must not auto-commit :flag-passenger-safety-concern")))))

(deftest schedule-and-maintenance-never-auto-at-any-phase
  (testing "structural invariant: schedule-service-operation/coordinate-maintenance are never auto-eligible either -- only the plain data-logging op is"
    (doseq [[_n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :schedule-service-operation)))
      (is (not (contains? auto :coordinate-maintenance))))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest flag-passenger-safety-concern-writable-from-the-earliest-assisted-phase
  (testing "a rollout-phase gate must never be the reason a passenger-safety concern cannot be surfaced -- writable from phase 1 onward"
    (is (contains? (:writes (get phase/phases 1)) :flag-passenger-safety-concern))
    (is (contains? (:writes (get phase/phases 2)) :flag-passenger-safety-concern))
    (is (contains? (:writes (get phase/phases 3)) :flag-passenger-safety-concern))))

(deftest phase-3-auto-commits-only-no-risk-op
  (testing ":log-service-record carries no direct capital/safety risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-service-record} (:auto (get phase/phases 3))))))

(deftest phase-3-writes-the-full-closed-allowlist
  (is (= governor/allowed-ops (:writes (get phase/phases 3)))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-service-record} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-service-operation} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :coordinate-maintenance} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-service-record} :commit))))
  (is (= :phase-disabled (:reason (phase/gate 0 {:op :log-service-record} :commit)))))
