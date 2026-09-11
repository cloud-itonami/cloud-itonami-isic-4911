(ns railops.registry
  "Pure-function OPERATIONS-COORDINATION record construction -- an
  append-only draft book-of-record for the community interurban-
  passenger-rail actor's four proposal ops.

  This actor is deliberately scoped to COORDINATION, not direct
  dispatch/signal-safety authority or rolling-stock control (see
  README `Robotics premise` / `Core Contract`): every record this
  namespace builds is a DRAFT PROPOSAL RECORD -- a schedule-adherence/
  ridership/incident log entry, a timetable/consist scheduling
  proposal, a passenger-safety-concern flag, or a maintenance-
  coordination request -- never a real dispatch, never a real signal-
  interlock override, never a real maintenance release. `:kind` on
  every record ends in `-draft` (or, for the safety-concern flag,
  carries `requires_human_review`/`advisory_only` true) and every
  certificate is UNSIGNED (`railops.operation`'s `:commit` node is the
  only writer of this actor's OWN SSoT; a rail operator's own
  certified safety-management system is a wholly separate, external
  system of record this actor never touches).

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real dispatch/signalling/maintenance system. It builds
  the RECORD an operations-coordination desk would keep, not the act
  itself.")

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the operator's act, not this actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn- record-id [prefix sequence]
  (str prefix "-" (zero-pad sequence 6)))

(defn- require-service-id [service-id caller]
  (when-not (and service-id (not= service-id ""))
    (throw (ex-info (str caller ": service-id required") {}))))

(defn- require-non-negative-sequence [sequence caller]
  (when (< sequence 0)
    (throw (ex-info (str caller ": sequence must be >= 0") {}))))

(defn register-service-log
  "Validate + construct a SERVICE-LOG DRAFT -- schedule-adherence,
  ridership or incident data logging for `service-id`. Pure function;
  does not touch any real operations system."
  ([service-id sequence] (register-service-log service-id sequence {}))
  ([service-id sequence payload]
   (require-service-id service-id "register-service-log")
   (require-non-negative-sequence sequence "register-service-log")
   (let [rid (record-id "LOG" sequence)
         record {"record_id" rid
                  "kind" "service-log-draft"
                  "service_id" service-id
                  "log_kind" (name (:kind payload :schedule-adherence))
                  "data" (:data payload {})
                  "immutable" true}]
     {"record" record "log_number" rid
      "certificate" (unsigned-certificate "ServiceLog" service-id rid)})))

(defn register-schedule-proposal
  "Validate + construct a SCHEDULE/CONSIST-OPERATION PROPOSAL DRAFT --
  a timetable or consist scheduling proposal for `service-id`. Pure
  function; NEVER dispatches a service. `railops.governor`
  independently re-verifies the service's own route/schedule
  registration ground truth before this is ever allowed to commit."
  ([service-id sequence] (register-schedule-proposal service-id sequence {}))
  ([service-id sequence payload]
   (require-service-id service-id "register-schedule-proposal")
   (require-non-negative-sequence sequence "register-schedule-proposal")
   (let [rid (record-id "SCH" sequence)
         record {"record_id" rid
                  "kind" "schedule-operation-proposal-draft"
                  "service_id" service-id
                  "change_summary" (:change-summary payload "")
                  "proposed_consist" (:proposed-consist payload)
                  "immutable" true}]
     {"record" record "proposal_number" rid
      "certificate" (unsigned-certificate "ScheduleServiceOperation" service-id rid)})))

(defn register-safety-concern-flag
  "Validate + construct a PASSENGER-SAFETY-CONCERN FLAG -- surfaces a
  signal-fault/platform-hazard/incident concern for `service-id`.
  ALWAYS `requires_human_review`/`advisory_only` true: this record
  NEVER finalizes, overrides or clears anything -- it is a flag for a
  human to act on, full stop. Pure function; does not touch any real
  signalling/dispatch system."
  ([service-id sequence] (register-safety-concern-flag service-id sequence {}))
  ([service-id sequence payload]
   (require-service-id service-id "register-safety-concern-flag")
   (require-non-negative-sequence sequence "register-safety-concern-flag")
   (let [rid (record-id "SFC" sequence)
         record {"record_id" rid
                  "kind" "passenger-safety-concern-flag"
                  "service_id" service-id
                  "concern_kind" (name (:concern-kind payload :unspecified))
                  "description" (:description payload "")
                  "requires_human_review" true
                  "advisory_only" true
                  "immutable" true}]
     {"record" record "concern_number" rid
      "certificate" (unsigned-certificate "PassengerSafetyConcernFlag" service-id rid)})))

(defn register-maintenance-coordination
  "Validate + construct a MAINTENANCE-COORDINATION DRAFT -- rolling-
  stock/track maintenance coordination for `service-id`. Pure
  function; NEVER releases a maintenance action -- that is a
  qualified maintenance provider's own act, entirely outside this
  actor's authority."
  ([service-id sequence] (register-maintenance-coordination service-id sequence {}))
  ([service-id sequence payload]
   (require-service-id service-id "register-maintenance-coordination")
   (require-non-negative-sequence sequence "register-maintenance-coordination")
   (let [rid (record-id "MNT" sequence)
         record {"record_id" rid
                  "kind" "maintenance-coordination-draft"
                  "service_id" service-id
                  "target" (name (:target payload :rolling-stock))
                  "coordination_note" (:coordination-note payload "")
                  "immutable" true}]
     {"record" record "coordination_number" rid
      "certificate" (unsigned-certificate "MaintenanceCoordination" service-id rid)})))

(defn append [history result]
  (conj (vec history) (get result "record")))
