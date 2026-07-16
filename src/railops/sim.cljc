(ns railops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean service through
  log -> schedule-coordination (escalate/approve/commit) ->
  passenger-safety-concern flag (always escalate/approve/commit) ->
  maintenance coordination (escalate/approve/commit), then shows
  HARD-hold scenarios: an unregistered route/schedule, an op outside
  the closed allowlist, and a proposal that tries to finalize a
  dispatch-safety override.

  Each governor check is exercised directly and independently below,
  following the SAME 'exercise the failure mode directly, never only
  via a happy-path actuation' discipline this fleet's siblings
  establish."
  (:require [langgraph.graph :as g]
            [railops.store :as store]
            [railops.governor :as governor]
            [railops.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :rail-ops-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== log-service-record service-1 (clean -- auto-commits) ==")
    (println (exec-op actor "t1" {:op :log-service-record :subject "service-1"
                                  :kind :ridership :data {:boardings 412}} operator))

    (println "== schedule-service-operation service-1 (escalates -- human approves) ==")
    (let [r (exec-op actor "t2" {:op :schedule-service-operation :subject "service-1"
                                 :change-summary "shift 08:10 departure to 08:15"} operator)]
      (println r)
      (println (approve! actor "t2")))

    (println "== flag-passenger-safety-concern service-1 (always escalates -- human approves) ==")
    (let [r (exec-op actor "t3" {:op :flag-passenger-safety-concern :subject "service-1"
                                 :concern-kind :signal-fault
                                 :description "platform 2 signal reported intermittent fault"} operator)]
      (println r)
      (println (approve! actor "t3")))

    (println "== coordinate-maintenance service-1 (escalates -- human approves) ==")
    (let [r (exec-op actor "t4" {:op :coordinate-maintenance :subject "service-1"
                                 :target :rolling-stock
                                 :coordination-note "request bogie inspection window"} operator)]
      (println r)
      (println (approve! actor "t4")))

    (println "== log-service-record service-2 (route/schedule NOT independently verified -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :log-service-record :subject "service-2"
                                  :kind :incident :data {}} operator))

    (println "== an op outside the closed allowlist -> HARD hold ==")
    (println (exec-op actor "t6" {:op :cancel-service-record :subject "service-1"} operator))

    (println "== a proposal that tries to finalize a dispatch-safety override -> HARD, permanent block ==")
    (println (governor/check {:op :finalize-dispatch-safety-override :subject "service-1"}
                             operator
                             {:effect :propose :confidence 0.99 :rationale "clear the train for departure"}
                             db))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft service-log records ==")
    (doseq [r (store/service-log-history db)] (println r))

    (println "== draft schedule-operation proposals ==")
    (doseq [r (store/schedule-proposal-history db)] (println r))

    (println "== passenger-safety-concern flags ==")
    (doseq [r (store/safety-concern-history db)] (println r))

    (println "== maintenance-coordination drafts ==")
    (doseq [r (store/maintenance-coordination-history db)] (println r))))
