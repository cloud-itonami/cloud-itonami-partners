(ns partners.governor-contract-test
  "PartnerGovernor contract: every HARD check independently, PLUS the two
  structural invariants (fairness/non-discrimination and minimal-
  disclosure) `partners.governor`'s own docstring commits to (points 5/6) --
  verified here as data/contract assertions, not by inspection alone."
  (:require [clojure.test :refer [deftest is testing]]
            [partners.governor :as governor]
            [partners.store :as store]))

(def clean-proposal {:summary "s" :rationale "r" :cites [] :score 0.8 :confidence 0.85})

(deftest clean-application-is-ok
  (let [s (store/seed-db)
        v (governor/check {:op :partner/govern :subject "app-1"} {} clean-proposal s)]
    (is (true? (:ok? v)))
    (is (false? (:hard? v)))
    (is (true? (:escalate? v)))
    (is (= [] (:violations v)))))

(deftest unknown-vertical-is-hard-hold
  (let [s (store/seed-db)
        v (governor/check {:op :partner/govern :subject "app-2"} {} clean-proposal s)]
    (is (false? (:ok? v)))
    (is (true? (:hard? v)))
    (is (some #(= :unknown-itonami-vertical (:rule %)) (:violations v)))))

(deftest territory-already-granted-is-hard-hold
  (let [s (store/seed-db)
        v (governor/check {:op :partner/govern :subject "app-3"} {} clean-proposal s)]
    (is (true? (:hard? v)))
    (is (some #(= :territory-already-granted (:rule %)) (:violations v)))))

(deftest required-field-missing-is-hard-hold
  (let [s (store/empty-db)
        _ (store/commit-record! s {:effect :application/upsert
                                   :value {:id "bad-1" :applicant-name "" :applicant-contact nil
                                           :itonami-vertical-ref "cloud-itonami-isic-6499"
                                           :territory {:country "not-a-code"} :pitch nil
                                           :status :pending}})
        v (governor/check {:op :partner/govern :subject "bad-1"} {} clean-proposal s)]
    (is (true? (:hard? v)))
    (let [detail (:detail (first (filter #(= :required-field-missing (:rule %)) (:violations v))))]
      (is (re-find #"applicant-name" detail))
      (is (re-find #"applicant-contact" detail))
      (is (re-find #"pitch" detail))
      (is (re-find #"territory" detail)))))

(deftest malformed-screening-proposal-is-hard-hold
  (let [s (store/seed-db)
        v (governor/check {:op :partner/govern :subject "app-1"} {} {:score nil} s)]
    (is (true? (:hard? v)))
    (is (some #(= :screening-unparseable (:rule %)) (:violations v)))))

(deftest all-violations-collected-not-just-first
  ;; bad-2 is BOTH an unknown vertical AND missing required fields at once
  ;; -- the governor must report BOTH rules, matching the docstring's
  ;; "an application can violate more than one at once, and ALL violations
  ;; are reported" discipline (mirrors vcfund.governor / jobs-governor).
  (let [s (store/empty-db)]
    (store/commit-record! s {:effect :application/upsert
                             :value {:id "bad-2" :applicant-name "" :applicant-contact ""
                                     :itonami-vertical-ref "cloud-itonami-isic-9999"
                                     :territory {:country "?"} :pitch ""
                                     :status :pending}})
    (let [v (governor/check {:op :partner/govern :subject "bad-2"} {} clean-proposal s)
          rules (set (map :rule (:violations v)))]
      (is (contains? rules :unknown-itonami-vertical))
      (is (contains? rules :required-field-missing))
      (is (>= (count (:violations v)) 2)))))

;; ---------------------- structural invariant 5: fairness ----------------------

(deftest fairness-non-discrimination
  (testing "two applications identical except for name/contact/language produce IDENTICAL verdicts"
    (let [s (store/empty-db)
          base {:itonami-vertical-ref "cloud-itonami-isic-6499"
                :territory {:country "DEU"} :pitch "Equivalent professional pitch."
                :capital-tier "tier-2" :status :pending}]
      (store/commit-record! s {:effect :application/upsert
                               :value (merge base {:id "fair-a" :applicant-name "Alice Wong"
                                                   :applicant-contact "alice@example.com" :language "en"})})
      (store/commit-record! s {:effect :application/upsert
                               :value (merge base {:id "fair-b" :applicant-name "محمد الأمين"
                                                   :applicant-contact "mohamed@example.eg" :language "ar"})})
      (let [va (governor/check {:op :partner/govern :subject "fair-a"} {} clean-proposal s)
            vb (governor/check {:op :partner/govern :subject "fair-b"} {} clean-proposal s)]
        (is (= (:ok? va) (:ok? vb)))
        (is (= (map :rule (:violations va)) (map :rule (:violations vb)))))))
  #?(:clj
     (testing "no governor rule is ever KEYED on an identity attribute (JVM-only source scan)"
       ;; a grep-level structural proof, not just a data-driven one: the
       ;; governor source contains no `:nationality`/`:race`/etc. KEYWORD
       ;; (the shape an actual data-driven check would need,
       ;; e.g. `(:nationality a)`) as a basis for any :rule. Deliberately
       ;; colon-prefixed so this does NOT false-positive on this file's OWN
       ;; prose docstring, which discusses these words in English without
       ;; ever using them as a keyword. JVM-only (`slurp`, absent from
       ;; cljs.core) -- the data-driven check above still runs portably.
       (let [src (slurp "src/partners/governor.cljc")]
         (doseq [forbidden [":nationality" ":race" ":ethnicity" ":religion" ":gender"]]
           (is (not (re-find (re-pattern forbidden) src))
               (str forbidden " must never appear in governor.cljc")))))))

;; -------------------- structural invariant 6: minimal disclosure --------------------

(deftest minimal-disclosure
  (let [applicant-supplied (into #{} (remove #{:id :submitted-at :status}) (keys store/app-spec))]
    (is (= #{:applicant-name :applicant-contact :itonami-vertical-ref
             :territory :pitch :capital-tier :language}
           applicant-supplied)
        "the application field-spec collects EXACTLY the ADR-2607194000 fields -- no more")))
