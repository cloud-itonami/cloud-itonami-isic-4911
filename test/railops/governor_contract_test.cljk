(ns railops.governor-contract-test
  "The Rail Safety Governor contract as executable tests -- this
  vertical's own Decision Rule (README `Robotics premise`, blueprint's
  `:itonami.blueprint/governor :rail-safety-governor`), implemented
  faithfully. The single invariant under test:

    Rail-Operations-LLM never commits a coordination record the Rail
    Safety Governor would reject; `:log-service-record` (no
    capital/safety risk) MAY auto-commit when clean;
    `:schedule-service-operation`/`:flag-passenger-safety-concern`/
    `:coordinate-maintenance` NEVER auto-commit at any phase; a
    passenger-safety concern ALWAYS escalates to a human; this actor
    can NEVER finalize a dispatch/signal-safety override, at any
    confidence, at any phase, from any approver; and every decision
    (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [railops.store :as store]
            [railops.governor :as governor]
            [railops.railopsllm :as railopsllm]
            [railops.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :rail-ops-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

;; ----------------------------- happy path -----------------------------

(deftest clean-log-service-record-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-service-record :subject "service-1"
                   :kind :ridership :data {:boardings 412}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 1 (count (store/service-log-history db))))
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-service-operation-always-needs-approval
  (testing "schedule-service-operation is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :schedule-service-operation :subject "service-1"
                                   :change-summary "shift 08:10 to 08:15"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/schedule-proposal-history db))))))))

(deftest coordinate-maintenance-always-needs-approval
  (testing "coordinate-maintenance is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t3" {:op :coordinate-maintenance :subject "service-1"
                                   :target :rolling-stock
                                   :coordination-note "bogie inspection window"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t3")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/maintenance-coordination-history db))))))))

(deftest flag-passenger-safety-concern-always-escalates-then-human-decides
  (testing "a clean, fully-verified service still ALWAYS interrupts for human approval -- :safety-concern/flag is never auto"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t4" {:op :flag-passenger-safety-concern :subject "service-1"
                                  :concern-kind :signal-fault
                                  :description "platform 2 signal reported intermittent fault"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, concern flag drafted"
        (let [r2 (approve! actor "t4")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 1 (count (store/safety-concern-history db)))))))))

;; ----------------------------- HARD holds -----------------------------

(deftest route-schedule-not-verified-is-held-and-unoverridable
  (testing "a service whose route/schedule has no independent registration -> HOLD, and never reaches request-approval, on ANY op"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :log-service-record :subject "service-2"
                                   :kind :incident :data {}} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:route-schedule-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/service-log-history db))))))

(deftest route-schedule-not-verified-blocks-every-op
  (testing "the route/schedule precondition applies to ALL FOUR ops, not just log-service-record"
    (doseq [request [{:op :schedule-service-operation :subject "service-2" :change-summary "x"}
                     {:op :flag-passenger-safety-concern :subject "service-2" :concern-kind :incident :description "d"}
                     {:op :coordinate-maintenance :subject "service-2" :target :track :coordination-note "n"}]]
      (let [[db actor] (fresh)
            res (exec-op actor (str "t-" (:op request)) request operator)]
        (is (= :hold (get-in res [:state :disposition])) (str (:op request) " must hold on an unverified service"))
        (is (some #{:route-schedule-not-verified} (-> (store/ledger db) last :basis)))))))

(deftest op-not-allowlisted-is-held-and-unoverridable
  (testing "an op outside the closed four-op allowlist -> HOLD, never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :cancel-service-record :subject "service-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:op-not-allowlisted} (-> (store/ledger db) last :basis))))))

(deftest dispatch-safety-override-op-is-a-permanent-hard-block
  (testing "a proposal whose op IS a forbidden finalize op -> HOLD, at any confidence, at any phase"
    (let [[db _actor] (fresh)
          request {:op :override-signal-interlock :subject "service-1"}
          proposal {:effect :propose :confidence 0.99 :rationale "routine" :summary "" :cites []}
          verdict (governor/check request operator proposal db)]
      (is (true? (:hard? verdict)))
      (is (some #{:dispatch-safety-override-blocked} (map :rule (:violations verdict))))
      (is (false? (:ok? verdict))))))

(deftest dispatch-safety-override-phrase-is-a-permanent-hard-block
  (testing "an otherwise-allowlisted op whose OWN rationale text names a forbidden finalization ACTION -> HOLD -- catches a compromised/prompt-injected advisor output on an allowed op"
    (let [[db _actor] (fresh)
          request {:op :schedule-service-operation :subject "service-1"}
          proposal {:effect :propose :confidence 0.99
                    :rationale "plan to override the signal interlock and depart"
                    :summary "" :cites []}
          verdict (governor/check request operator proposal db)]
      (is (true? (:hard? verdict)))
      (is (some #{:dispatch-safety-override-blocked} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-a-hard-hold
  (testing "any proposal whose :effect is not :propose -> HOLD, regardless of confidence or op"
    (let [[db _actor] (fresh)
          request {:op :log-service-record :subject "service-1"}
          proposal {:effect :log/execute :confidence 0.99 :rationale "" :summary "" :cites []}
          verdict (governor/check request operator proposal db)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

;; ----------------------------- self-tripping-bug regression -----------------------------

(deftest mock-advisor-defaults-never-self-trip-scope-exclusion
  (testing "the DEFAULT mock-advisor proposal for every legitimate, allowed request never trips the scope-exclusion (dispatch-safety-override) check -- the fleet-wide bare-noun self-tripping bug class this governor is written to avoid from day one"
    (let [db (store/seed-db)
          requests [{:op :log-service-record :subject "service-1" :kind :ridership :data {:boardings 100}}
                    {:op :schedule-service-operation :subject "service-1" :change-summary "shift departure"}
                    {:op :flag-passenger-safety-concern :subject "service-1"
                     :concern-kind :signal-fault :description "signal fault at platform 2"}
                    {:op :coordinate-maintenance :subject "service-1" :target :track
                     :coordination-note "track inspection requested"}]]
      (doseq [request requests]
        (let [proposal (railopsllm/infer db request)
              verdict (governor/check request operator proposal db)]
          (is (not (some #{:dispatch-safety-override-blocked} (map :rule (:violations verdict))))
              (str (:op request) "'s own default mock-advisor proposal must never self-trip the scope-exclusion check"))
          (is (not (:hard? verdict))
              (str (:op request) "'s own default mock-advisor proposal on a clean, verified service must never HARD-hold")))))))

(deftest end-to-end-dispatch-safety-override-attempt-is-held
  (testing "even if the advisor layer is compromised and proposes finalizing a dispatch-safety override on an otherwise-allowed op, the FULL actor graph HOLDS -- never reaches request-approval"
    (let [db (store/seed-db)
          malicious-advisor (reify railopsllm/Advisor
                              (-advise [_ _st _req]
                                {:summary "shift departure"
                                 :rationale "will override the signal interlock to clear the train for departure"
                                 :cites [] :effect :propose
                                 :value {:change-summary "x"} :stake nil :confidence 0.99}))
          actor (op/build db {:advisor malicious-advisor})
          res (exec-op actor "mal" {:op :schedule-service-operation :subject "service-1"
                                    :change-summary "x"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:dispatch-safety-override-blocked} (-> (store/ledger db) last :basis))))))

;; ----------------------------- ledger bookkeeping -----------------------------

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-service-record :subject "service-1"
                          :kind :ridership :data {}} operator)
      (exec-op actor "b" {:op :log-service-record :subject "service-2"
                          :kind :ridership :data {}} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
