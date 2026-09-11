(ns partners.operation-test
  "End-to-end StateGraph tests -- the single invariant (ADR-2607194000 /
  CLAUDE.md Actors: 'governor が拒否する territory-grant を actor は決して
  行わない') AND the permanent human-gate invariant ('territory 付与は
  オーナー最終承認を必須とする... interrupt-before' -- no phase or
  confidence threshold ever bypasses it) are both proven end-to-end here,
  not just unit-tested per node."
  (:require [clojure.test :refer [deftest is]]
            [langgraph.graph :as g]
            [partners.operation :as op]
            [partners.store :as store]))

(def ctx {:actor-id "test" :approver "test-owner"})

(defn- govern! [actor tid app-id]
  (g/run* actor {:request {:op :partner/govern :subject app-id} :context ctx} {:thread-id tid}))

(defn- resume! [actor tid approval]
  (g/run* actor {:approval approval} {:thread-id tid :resume? true}))

(defn- no-grant-for?
  "`store/seed-db` already ships ONE pre-existing territory-grant
  (`demo-existing-grant`, for app-0/isic-6511/ITA -- `app-3`'s deliberate
  collision fixture), so asserting the WHOLE grant list is empty is wrong
  for any test that shares that seed. This checks no grant for THIS
  specific application-id exists instead."
  [s app-id]
  (empty? (filter #(= app-id (get % "partner_application_id")) (store/territory-grants s))))

(deftest clean-application-interrupts-before-approval-never-auto-commits
  (let [s (store/seed-db)
        actor (op/build s)
        r (govern! actor "t1" "app-1")]
    (is (= :interrupted (:status r)))
    (is (= [:request-approval] (:frontier r))
        "a governor-clean application ALWAYS pauses at request-approval -- no auto-commit path exists")
    (is (no-grant-for? s "app-1")
        "no territory-grant record exists yet -- interrupt-before means NOTHING committed")
    (is (= :screening (:status (store/application s "app-1"))))
    (is (some? (store/screening-result-of s "app-1"))
        "the ScreeningAdvisor proposal is persisted BEFORE the governor/human ever weighs in")))

(deftest approved-application-onboards-mints-identity-and-grants-territory
  (let [s (store/seed-db)
        actor (op/build s)]
    (govern! actor "t2" "app-1")
    (let [r (resume! actor "t2" {:status :approved :by "jun"})]
      (is (= :done (:status r))))
    (is (= :approved (:status (store/application s "app-1"))))
    (is (= 1 (count (filter #(= "app-1" (get % "partner_application_id")) (store/territory-grants s)))))
    (let [granted (first (filter #(= "app-1" (get % "partner_application_id")) (store/territory-grants s)))]
      (is (= "cloud-itonami-isic-6499" (get granted "itonami_vertical")))
      (is (= {:country "JPN" :region nil} (get granted "territory"))))
    (let [onboard-fact (last (filter #(= :territory-granted (:t %)) (store/ledger s)))]
      (is (some? onboard-fact))
      (is (= "app-1" (:subject onboard-fact))))))

(deftest hard-hold-never-reaches-a-human
  (let [s (store/seed-db)
        actor (op/build s)
        r (govern! actor "t3" "app-2")]
    (is (= :done (:status r)) "a HARD violation terminates immediately -- no interrupt-before is ever hit")
    (is (= :rejected (:status (store/application s "app-2"))))
    (is (no-grant-for? s "app-2"))
    (let [hold (last (filter #(= :governor-hold (:t %)) (store/ledger s)))]
      (is (some? hold))
      (is (some #(= :unknown-itonami-vertical %) (:basis hold))))))

(deftest territory-exclusivity-hard-hold-never-reaches-a-human
  (let [s (store/seed-db)
        actor (op/build s)
        r (govern! actor "t4" "app-3")]
    (is (= :done (:status r)))
    (is (= :rejected (:status (store/application s "app-3"))))
    (let [hold (last (filter #(= :governor-hold (:t %)) (store/ledger s)))]
      (is (some #(= :territory-already-granted %) (:basis hold))))))

(deftest human-can-reject-a-governor-clean-application
  (let [s (store/seed-db)
        actor (op/build s)]
    (govern! actor "t5" "app-1")
    (resume! actor "t5" {:status :rejected :by "jun" :reason "capital tier too low"})
    (is (= :rejected (:status (store/application s "app-1"))))
    (is (no-grant-for? s "app-1"))
    (let [rej (last (filter #(= :approval-rejected (:t %)) (store/ledger s)))]
      (is (some? rej)))))

(deftest human-can-waitlist-a-governor-clean-application
  (let [s (store/seed-db)
        actor (op/build s)]
    (govern! actor "t6" "app-1")
    (resume! actor "t6" {:status :waitlisted :by "jun" :reason "revisit next quarter"})
    (is (= :waitlisted (:status (store/application s "app-1"))))
    (is (no-grant-for? s "app-1"))))

(deftest stale-interrupt-resolves-to-waitlist-never-silent-approve
  (let [s (store/seed-db)
        actor (op/build s)]
    (govern! actor "t7" "app-1")
    ;; resume with NO :approval decision recorded at all -- ADR-2607194000
    ;; step 4: "a stale/unresolved interrupt can resolve to waitlist"
    (resume! actor "t7" nil)
    (is (= :waitlisted (:status (store/application s "app-1"))))
    (is (no-grant-for? s "app-1")
        "a stale interrupt NEVER silently approves a territory grant")))

(deftest intake-then-govern-full-flow-through-fresh-application
  (let [s (store/seed-db)
        actor (op/build s)
        id (op/intake! s {:applicant-name "New Applicant" :applicant-contact "n@example.com"
                          :itonami-vertical-ref "cloud-itonami-isic-8291"
                          :territory {:country "FRA"} :pitch "Compliance-intelligence background."
                          :capital-tier "tier-3" :language "fr"
                          :submitted-at "2026-07-19T00:00:00Z"})]
    (is (= :pending (:status (store/application s id))))
    (let [r (govern! actor "t8" id)]
      (is (= :interrupted (:status r))))
    (resume! actor "t8" {:status :approved :by "jun"})
    (is (= :approved (:status (store/application s id))))))
