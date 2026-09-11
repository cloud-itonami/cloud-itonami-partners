(ns partners.store-contract-test
  "The Store contract, run against BOTH backends -- proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite (same pattern `underwriting.store-contract-test`
  uses for the sibling actor)."
  (:require [clojure.test :refer [deftest is testing]]
            [partners.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "田中 花子" (:applicant-name (store/application s "app-1"))))
      (is (= "cloud-itonami-isic-6499" (:itonami-vertical-ref (store/application s "app-1"))))
      (is (= {:country "JPN" :region nil} (:territory (store/application s "app-1"))))
      (is (= ["app-1" "app-2" "app-3"] (mapv :id (store/all-applications s))))
      (is (nil? (store/screening-result-of s "app-1")))
      (is (= [] (store/ledger s)))
      (is (zero? (store/next-sequence s))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :application/upsert :value {:id "app-1" :capital-tier "tier-3"}})
        (is (= "tier-3" (:capital-tier (store/application s "app-1"))))
        (is (= "田中 花子" (:applicant-name (store/application s "app-1"))) "name preserved"))
      (testing "status transition"
        (store/commit-record! s {:effect :application/status :path ["app-1"] :payload :screening})
        (is (= :screening (:status (store/application s "app-1")))))
      (testing "screening result commits and reads back"
        (store/commit-record! s {:effect :screening/set :path ["app-1"]
                                 :payload {:application-id "app-1" :llm-score 0.8}})
        (is (= {:application-id "app-1" :llm-score 0.8} (store/screening-result-of s "app-1"))))
      (testing "onboard drafts a territory-grant, advances application status + grant-sequence"
        (store/commit-record! s {:effect :territory/grant-recorded :path ["app-1"]
                                 :payload {:granted-at "2026-07-19T00:00:00Z"}})
        (is (= "cloud-itonami-isic-6499"
               (get (first (filter #(= "app-1" (get % "partner_application_id"))
                                    (store/territory-grants s)))
                    "itonami_vertical")))
        (is (= :approved (:status (store/application s "app-1")))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:t :a :disposition :commit})
        (store/append-ledger! s {:t :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/application s "nope")))
    (is (= [] (store/all-applications s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/territory-grants s)))
    (is (zero? (store/next-sequence s)))
    (store/with-applications s {"x" {:id "x" :applicant-name "n" :applicant-contact "c"
                                     :itonami-vertical-ref "v" :territory {:country "JPN"}
                                     :pitch "p" :status :pending}})
    (is (= "n" (:applicant-name (store/application s "x"))))))
