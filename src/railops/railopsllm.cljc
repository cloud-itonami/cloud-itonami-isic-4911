(ns railops.railopsllm
  "Rail-Operations-LLM client -- the *contained intelligence node* for
  the community interurban-passenger-rail operations-coordination
  actor.

  It drafts a service-log entry (schedule-adherence/ridership/incident
  data), a schedule/consist-operation proposal, a passenger-safety-
  concern flag, or a maintenance-coordination request. CRITICAL: it is
  a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER
  a real dispatch/signal-safety/rolling-stock action. Every output is
  censored downstream by `railops.governor` before anything touches
  the SSoT, and `:effect` is ALWAYS `:propose` -- see README
  `Actuation`.

  IMPORTANT (fleet-wide self-tripping bug class, avoided here from day
  one): NONE of this namespace's default rationale/summary text uses
  the governor's forbidden finalization ACTION phrases
  (`railops.governor/scope-exclusion-phrases`, e.g. 'finalize the
  dispatch-safety override', 'clear the train for departure',
  'override the signal interlock'). Ordinary domain words like
  'safety'/'dispatch'/'signal'/'departure' DO appear in this
  namespace's rationale text (e.g. `:flag-passenger-safety-concern`'s
  'signal fault reported' below) -- that is fine and expected, because
  the governor's scope-exclusion check matches specific multi-word
  finalization ACTIONS, never a bare noun. See
  `governor-contract-test`'s
  `mock-advisor-defaults-never-self-trip-scope-exclusion` for the
  regression test that keeps this true.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [kw|str ..]    ; facts/fields the LLM used -- SCANNED too
     :effect     kw             ; ALWAYS :propose -- see README `Actuation`
     :value      map            ; the draft payload railops.store persists
     :stake      kw|nil         ; :safety-concern/flag | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [railops.store :as store]
            [langchain.model :as model]))

(defn- log-service-record
  "Normalizes a schedule-adherence/ridership/incident log entry for
  `subject`. The LLM only normalizes/validates the submitted data; it
  does not invent ridership numbers or incident facts. Low stakes,
  high confidence."
  [_db {:keys [subject kind data]}]
  (let [k (or kind :schedule-adherence)]
    {:summary    (str subject " のサービス記録ログ (" (name k) ") を提案")
     :rationale  "提出されたdataの正規化のみ。新規事実の創作なし。"
     :cites      [subject (name k)]
     :effect     :propose
     :value      {:kind k :data (or data {})}
     :stake      nil
     :confidence 0.95}))

(defn- schedule-service-operation
  "Drafts a timetable/consist scheduling COORDINATION PROPOSAL for
  `subject` -- NEVER a real service dispatch. This op only ever
  produces a draft record `railops.registry/register-schedule-
  proposal` builds; committing it never moves a train."
  [db {:keys [subject change-summary proposed-consist]}]
  (let [svc (store/service db subject)]
    {:summary    (str subject " 向け運行計画調整の提案 (実際の配車ではない)")
     :rationale  (if svc
                   (str "route-schedule-registered?=" (:route-schedule-registered? svc)
                        " -- 経路/時刻表の独立登録状況を確認のうえ調整案を作成")
                   "serviceが見つかりません")
     :cites      (if svc [subject "route-schedule-registered?"] [])
     :effect     :propose
     :value      {:change-summary (or change-summary "") :proposed-consist proposed-consist}
     :stake      nil
     :confidence (if (and svc (:route-schedule-registered? svc)) 0.9 0.3)}))

(defn- flag-passenger-safety-concern
  "Drafts a passenger-safety-concern FLAG for `subject` -- a
  signal-fault/platform-hazard/incident concern surfaced for a human
  to act on. ALWAYS `:stake :safety-concern/flag` -- this op NEVER
  auto-commits at any phase (`railops.phase`); the governor also
  always escalates on `:safety-concern/flag`. Two independent layers
  agree, deliberately. This op's own rationale text legitimately uses
  the words 'signal'/'safety'/'fault' as ordinary domain vocabulary --
  it never uses a governor scope-exclusion ACTION phrase (it never
  claims to 'clear', 'override' or 'finalize' anything; it only
  reports)."
  [db {:keys [subject concern-kind description]}]
  (let [svc (store/service db subject)]
    {:summary    (str subject " について旅客安全上の懸念を報告 (人間レビュー必須)")
     :rationale  (str "信号故障/プラットフォーム危険/インシデントの懸念を報告するのみ -- "
                       "このアクターは信号インターロックの解除や発車許可を一切行わない。"
                       (when svc (str " route-schedule-registered?=" (:route-schedule-registered? svc))))
     :cites      (if svc [subject] [])
     :effect     :propose
     :value      {:concern-kind (or concern-kind :unspecified) :description (or description "")}
     :stake      :safety-concern/flag
     :confidence 0.8}))

(defn- coordinate-maintenance
  "Drafts a rolling-stock/track MAINTENANCE-COORDINATION request for
  `subject` -- NEVER a maintenance release or execution. That remains
  entirely a qualified maintenance provider's own act."
  [db {:keys [subject target coordination-note]}]
  (let [svc (store/service db subject)]
    {:summary    (str subject " の保守調整案 (対象: " (name (or target :rolling-stock)) ")")
     :rationale  (if svc
                   "保守提供者への調整依頼案を作成。保守作業自体の実施・解放は行わない。"
                   "serviceが見つかりません")
     :cites      (if svc [subject] [])
     :effect     :propose
     :value      {:target (or target :rolling-stock) :coordination-note (or coordination-note "")}
     :stake      nil
     :confidence (if svc 0.85 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}. An op outside the
  closed allowlist falls through to a safe low-confidence noop --
  `railops.governor`'s `op-not-allowlisted-violations` independently
  blocks it too (two layers, not one)."
  [db {:keys [op] :as request}]
  (case op
    :log-service-record            (log-service-record db request)
    :schedule-service-operation    (schedule-service-operation db request)
    :flag-passenger-safety-concern (flag-passenger-safety-concern db request)
    :coordinate-maintenance        (coordinate-maintenance db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :propose :value {} :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地域相互都市間旅客鉄道の運行調整デスクの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :effect(常に:propose) "
       ":value(ドラフトの内容) "
       ":stake(:safety-concern/flag か nil) :confidence(0..1)。\n"
       "重要: このアクターは信号/安全インターロックの最終化・列車発車許可・"
       "保守作業の実施/解放を一切行いません。それらの実行を提案してはいけません。"
       "経路/時刻表が独立登録済みか偽って報告してはいけません。"))

(defn- facts-for [st {:keys [subject]}]
  {:service (store/service st subject)})

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Rail Safety Governor
  escalates/holds -- an LLM hiccup can never auto-commit a real
  coordination record, let alone finalize a dispatch/signal-safety
  decision."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :propose))
          (update :value #(or % {})))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :propose :value {} :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :railopsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
