(ns railops.governor
  "Rail Safety Governor -- the independent compliance layer that earns
  the Rail-Operations-LLM the right to commit. The LLM has no notion
  of whether a service's own route/timetable slot has actually been
  independently verified/registered by the operator's safety-
  management system, whether an op is even inside this actor's own
  closed proposal-op allowlist, or when a proposal has drifted from
  'coordinate around a service' into 'finalize a real dispatch/signal-
  safety decision' -- so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD.

  `:itonami.blueprint/governor` is `:rail-safety-governor` (already
  published in this blueprint's own `blueprint.edn`, written before
  this actor existed -- this governor implements that published name
  faithfully).

  This actor is SCOPED to OPERATIONS COORDINATION, not direct dispatch/
  signal-safety authority or rolling-stock control (see README
  `Robotics premise`). Four checks, in priority order, ALL HARD
  violations -- a human approver CANNOT override them:

    1. Op not allowlisted           -- the proposal's `:op` is outside
                                        the closed four-op allowlist
                                        (`allowed-ops`). A genuinely
                                        unknown/unexpected op is never
                                        given the benefit of the
                                        doubt.
    2. Dispatch-safety-override
       blocked                      -- the proposal's `:op` is a
                                        forbidden finalize op
                                        (`forbidden-finalize-ops`), OR
                                        its own rationale/summary/cites
                                        text names one of the
                                        forbidden finalization ACTIONS
                                        (`scope-exclusion-phrases`) --
                                        a permanent, un-overridable
                                        block. This is the ONE check in
                                        this governor that can never be
                                        satisfied by ANY proposal, at
                                        ANY phase, by ANY approver --
                                        this actor structurally cannot
                                        finalize a passenger-safety-
                                        authority decision.

                                        IMPORTANT (grep-verified fleet
                                        bug class, fixed here from day
                                        one): the scope-exclusion terms
                                        below are phrased as the
                                        finalization/execution ACTION
                                        ('finalize the dispatch-safety
                                        override', not the bare noun
                                        'safety'/'dispatch'/'signal').
                                        A bare-noun term list would
                                        self-trip on this actor's OWN
                                        default mock-advisor rationale
                                        text for `:flag-passenger-
                                        safety-concern` (which
                                        legitimately says things like
                                        'signal fault reported' --
                                        containing 'signal' and
                                        'safety' as ordinary words, not
                                        as a finalize action). See
                                        `railops.railopsllm`'s own
                                        docstring and
                                        `governor-contract-test`'s
                                        `mock-advisor-defaults-never-
                                        self-trip-scope-exclusion` for
                                        the regression test.
    3. Effect not :propose          -- every proposal from this actor
                                        MUST carry `:effect :propose`
                                        -- this actor never emits an
                                        effect that would read as a
                                        direct mutation of a real
                                        dispatch/signalling/rolling-
                                        stock system. Evaluated
                                        UNCONDITIONALLY, for all four
                                        ops.
    4. Route/schedule not verified  -- the service's own
                                        `:route-schedule-registered?`
                                        must be true -- INDEPENDENTLY
                                        verified/registered by a system
                                        OUTSIDE this actor (see
                                        `railops.store`'s own
                                        docstring: no op/effect in this
                                        actor's allowlist can ever set
                                        this flag). Applies to ALL FOUR
                                        ops, not just actuation-shaped
                                        ones -- this actor never
                                        coordinates around a service
                                        whose own route/schedule record
                                        is unverified. Evaluated
                                        UNCONDITIONALLY.

  The confidence/actuation gate is SOFT: it asks a human to look, and
  the human may approve. `:flag-passenger-safety-concern` is a
  dedicated high-stakes stake (`high-stakes`) so it ALWAYS escalates
  to a human -- never auto-commits at any phase (`railops.phase`
  independently agrees: no phase's `:auto` set ever contains
  `:flag-passenger-safety-concern`). Two independent layers, not one."
  (:require [clojure.string :as str]
            [railops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The CLOSED proposal-op allowlist -- every op this actor may ever
  propose. Anything outside this set is a HARD, un-overridable HOLD
  (`op-not-allowlisted-violations`), never given the benefit of the
  doubt."
  #{:log-service-record :schedule-service-operation
    :flag-passenger-safety-concern :coordinate-maintenance})

(def forbidden-finalize-ops
  "Ops that would directly finalize a passenger-safety-authority
  decision -- NEVER in `allowed-ops`, and explicitly named here so a
  proposal carrying one of these is blocked by a distinct, legible
  rule (`:dispatch-safety-override-blocked`) rather than falling
  through to the generic `:op-not-allowlisted` rule."
  #{:finalize-dispatch-safety-override :clear-train-for-departure
    :override-signal-interlock :authorize-departure-despite-fault})

(def scope-exclusion-phrases
  "Forbidden finalization ACTION phrases -- multi-word verb+object
  phrases, NEVER bare nouns. A bare noun like 'safety'/'dispatch'/
  'signal'/'override' would match this actor's OWN legitimate default
  rationale text (e.g. `:flag-passenger-safety-concern`'s 'signal
  fault reported' or `:schedule-service-operation`'s 'coordination
  proposal, not a dispatch') and self-trip on the happy path -- the
  exact fleet-wide bug class this governor is written to avoid from
  day one. Matched case-insensitively as a substring of the
  proposal's rationale/summary/cites text."
  ["finalize the dispatch-safety override"
   "clear the train for departure"
   "override the signal interlock"
   "authorize departure despite fault"
   "bypass the dispatch-safety interlock"])

(def high-stakes
  "Stakes grave enough to always require a human, even when the
  governor is otherwise clean. Surfacing a passenger-safety concern
  always needs a human's eyes -- a one-member set (this domain has
  only one true actuation-adjacent stake; the other three ops are
  plain coordination records)."
  #{:safety-concern/flag})

;; ----------------------------- checks -----------------------------

(defn- op-not-allowlisted-violations
  [{:keys [op]} _proposal]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowlisted
      :detail (str op " は本アクターの閉じた提案op許可リストに含まれない")}]))

(defn- proposal-text-blob [proposal]
  (str/lower-case
   (str (:rationale proposal) " " (:summary proposal) " "
        (str/join " " (map str (:cites proposal))))))

(defn- dispatch-safety-override-violations
  "Permanent, un-overridable block -- see governor docstring `2.`."
  [{:keys [op]} proposal]
  (let [blob (proposal-text-blob proposal)]
    (when (or (contains? forbidden-finalize-ops op)
              (some #(str/includes? blob %) scope-exclusion-phrases))
      [{:rule :dispatch-safety-override-blocked
        :detail "信号/安全インターロックの最終化・列車発車の許可はこのアクターの権限外 -- 恒久的にブロック"}])))

(defn- effect-not-propose-violations
  [_request proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str "提案の:effectは:proposeのみ許可 (実際: " (pr-str (:effect proposal)) ")")}]))

(defn- route-schedule-not-verified-violations
  [{:keys [subject]} st]
  (let [svc (store/service st subject)]
    (when-not (true? (:route-schedule-registered? svc))
      [{:rule :route-schedule-not-verified
        :detail (str subject " は経路/時刻表の独立検証・登録記録が確認できない -- いかなる提案も進められない")}])))

(defn check
  "Censors a Rail-Operations-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (op-not-allowlisted-violations request proposal)
                           (dispatch-safety-override-violations request proposal)
                           (effect-not-propose-violations request proposal)
                           (route-schedule-not-verified-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
