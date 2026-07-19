(ns partners.store
  "SSoT for the cloud-itonami-partners actor, behind a `Store` protocol so
  the backend is a swap, not a rewrite -- the same seam
  `cloud-itonami-isic-6511`'s `underwriting.store` (the reference ENTITY-
  store adopter, ADR-2607141600) uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/partners/store_contract_test.cljc), which is the whole point: the
  actor, PartnerGovernor and the audit ledger never know which SSoT they
  run on.

  Per CLAUDE.md ('Store は :db-api 駆動... 自前でハンドロールしない,
  ADR-2607141600'), the EDN-blob codec, `:db.unique/identity` schema and
  entity field/pull shaping are `kotoba-lang/langchain-store`
  (`langchain-store.core`), NOT hand-rolled `enc`/`dec*` functions.

  The ledger stays append-only on every backend: 'who applied for which
  itonami vertical/territory, on what screening basis, approved or rejected
  by whom, and which territory grants are already on file' is always a
  query over an immutable log -- the audit trail an applicant, and the
  human approver, needs if a decision is ever disputed."
  (:require [partners.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (application [s id])
  (all-applications [s])
  (screening-result-of [s application-id] "committed ScreeningAdvisor proposal for an application, or nil")
  (ledger [s])
  (territory-grants [s] "the append-only territory-grant history -- PartnerGovernor's exclusivity check source of truth")
  (next-sequence [s] "next partner-application sequence number")
  (grant-sequence [s] "next territory-grant sequence number")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-applications [s apps] "replace/seed the application directory (map id->application)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained application set so the actor + tests run
  offline. `app-1` is a clean application against a real catalogued
  vertical/territory with no prior grant; `app-2` deliberately targets an
  itonami-vertical-ref that is NOT in `partners.catalog/verticals` (a
  fabricated/unknown code), and `app-3` deliberately targets the SAME
  vertical+territory as an existing `territory-grant` seed below --
  PartnerGovernor must HOLD both, independently, for different reasons."
  []
  {:applications
   {"app-1" {:id "app-1" :applicant-name "田中 花子" :applicant-contact "hanako@example.com"
             :itonami-vertical-ref "cloud-itonami-isic-6499" :territory {:country "JPN" :region nil}
             :pitch "Tokyo VC ecosystem experience, 8 years." :capital-tier "tier-2"
             :language "ja" :submitted-at "2026-07-19T00:00:00Z" :status :pending}
    "app-2" {:id "app-2" :applicant-name "J. Doe" :applicant-contact "jdoe@example.com"
             :itonami-vertical-ref "cloud-itonami-isic-9999" :territory {:country "USA" :region "CA"}
             :pitch "Interested in an unlisted vertical." :capital-tier "tier-1"
             :language "en" :submitted-at "2026-07-19T00:00:00Z" :status :pending}
    "app-3" {:id "app-3" :applicant-name "M. Rossi" :applicant-contact "rossi@example.com"
             :itonami-vertical-ref "cloud-itonami-isic-6511" :territory {:country "ITA" :region nil}
             :pitch "Licensed life-insurance broker, 12 years in Milan." :capital-tier "tier-3"
             :language "it" :submitted-at "2026-07-19T00:00:00Z" :status :pending}}})

(def demo-existing-grant
  "A pre-existing territory-grant seed (ITA / isic-6511) that `app-3`
  deliberately collides with -- see `demo-data`'s docstring."
  {"record_id" "TERRITORY-GRANT-00000000"
   "kind" "territory-grant-draft"
   "itonami_vertical" "cloud-itonami-isic-6511"
   "territory" {:country "ITA" :region nil}
   "partner_application_id" "app-0"
   "granted_at" "2026-06-01T00:00:00Z"
   "immutable" true})

;; ----------------------------- shared onboard logic -----------------------------

(defn- onboard!
  "Backend-agnostic `:territory/grant-recorded` -- looks up the application,
  drafts the territory-grant record, and returns {:result ..} for the
  caller to persist. Pure w.r.t. any particular backend's transaction
  mechanics. NEVER invoked except by `partners.operation`'s `:onboard` node
  on an application already governor-clean AND human-approved."
  [s app-id granted-at]
  (let [a (application s app-id)
        seq-n (grant-sequence s)
        result (registry/register-territory-grant
                (:itonami-vertical-ref a) (:territory a) app-id seq-n granted-at)]
    {:result result
     :app-patch {:status :approved}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (application [_ id] (get-in @a [:applications id]))
  (all-applications [_] (sort-by :id (vals (:applications @a))))
  (screening-result-of [_ app-id] (get-in @a [:screening-results app-id]))
  (ledger [_] (:ledger @a))
  (territory-grants [_] (:territory-grants @a))
  (next-sequence [_] (:sequence @a 0))
  (grant-sequence [_] (:grant-sequence @a 0))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      ;; bumps :sequence in the SAME swap as the upsert -- next-sequence
      ;; must advance exactly once per registered application, even under
      ;; concurrent intake!, so id-assignment (registry/register-application,
      ;; called BEFORE this commit with the pre-swap sequence) and the
      ;; counter advance are not two separately-racy steps.
      :application/upsert
      (swap! a (fn [state]
                 (-> state
                     (update-in [:applications (:id value)] merge value)
                     (update :sequence (fnil inc 0)))))

      :application/status
      (swap! a assoc-in [:applications (first path) :status] payload)

      :screening/set
      (swap! a assoc-in [:screening-results (first path)] payload)

      :territory/grant-recorded
      (let [app-id (first path)
            granted-at (:granted-at payload)
            {:keys [result app-patch]} (onboard! s app-id granted-at)]
        (swap! a (fn [state]
                   (-> state
                       (update :grant-sequence (fnil inc 0))
                       (update-in [:applications app-id] merge app-patch)
                       (update :territory-grants registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-applications [s apps] (when (seq apps) (swap! a assoc :applications apps)) s))

(defn seed-db
  "A MemStore seeded with the demo application set + one pre-existing
  territory grant. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :screening-results {} :ledger [] :sequence 0
                           :grant-sequence 1
                           :territory-grants [demo-existing-grant]))))

(defn empty-db
  "A MemStore with no seed data at all -- for tests that want full control
  over exactly what's on file."
  []
  (->MemStore (atom {:applications {} :screening-results {} :ledger []
                     :sequence 0 :grant-sequence 0 :territory-grants []})))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

;; Schema, the EDN-blob codec (enc/dec*) and the application entity
;; map<->tx<->pull are the shared kotoba-lang/langchain-store machinery
;; (ADR-2607141600) -- the seam ~190 actors hand-roll. This store follows
;; `underwriting.store`'s reference ENTITY-store shape; the application
;; field spec is the only per-entity data, and the screening-result/ledger/
;; territory-grant/sequence attrs (custom query shapes, append-only logs)
;; keep their own wiring below, still using the shared enc/dec*.
(def ^:private schema
  (ls/identity-schema [:app/id :screening/app-id :ledger/seq
                       :territory-grant/seq :sequence/global :grant-sequence/global]))

(defn- enc [v] (ls/enc v))
(defn- dec* [s] (ls/dec* s))

;; PUBLIC (not ^:private) on purpose: partners.governor's minimal-disclosure
;; invariant (docstring point 6) is verified structurally by
;; governor_contract_test asserting this field-spec's key set is EXACTLY
;; the ADR-2607194000 applicant-supplied fields plus the three
;; administrative ones (:id/:submitted-at/:status) -- a future accidental
;; field addition here should fail that test, not silently over-collect.
(def app-spec
  {:id {:attr :app/id}
   :applicant-name {:attr :app/applicant-name}
   :applicant-contact {:attr :app/applicant-contact}
   :itonami-vertical-ref {:attr :app/itonami-vertical-ref}
   :territory {:attr :app/territory :blob? true :default nil}
   :pitch {:attr :app/pitch}
   :capital-tier {:attr :app/capital-tier}
   :language {:attr :app/language}
   :submitted-at {:attr :app/submitted-at}
   :status {:attr :app/status :blob? true :default :pending}})

(defn- app->tx [m] (ls/map->tx app-spec m))
(def ^:private app-pull (ls/pull-pattern app-spec))
(defn- pull->app [m] (ls/pull->map app-spec :id m))

(defrecord DatomicStore [conn]
  Store
  (application [_ id]
    (pull->app (d/pull (d/db conn) app-pull [:app/id id])))
  (all-applications [_]
    (->> (d/q '[:find [?id ...] :where [?e :app/id ?id]] (d/db conn))
         (map #(pull->app (d/pull (d/db conn) app-pull [:app/id %])))
         (sort-by :id)))
  (screening-result-of [_ app-id]
    (dec* (d/q '[:find ?p . :in $ ?aid
                :where [?s :screening/app-id ?aid] [?s :screening/payload ?p]]
              (d/db conn) app-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (territory-grants [_]
    (->> (d/q '[:find ?s ?r :where [?e :territory-grant/seq ?s] [?e :territory-grant/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_]
    (or (d/q '[:find ?n . :where [?e :sequence/global ?n]] (d/db conn)) 0))
  (grant-sequence [_]
    (or (d/q '[:find ?n . :where [?e :grant-sequence/global ?n]] (d/db conn)) 0))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :application/upsert
      (d/transact! conn [(app->tx value) {:sequence/global (inc (next-sequence s))}])

      :application/status
      (d/transact! conn [{:app/id (first path) :app/status (enc payload)}])

      :screening/set
      (d/transact! conn [{:screening/app-id (first path) :screening/payload (enc payload)}])

      :territory/grant-recorded
      (let [app-id (first path)
            granted-at (:granted-at payload)
            {:keys [result app-patch]} (onboard! s app-id granted-at)
            next-n (inc (grant-sequence s))]
        (d/transact! conn
                     [(app->tx (assoc app-patch :id app-id))
                      {:grant-sequence/global next-n}
                      {:territory-grant/seq (count (territory-grants s))
                       :territory-grant/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-applications [s apps]
    (when (seq apps) (d/transact! conn (mapv app->tx (vals apps)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:applications ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [applications]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-applications s applications))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo application set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity. (Does
  NOT seed `demo-existing-grant` -- store-contract parity only needs to
  cover the `Store` protocol surface itself, which `territory-grants`
  already exercises via `:territory/grant-recorded`.)"
  []
  (datomic-store (demo-data)))
