(ns partners.operation
  "OperationActor -- one partner application = one supervised actor run,
  expressed as a langgraph-clj StateGraph (ADR-2607194000). The
  ScreeningAdvisor is sealed into a single node (`:screen`); its proposal is
  ALWAYS routed through PartnerGovernor (`:govern`) before anything ever
  commits to the SSoT, and a governor-CLEAN application still ALWAYS pauses
  for human approval (`interrupt-before #{:request-approval}`) -- there is
  no phase or confidence threshold anywhere in this graph that lets a
  territory grant auto-commit. See README 'Why no phase.cljc' and
  `partners.governor`'s docstring.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (MemStore today; Datomic/kotoba-server is the next seam) - `store` arg
    - the Advisor  (mock | real LLM)                                       - :advisor opt
    - the mint fn  (JVM CACAO mint | cljs no-op stub)                      - :mint-fn opt

  One graph run = one application (intake -> screen -> govern -> decide ->
  [reject | request-approval -> {onboard | reject | waitlist}]). No
  unbounded inner loop -- each run is auditable and checkpointed.

  Human-in-the-loop = the actual, permanent approval workflow (ADR-2607194000
  step 3-4): `interrupt-before #{:request-approval}` pauses the actor and
  hands the decision to the repo owner. The approver resumes with
  `{:approval {:status :approved :by \"...\"}}` (or `:rejected`/
  `:waitlisted`) -- see README 'Human approval' for the concrete operational
  procedure."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [partners.advisor :as advisor]
            [partners.governor :as governor]
            [partners.registry :as registry]
            [partners.store :as store]
            #?(:clj [partners.cacao :as cacao])))

(defn- application-status! [store app-id status]
  (store/commit-record! store {:effect :application/status :path [app-id] :payload status})
  store)

(defn- persist-screening-result!
  "Commits the ScreeningAdvisor's proposal as a `screening-result` entity
  (ADR-2607194000 data model) -- persisted for audit BEFORE PartnerGovernor
  ever runs, so a human reviewing a later HOLD/approval-request can always
  see exactly what the advisor proposed, never just the governor's verdict."
  [store request proposal]
  (store/commit-record!
   store {:effect :screening/set
          :path [(:subject request)]
          :payload {:application-id (:subject request)
                    :llm-score (:score proposal)
                    :llm-rationale (:rationale proposal)
                    :advisor-model-ref (:advisor-model-ref proposal "mock-advisor")}}))

;; JVM-only real CACAO mint; CLJS hosts get an explicit, honest no-op stub
;; (never a silently-fabricated identity) -- see this ns's own docstring
;; 'injected, so each is a swap'.
(def default-mint-fn
  #?(:clj cacao/mint-for-partner!
     :cljs (fn [app-id _application]
             {:did nil :graph nil
              :note (str "CACAO minting is JVM-only in this actor (partners.cacao) -- "
                         "no identity minted for " app-id " under this host.")})))

(defn build
  "Compiles an OperationActor graph bound to `store` (any
  `partners.store/Store`).
  opts:
    :advisor      -- a `partners.advisor/Advisor` (default: mock-advisor)
    :mint-fn      -- fn of [application-id application-map] -> identity map
                     (default: `default-mint-fn`)
    :checkpointer -- langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor mint-fn checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    mint-fn      default-mint-fn
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; injected actor-id/approver
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}   ; :reject | :escalate | :commit | :waitlist
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake
        (fn [{:keys [request]}]
          (application-status! store (:subject request) :screening)
          {:audit [{:t :intake :op :partner/intake :subject (:subject request)}]}))

      ;; ScreeningAdvisor (the contained intelligence node) -- proposal only.
      (g/add-node :screen
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            (persist-screening-result! store request p)
            {:proposal p :audit [(advisor/trace request p)]})))

      ;; PartnerGovernor -- independent censor (separate system from the LLM).
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store)}))

      ;; Decide: a HARD governor violation goes straight to :reject, no
      ;; interrupt needed for a clean reject (ADR-2607194000 step 3).
      ;; A governor-clean application ALWAYS escalates -- there is no
      ;; auto-commit branch here, unlike vcfund/underwriting's phase gate.
      (g/add-node :decide
        (fn [{:keys [request context verdict]}]
          (if (:hard? verdict)
            {:disposition :reject
             :audit [(governor/hold-fact request context verdict)]}
            {:disposition :escalate
             :audit [{:t :approval-requested :op (:op request) :subject (:subject request)
                      :reason :territory-grant-always-human-gated}]})))

      ;; Approval handoff -- paused by interrupt-before; the repo owner
      ;; resumes with :approval. Routes to onboard/reject/waitlist.
      (g/add-node :request-approval
        (fn [{:keys [approval]}]
          (case (:status approval)
            :approved   {:disposition :commit
                         :audit [{:t :approval-granted :by (:by approval)}]}
            :rejected   {:disposition :reject
                         :audit [{:t :approval-rejected :by (:by approval)
                                  :reason (:reason approval)}]}
            :waitlisted {:disposition :waitlist
                         :audit [{:t :approval-waitlisted :by (:by approval)
                                  :reason (:reason approval)}]}
            ;; a stale/unresolved interrupt (no approval ever recorded)
            ;; resolves to waitlist, per ADR-2607194000 step 4 -- never a
            ;; silent approve.
            {:disposition :waitlist
             :audit [{:t :approval-stale-waitlisted}]})))

      ;; Onboard -- the ONLY node that mints a CACAO identity and grants a
      ;; territory. Reachable ONLY via human :approved (never governor-auto).
      (g/add-node :onboard
        (fn [{:keys [request]}]
          (let [app-id (:subject request)
                app (store/application store app-id)
                identity (mint-fn app-id app)
                granted-at #?(:clj (str (java.time.Instant/now))
                              :cljs (.toISOString (js/Date.)))
                result (store/commit-record!
                        store {:effect :territory/grant-recorded
                               :path [app-id]
                               :payload {:granted-at granted-at}})
                fact {:t :territory-granted :op :partner/onboard :subject app-id
                      :grant-id (get result "grant_id")
                      :identity-did (:did identity) :identity-graph (:graph identity)
                      :identity-note (:note identity)}]
            (store/append-ledger! store fact)
            {:audit [fact]})))

      ;; Reject -- write the rejection to the ledger (governor-hold OR
      ;; human-rejected); no territory-grant, no CACAO mint.
      (g/add-node :reject
        (fn [{:keys [request audit]}]
          (application-status! store (:subject request) :rejected)
          (let [f (or (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))
                      {:t :governor-hold :op (:op request) :subject (:subject request)
                       :disposition :hold})]
            (store/append-ledger! store (assoc f :disposition :hold))
            {})))

      ;; Waitlist -- write the waitlist fact; the application stays
      ;; re-visitable (a later run can resume through :request-approval
      ;; again with a fresh :approval).
      (g/add-node :waitlist
        (fn [{:keys [request]}]
          (application-status! store (:subject request) :waitlisted)
          (let [f {:t :waitlisted :op (:op request) :subject (:subject request)}]
            (store/append-ledger! store f)
            {})))

      (g/set-entry-point :intake)
      (g/add-edge :intake :screen)
      (g/add-edge :screen :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :reject   :reject
            :escalate :request-approval)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :onboard
            :reject   :reject
            :waitlist :waitlist)))

      (g/set-finish-point :onboard)
      (g/set-finish-point :reject)
      (g/set-finish-point :waitlist)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))

(defn intake!
  "Convenience: store a NEW partner-application (drafted via
  `partners.registry/register-application`) and return its id. Callers
  (the public intake endpoint, or `partners.sim`) use this BEFORE invoking
  the compiled graph with `{:request {:op :partner/govern :subject id}}`."
  [store {:keys [applicant-name applicant-contact itonami-vertical-ref
                 territory pitch capital-tier language submitted-at]}]
  (let [seq-n (store/next-sequence store)
        {:strs [application_id]} (registry/register-application
                                   {:applicant-name applicant-name
                                    :applicant-contact applicant-contact
                                    :itonami-vertical-ref itonami-vertical-ref
                                    :territory territory
                                    :pitch pitch
                                    :capital-tier capital-tier
                                    :language language
                                    :sequence seq-n})]
    (store/commit-record!
     store {:effect :application/upsert
            :path [application_id]
            :value {:id application_id
                    :applicant-name applicant-name
                    :applicant-contact applicant-contact
                    :itonami-vertical-ref itonami-vertical-ref
                    :territory territory
                    :pitch pitch
                    :capital-tier capital-tier
                    :language language
                    :submitted-at submitted-at
                    :status :pending}})
    application_id))
