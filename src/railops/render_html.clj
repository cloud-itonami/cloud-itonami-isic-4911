(ns railops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had NO
  demo page and no generator. This namespace drives the REAL actor
  stack (`railops.operation` -> `railops.governor` -> `railops.phase`
  -> `railops.store`) and renders the resulting store + append-only
  audit ledger. Nothing on the page is hand-typed telemetry: every
  service field is a `railops.store` entity key, every decision row is
  a fact the real `:commit`/`:hold` nodes appended to the ledger, and
  every draft-record row is a real `railops.registry` record (real
  `LOG-`/`SCH-`/`SFC-`/`MNT-` record ids, produced by the commit
  path). The ONLY hand-written content is `action-gate-rows`, a static
  description of this actor's own fixed op/gate contract -- see the
  comment there.

  Subject provenance: the only service ids used below are `service-1`
  and `service-2`, which are exactly the two services seeded by
  `railops.store/demo-data` (via `store/seed-db`). No invented ids.
  This repo's own demo driver `railops.sim` (`clojure -M:dev:run`, run
  BEFORE writing this file) uses the same two seeded ids, so its
  scenario was safe to build on rather than replace; this namespace
  extends it to reach dispositions `sim` never puts on the ledger (an
  approver rejection, a rollout-phase-disabled hold, and the two HARD
  governor rules `sim` only checks out-of-band).

  Ledger fact types: `railops.operation`'s `:commit` node appends
  `:committed`, and its `:hold` node appends whichever of
  `:governor-hold` / `:approval-rejected` is last on the `:audit`
  channel. `:approval-granted` and `:approval-requested` NEVER reach
  the ledger -- they are in-memory audit-channel entries only -- so
  nothing below branches on them.

  Determinism: the mock advisor is deterministic, the store is a fresh
  seeded `MemStore`, and no timestamp or random id appears in the page
  content -- two consecutive runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [railops.store :as store]
            [railops.railopsllm :as railopsllm]
            [railops.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  "Phase 3 (supervised-auto) operations-coordination desk operator --
  the same context shape `railops.sim` injects."
  {:actor-id "op-1" :actor-role :rail-ops-coordinator :phase 3})

(def ^:private phase-1-operator
  "The SAME operator at rollout phase 1 (assisted-log-safety), used to
  exercise `railops.phase/gate`'s `:phase-disabled` hold on an op that
  phase 1 does not yet enable."
  (assoc operator :phase 1))

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn- drift-advisor
  "A deliberately DRIFTING advisor -- the injected `:advisor` seam
  `railops.operation/build` documents, used exactly as this repo's own
  `governor_contract_test/end-to-end-dispatch-safety-override-attempt-is-held`
  uses it. It models a compromised / prompt-injected Rail-Operations-LLM.
  It is an INPUT to the real actor; the hold it produces is still the
  real governor's verdict, on a real seeded service."
  [proposal]
  (reify railopsllm/Advisor
    (-advise [_ _st _req] proposal)))

(defn run-demo!
  "Runs a fresh seeded store (`store/seed-db` -- services `service-1`
  and `service-2`, nothing else) through a scenario reaching every
  disposition this actor can put on the ledger.

  `service-1` (route/timetable slot INDEPENDENTLY registered by the
  operator's safety-management system -- `:route-schedule-registered?
  true` in the seed):

    - a full clean lifecycle across all four allowlisted ops --
      `:log-service-record` auto-commits at phase 3 (the only op in
      phase 3's `:auto` set), then `:schedule-service-operation`,
      `:flag-passenger-safety-concern` and `:coordinate-maintenance`
      each escalate to a human and are approved and committed
      (`:flag-passenger-safety-concern` escalates for TWO independent
      reasons -- the governor's `high-stakes` gate and its absence
      from every phase's `:auto` set);
    - a SECOND `:schedule-service-operation` where the human approver
      REJECTS -> `:approval-rejected` (basis `:approver-rejected`);
    - `:coordinate-maintenance` replayed at rollout phase 1, which
      does not yet enable that op -> HOLD with phase reason
      `:phase-disabled` (governor clean, rollout gate refuses);
    - `:cancel-service-record`, outside the closed four-op allowlist
      -> HARD `:op-not-allowlisted`;
    - `:finalize-dispatch-safety-override` -> HARD, two rules at once
      (`:op-not-allowlisted` AND `:dispatch-safety-override-blocked`);
    - an allowlisted `:schedule-service-operation` whose DRIFTING
      advisor rationale names a forbidden finalization action -> HARD
      `:dispatch-safety-override-blocked` on its own, proving the
      permanent block survives an op that is otherwise perfectly
      legal;
    - an allowlisted `:log-service-record` whose DRIFTING advisor
      returns `:effect :actuate` instead of `:propose` -> HARD
      `:effect-not-propose` on its own.

  `service-2` (route/timetable slot NOT independently registered --
  `:route-schedule-registered? false` in the seed):

    - `:log-service-record` -> HARD `:route-schedule-not-verified`.
      No op anywhere in this actor's allowlist can ever set that flag
      (see `railops.store`'s docstring), so this service cannot be
      coordinated around at all until an external system registers it.

  All four HARD governor rules and both non-governor hold paths are
  therefore real output. Returns the store."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        override-actor
        (op/build db {:advisor (drift-advisor
                                {:summary "shift departure"
                                 :rationale "will override the signal interlock to clear the train for departure"
                                 :cites [] :effect :propose
                                 :value {:change-summary "advance 08:10 departure"}
                                 :stake nil :confidence 0.99})})
        actuate-actor
        (op/build db {:advisor (drift-advisor
                                {:summary "log ridership"
                                 :rationale "正規化のみ"
                                 :cites ["service-1"] :effect :actuate
                                 :value {:kind :ridership :data {:boardings 118}}
                                 :stake nil :confidence 0.95})})]

    ;; --- service-1: clean lifecycle across all four allowlisted ops ---
    (exec! actor "s1-log" {:op :log-service-record :subject "service-1"
                           :kind :ridership :data {:boardings 412}} operator)

    (exec! actor "s1-schedule" {:op :schedule-service-operation :subject "service-1"
                                :change-summary "shift 08:10 departure to 08:15"
                                :proposed-consist "consist-7"} operator)
    (approve! actor "s1-schedule")

    (exec! actor "s1-concern" {:op :flag-passenger-safety-concern :subject "service-1"
                               :concern-kind :signal-fault
                               :description "platform 2 signal reported intermittent fault"} operator)
    (approve! actor "s1-concern")

    (exec! actor "s1-maintenance" {:op :coordinate-maintenance :subject "service-1"
                                   :target :rolling-stock
                                   :coordination-note "request bogie inspection window"} operator)
    (approve! actor "s1-maintenance")

    ;; --- service-1: the human approver refuses ---
    (exec! actor "s1-schedule-rejected" {:op :schedule-service-operation :subject "service-1"
                                         :change-summary "add unscheduled 23:40 relief run"} operator)
    (reject! actor "s1-schedule-rejected")

    ;; --- service-1: rollout phase 1 does not yet enable maintenance coordination ---
    (exec! actor "s1-maintenance-phase1" {:op :coordinate-maintenance :subject "service-1"
                                          :target :track
                                          :coordination-note "request rail-grinding window"}
           phase-1-operator)

    ;; --- service-1: HARD governor rules ---
    (exec! actor "s1-cancel" {:op :cancel-service-record :subject "service-1"} operator)

    (exec! actor "s1-finalize" {:op :finalize-dispatch-safety-override :subject "service-1"} operator)

    (exec! override-actor "s1-drift-override" {:op :schedule-service-operation :subject "service-1"
                                               :change-summary "advance 08:10 departure"} operator)

    (exec! actuate-actor "s1-drift-actuate" {:op :log-service-record :subject "service-1"
                                             :kind :ridership :data {:boardings 118}} operator)

    ;; --- service-2: route/timetable slot never independently registered ---
    (exec! actor "s2-log" {:op :log-service-record :subject "service-2"
                           :kind :incident :data {}} operator)
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- facts-for [ledger service-id]
  (filter #(= (:subject %) service-id) ledger))

(defn- verified-cell [{:keys [route-schedule-registered?]}]
  (if route-schedule-registered?
    "<span class=\"ok\">registered (external SMS)</span>"
    "<span class=\"critical\">not registered</span>"))

(defn- status-cell
  "Last ledger decision for a service. Branches ONLY on the three fact
  types `railops.store` actually receives -- `:committed` from the
  actor's `:commit` node and `:governor-hold` / `:approval-rejected`
  from its `:hold` node."
  [ledger service-id]
  (let [f (last (facts-for ledger service-id))
        rule (-> f :violations first :rule)]
    (case (:t f)
      :committed          "<span class=\"ok\">committed</span>"
      :approval-rejected  "<span class=\"warn\">approver rejected</span>"
      :governor-hold      (if rule
                            (str "<span class=\"critical\">HARD hold &middot; " (esc (name rule)) "</span>")
                            (str "<span class=\"warn\">rollout hold &middot; "
                                 (esc (name (:phase-reason f :phase-gate))) "</span>"))
      "<span class=\"muted\">no activity</span>")))

(defn- service-row [ledger {:keys [id route consist operator] :as svc}]
  (let [fs (facts-for ledger id)]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td><td>%s</td></tr>"
            (esc id) (esc route) (esc consist) (esc operator)
            (verified-cell svc)
            (count (filter #(= :committed (:t %)) fs))
            (count (filter #(#{:governor-hold :approval-rejected} (:t %)) fs))
            (status-cell ledger id))))

(defn- basis-text [{:keys [basis phase-reason]}]
  (let [b (some->> (seq basis) (map name) (str/join ", "))]
    (cond
      (and b phase-reason) (str b " (phase: " (name phase-reason) ")")
      b b
      phase-reason (str "phase: " (name phase-reason))
      :else "")))

(defn- fact-cell
  "A `:governor-hold` fact carries the governor's own violations when a
  HARD rule fired, and carries none when the governor was clean and
  the ROLLOUT PHASE gate refused instead -- two different things that
  share one fact type, so they are labelled apart here rather than
  both being called a HARD hold."
  [{:keys [t violations]}]
  (case t
    :committed         "<span class=\"ok\">committed</span>"
    :approval-rejected "<span class=\"warn\">approval-rejected</span>"
    :governor-hold     (if (seq violations)
                         "<span class=\"critical\">governor-hold · HARD hold</span>"
                         "<span class=\"warn\">rollout-phase hold</span>")
    (str "<span class=\"muted\">" (esc (name (or t :unknown))) "</span>")))

(defn- ledger-row [{:keys [op subject] :as f}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
          (fact-cell f) (esc (name (or op :n-a))) (esc subject)
          (esc (basis-text f))))

(defn- record-row
  "One real `railops.registry` draft record, straight out of the
  store's append-only history. Keys are the record's own string keys;
  the tail column is every remaining key sorted, so nothing is
  selected or renamed by hand."
  [r]
  (let [skip #{"record_id" "kind" "service_id" "immutable"}
        detail (->> (sort (keys r))
                    (remove skip)
                    (map #(str % "=" (pr-str (get r %))))
                    (str/join " · "))]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td></tr>"
            (esc (get r "record_id")) (esc (get r "kind")) (esc (get r "service_id"))
            (esc detail))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own CLOSED op contract
  ;; (README `Core Contract`, `railops.governor/allowed-ops`,
  ;; `railops.phase/phases`) -- documentation of fixed code behavior,
  ;; not runtime telemetry, so it is legitimately hand-described
  ;; rather than derived from a live run.
  ["        <tr><td><code>:log-service-record</code></td><td><span class=\"ok\">phase-3 auto-commit when governor-clean &middot; the ONLY op in any phase's auto set</span></td></tr>"
   "        <tr><td><code>:schedule-service-operation</code></td><td><span class=\"warn\">human approval at every phase &middot; a coordination proposal, never a dispatch</span></td></tr>"
   "        <tr><td><code>:flag-passenger-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; high-stakes in the governor AND absent from every phase's auto set (two independent layers)</span></td></tr>"
   "        <tr><td><code>:coordinate-maintenance</code></td><td><span class=\"warn\">human approval &middot; enabled from phase 2 &middot; never releases or executes maintenance</span></td></tr>"
   "        <tr><td><code>anything else</code></td><td><span class=\"critical\">HARD hold &middot; :op-not-allowlisted &middot; an unknown op is never given the benefit of the doubt</span></td></tr>"])

(def ^:private hold-rule-rows
  ;; Static description of `railops.governor`'s four HARD checks, in
  ;; the priority order the namespace itself documents. Again: a
  ;; description of fixed code, not of this run -- the ledger section
  ;; below is where the rules that actually fired are shown.
  ["        <tr><td><code>:op-not-allowlisted</code></td><td>the proposal's op is outside the closed four-op allowlist</td></tr>"
   "        <tr><td><code>:dispatch-safety-override-blocked</code></td><td>the op — or the advisor's own rationale text — names a forbidden finalization action (clearing a train for departure, overriding a signal interlock). Can never be satisfied by any proposal, at any phase, by any approver</td></tr>"
   "        <tr><td><code>:effect-not-propose</code></td><td>the proposal's effect is not <code>:propose</code> — this actor never emits an effect that reads as a direct mutation of a real dispatch/signalling system</td></tr>"
   "        <tr><td><code>:route-schedule-not-verified</code></td><td>the service's route/timetable slot has not been independently registered by an external safety-management system. No op in this actor's allowlist can set that flag</td></tr>"])

(defn render
  "Renders the operator-console document from a store `db` that has
  already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        services (store/all-services db)
        records (concat (store/service-log-history db)
                        (store/schedule-proposal-history db)
                        (store/safety-concern-history db)
                        (store/maintenance-coordination-history db))]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-4911 &middot; interurban passenger rail — Operator Console</title>\n"
     "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Passenger rail transport, interurban (ISIC 4911) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · this actor coordinates around services, it never dispatches one</span>\n"
     "</header>\n"
     "<main class=\"container\">\n"

     "  <section class=\"card\">\n"
     "    <h2>Scheduled services</h2>\n"
     "    <p class=\"muted\">Build-time snapshot generated from <code>railops.store</code> by <code>railops.render-html</code> (<code>clojure -M:dev:render-html</code>). Every row is a real store entity; the decision counts and status come from the append-only ledger below.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Service</th><th>Route</th><th>Consist</th><th>Operator</th><th>Route/timetable slot</th><th>Committed</th><th>Held</th><th>Last decision</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial service-row ledger) services)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Rail Safety Governor + rollout phase)</h2>\n"
     "    <p class=\"muted\">Two independent layers. The governor censors the Rail-Operations-LLM's proposal; the rollout phase gate can only add caution, never remove it.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD hold rules</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by a human approver — the run never reaches the approval node at all.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>Meaning</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" hold-rule-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision facts — one per operation, written by the actor's <code>:commit</code> and <code>:hold</code> nodes. <code>:committed</code> basis lists the facts the advisor cited; hold basis lists the governor rules that fired.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Service</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Draft records committed to the SSoT</h2>\n"
     "    <p class=\"muted\">Every record <code>railops.registry</code> built on a committed op. All are unsigned drafts — a rail operator's own certified safety-management system remains a separate system of record this actor never writes to.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Record id</th><th>Kind</th><th>Service</th><th>Fields</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map record-row records)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer class=\"container footer\">cloud-itonami-isic-4911 · AGPL-3.0-or-later · regenerated from the actor stack, not hand-written</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/all-services db)) "services,"
             (+ (count (store/service-log-history db))
                (count (store/schedule-proposal-history db))
                (count (store/safety-concern-history db))
                (count (store/maintenance-coordination-history db)))
             "draft records )")))
