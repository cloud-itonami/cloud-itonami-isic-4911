(ns railops.store
  "SSoT for the community interurban-passenger-rail OPERATIONS-
  COORDINATION actor, behind a `Store` protocol so the backend is a
  swap, not a rewrite -- the same seam every prior `cloud-itonami-
  isic-*` actor in this fleet uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/railops/store_contract_test.clj), which is the whole point:
  the actor, the Rail Safety Governor and the audit ledger never know
  which SSoT they run on.

  The unit of work here is a `service` -- one scheduled interurban
  passenger-rail service run (a route + timetable slot + consist).
  `:route-schedule-registered?` is the flag `railops.governor`'s HARD
  `route-schedule-not-verified` check reads: it means the service's
  route/timetable slot has been independently verified/registered by
  a system OUTSIDE this actor (a safety-management-system / timetable
  authority of record) -- deliberately there is NO op/effect ANYWHERE
  in this actor's closed allowlist (`railops.governor/allowed-ops`)
  that can set it. This actor coordinates AROUND an already-registered
  service; it can never self-certify the precondition its own governor
  gates on. `with-services`/`seed-db` are the only ways this flag is
  ever set (demo seeding / an external registration feed), never a
  commit path.

  The ledger stays append-only on every backend: 'which service was
  logged, which schedule/consist change was proposed, which passenger-
  safety concern was flagged, which maintenance coordination was
  proposed, on what basis, approved by whom' is always a query over an
  immutable log -- the audit trail a regulator or passenger-rights
  body needs, and the evidence an operator needs if a service decision
  is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [railops.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (service [s id])
  (all-services [s])
  (ledger [s])
  (service-log-history [s] "append-only :log-service-record drafts")
  (schedule-proposal-history [s] "append-only :schedule-service-operation drafts")
  (safety-concern-history [s] "append-only :flag-passenger-safety-concern flags")
  (maintenance-coordination-history [s] "append-only :coordinate-maintenance drafts")
  (next-sequence [s kind] "next record sequence for a record kind (:log/:schedule/:concern/:maintenance)")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-services [s services] "replace/seed the service directory (map id->service)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained service set covering the clean path and
  the governor's HARD `route-schedule-not-verified` check, so the
  actor + tests run offline."
  []
  {:services
   {"service-1" {:id "service-1" :route "Tokyo-Nagano Express"
                 :consist "consist-7" :operator "Community Interurban Rail"
                 :route-schedule-registered? true}
    "service-2" {:id "service-2" :route "Atlantis-Nowhere Express"
                 :consist "consist-9" :operator "Atlantis Rail"
                 :route-schedule-registered? false}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- draft-for [op service-id sequence payload]
  (case op
    :log-service-record            (registry/register-service-log service-id sequence payload)
    :schedule-service-operation    (registry/register-schedule-proposal service-id sequence payload)
    :flag-passenger-safety-concern (registry/register-safety-concern-flag service-id sequence payload)
    :coordinate-maintenance        (registry/register-maintenance-coordination service-id sequence payload)))

(defn- seq-kind [op]
  (case op
    :log-service-record            :log
    :schedule-service-operation    :schedule
    :flag-passenger-safety-concern :concern
    :coordinate-maintenance        :maintenance))

;; ----------------------------- MemStore (default) -----------------------------

(def ^:private history-kw
  {:log :service-logs :schedule :schedule-proposals
   :concern :safety-concerns :maintenance :maintenance-coordinations})

(defrecord MemStore [a]
  Store
  (service [_ id] (get-in @a [:services id]))
  (all-services [_] (sort-by :id (vals (:services @a))))
  (ledger [_] (:ledger @a))
  (service-log-history [_] (:service-logs @a))
  (schedule-proposal-history [_] (:schedule-proposals @a))
  (safety-concern-history [_] (:safety-concerns @a))
  (maintenance-coordination-history [_] (:maintenance-coordinations @a))
  (next-sequence [_ kind] (get-in @a [:sequences kind] 0))
  (commit-record! [s {:keys [op path payload]}]
    (let [service-id (first path)
          kind (seq-kind op)
          sequence (next-sequence s kind)
          result (draft-for op service-id sequence payload)
          hk (history-kw kind)]
      (swap! a (fn [state]
                 (-> state
                     (update-in [:sequences kind] (fnil inc 0))
                     (update hk registry/append result))))
      result))
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-services [s services] (when (seq services) (swap! a assoc :services services)) s))

(defn seed-db
  "A MemStore seeded with the demo service set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :ledger [] :sequences {}
                           :service-logs [] :schedule-proposals []
                           :safety-concerns [] :maintenance-coordinations []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (ledger facts, per-op drafts) are stored as EDN
  strings so `langchain.db` doesn't expand them into sub-entities --
  the same convention every sibling actor's store uses."
  {:service/id      {:db/unique :db.unique/identity}
   :ledger/seq      {:db/unique :db.unique/identity}
   :seq/kind        {:db/unique :db.unique/identity}
   :log/seq         {:db/unique :db.unique/identity}
   :schedule/seq    {:db/unique :db.unique/identity}
   :concern/seq     {:db/unique :db.unique/identity}
   :maintenance/seq {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- service->tx [{:keys [id route consist operator route-schedule-registered?]}]
  (cond-> {:service/id id}
    route                              (assoc :service/route route)
    consist                            (assoc :service/consist consist)
    operator                           (assoc :service/operator operator)
    (some? route-schedule-registered?) (assoc :service/route-schedule-registered? route-schedule-registered?)))

(def ^:private service-pull
  [:service/id :service/route :service/consist :service/operator
   :service/route-schedule-registered?])

(defn- pull->service [m]
  (when (:service/id m)
    {:id (:service/id m) :route (:service/route m) :consist (:service/consist m)
     :operator (:service/operator m)
     :route-schedule-registered? (boolean (:service/route-schedule-registered? m))}))

(defrecord DatomicStore [conn]
  Store
  (service [_ id]
    (pull->service (d/pull (d/db conn) service-pull [:service/id id])))
  (all-services [_]
    (->> (d/q '[:find [?id ...] :where [?e :service/id ?id]] (d/db conn))
         (map #(pull->service (d/pull (d/db conn) service-pull [:service/id %])))
         (sort-by :id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (service-log-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :log/seq ?s] [?e :log/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (schedule-proposal-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :schedule/seq ?s] [?e :schedule/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (safety-concern-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :concern/seq ?s] [?e :concern/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (maintenance-coordination-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :maintenance/seq ?s] [?e :maintenance/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ kind]
    (or (d/q '[:find ?n . :in $ ?k
              :where [?e :seq/kind ?k] [?e :seq/next ?n]]
            (d/db conn) kind)
        0))
  (commit-record! [s {:keys [op path payload]}]
    (let [service-id (first path)
          kind (seq-kind op)
          sequence (next-sequence s kind)
          result (draft-for op service-id sequence payload)
          history-count (case kind
                          :log (count (service-log-history s))
                          :schedule (count (schedule-proposal-history s))
                          :concern (count (safety-concern-history s))
                          :maintenance (count (maintenance-coordination-history s)))]
      (case kind
        :log
        (d/transact! conn [{:seq/kind kind :seq/next (inc sequence)}
                           {:log/seq history-count :log/record (enc (get result "record"))}])
        :schedule
        (d/transact! conn [{:seq/kind kind :seq/next (inc sequence)}
                           {:schedule/seq history-count :schedule/record (enc (get result "record"))}])
        :concern
        (d/transact! conn [{:seq/kind kind :seq/next (inc sequence)}
                           {:concern/seq history-count :concern/record (enc (get result "record"))}])
        :maintenance
        (d/transact! conn [{:seq/kind kind :seq/next (inc sequence)}
                           {:maintenance/seq history-count :maintenance/record (enc (get result "record"))}]))
      result))
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-services [s services]
    (when (seq services) (d/transact! conn (mapv service->tx (vals services)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:services ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [services]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-services s services))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo service set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
