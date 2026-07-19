(ns partners.sim
  "Demo driver -- `clojure -M:dev:run`. Walks ONE clean application through
  intake -> screen -> govern -> human approval -> onboard (CACAO mint +
  territory grant), then shows a governor HARD-hold (an unlisted itonami-
  vertical-ref) that never reaches a human at all, then a territory-
  exclusivity HARD-hold (a duplicate application for an already-granted
  vertical/territory), then a human REJECT and a human WAITLIST outcome on
  two more otherwise-clean applications -- printing the audit ledger and
  the draft territory-grant records at the end.

  This is also the CONCRETE 'how the owner actually reviews a pending
  application' procedure README documents -- `approve!`/`reject!`/
  `waitlist!` below are exactly what an operator runs from a REPL against
  real KV-exported pending applications (see README 'Human approval')."
  (:require [langgraph.graph :as g]
            [partners.store :as store]
            [partners.operation :as op]))

(def owner {:actor-id "cloud-itonami-partners" :approver "jun"})

(defn- govern! [actor tid app-id]
  (g/run* actor {:request {:op :partner/govern :subject app-id} :context owner}
          {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by (:approver owner)}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid reason]
  (g/run* actor {:approval {:status :rejected :by (:approver owner) :reason reason}}
          {:thread-id tid :resume? true}))

(defn- waitlist! [actor tid reason]
  (g/run* actor {:approval {:status :waitlisted :by (:approver owner) :reason reason}}
          {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== govern app-1 (clean, cloud-itonami-isic-6499/JPN, no prior grant) ==")
    (println (govern! actor "t1" "app-1"))
    (println "-- human owner reviews the ScreeningAdvisor rationale + PartnerGovernor verdict, approves --")
    (println (approve! actor "t1"))

    (println "== govern app-2 (unknown itonami-vertical-ref -> HARD hold, never reaches a human) ==")
    (println (govern! actor "t2" "app-2"))

    (println "== govern app-3 (cloud-itonami-isic-6511/ITA already granted -> HARD hold) ==")
    (println (govern! actor "t3" "app-3"))

    (println "== a 4th, otherwise-clean application the owner explicitly REJECTS ==")
    (let [id (op/intake! db {:applicant-name "R. Kim" :applicant-contact "rkim@example.com"
                             :itonami-vertical-ref "cloud-itonami-isic-6910"
                             :territory {:country "KOR"} :pitch "Corporate law background, Seoul."
                             :capital-tier "tier-2" :language "ko"
                             :submitted-at "2026-07-19T00:00:00Z"})]
      (println (govern! actor "t4" id))
      (println (reject! actor "t4" "insufficient licensing evidence")))

    (println "== a 5th, otherwise-clean application the owner WAITLISTS ==")
    (let [id (op/intake! db {:applicant-name "A. Silva" :applicant-contact "asilva@example.com"
                             :itonami-vertical-ref "cloud-itonami-isic-7810"
                             :territory {:country "BRA"} :pitch "Staffing-agency operator, Sao Paulo."
                             :capital-tier "tier-1" :language "pt"
                             :submitted-at "2026-07-19T00:00:00Z"})]
      (println (govern! actor "t5" id))
      (println (waitlist! actor "t5" "capital tier below program minimum, revisit next quarter")))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft territory-grant records ==")
    (doseq [r (store/territory-grants db)] (println r))))
