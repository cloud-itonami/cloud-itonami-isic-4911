(ns railops.phase
  "Phase 0->3 staged rollout for the community interurban-passenger-
  rail operations-coordination actor.

    Phase 0  read-only            -- no writes, still governor-gated.
    Phase 1  assisted-log-safety  -- service-log entries AND
                                     passenger-safety-concern flags
                                     allowed, every write needs human
                                     approval. Safety-concern flagging
                                     is deliberately enabled from the
                                     EARLIEST assisted phase -- a
                                     rollout-phase gate must never be
                                     the reason a passenger-safety
                                     concern cannot be surfaced.
    Phase 2  assisted-coordinate  -- adds schedule/consist scheduling
                                     proposals and maintenance
                                     coordination, still approval.
    Phase 3  supervised auto      -- governor-clean, high-confidence
                                     `:log-service-record` (no
                                     capital/safety risk -- a data-
                                     logging record only) may auto-
                                     commit. `:schedule-service-
                                     operation`/`:flag-passenger-
                                     safety-concern`/`:coordinate-
                                     maintenance` NEVER auto-commit, at
                                     any phase.

  `:flag-passenger-safety-concern` is deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural
  fact, not a rollout milestone still to come. `railops.governor`'s
  own `high-stakes` gate enforces the same invariant independently --
  two layers, not one, agree on this. Like every prior sibling's phase
  3 `:auto` set, this domain has only ONE member
  (`:log-service-record`) -- no separate no-risk lifecycle distinct
  from the four-op allowlist itself."
  (:require [railops.governor :as governor]))

(def read-ops  #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: `:flag-passenger-safety-concern` is a member of
;; `write-ops` (governor-gated like any write, enabled from phase 1)
;; but is NEVER a member of any phase's `:auto` set below. Do not add
;; it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"
      :writes #{}
      :auto #{}}
   1 {:label "assisted-log-safety"
      :writes #{:log-service-record :flag-passenger-safety-concern}
      :auto #{}}
   2 {:label "assisted-coordinate"
      :writes #{:log-service-record :flag-passenger-safety-concern
                :schedule-service-operation :coordinate-maintenance}
      :auto #{}}
   3 {:label "supervised-auto"
      :writes write-ops
      :auto #{:log-service-record}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:flag-passenger-safety-concern` is never auto-eligible at any
    phase, so it always escalates once the governor clears it (or
    holds if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Rail Safety Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
