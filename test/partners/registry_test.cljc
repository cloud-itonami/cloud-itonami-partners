(ns partners.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [partners.registry :as registry]))

(deftest register-application-validates-and-builds
  (testing "happy path"
    (let [{:strs [record application_id]}
          (registry/register-application
           {:applicant-name "Jane" :applicant-contact "jane@example.com"
            :itonami-vertical-ref "cloud-itonami-isic-6499"
            :territory {:country "JPN"} :pitch "10 years VC experience."
            :capital-tier "tier-2" :language "en" :sequence 0})]
      (is (= "PARTNER-APP-00000000" application_id))
      (is (= application_id (get record "record_id")))
      (is (= "partner-application-draft" (get record "kind")))
      (is (true? (get record "immutable")))))
  (testing "required fields"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (registry/register-application {:applicant-contact "a@b.com" :itonami-vertical-ref "x"
                                                  :territory {:country "JPN"} :pitch "p" :sequence 0})))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (registry/register-application {:applicant-name "A" :applicant-contact "a@b.com"
                                                  :itonami-vertical-ref "x" :territory {:country "JPN"}
                                                  :pitch "p" :sequence -1})))))

(deftest register-screening-result-validates
  (let [{:strs [record]} (registry/register-screening-result "app-1" 0.7 "reasoning" "mock-advisor")]
    (is (= 0.7 (get record "llm_score")))
    (is (= "screening-result" (get record "kind"))))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (registry/register-screening-result "app-1" "not-a-number" "r" "m"))))

(deftest register-territory-grant-validates-and-builds
  (let [{:strs [record certificate grant_id]}
        (registry/register-territory-grant "cloud-itonami-isic-6499" {:country "JPN"} "app-1" 0 "2026-07-19T00:00:00Z")]
    (is (= "TERRITORY-GRANT-00000000" grant_id))
    (is (= grant_id (get record "record_id")))
    (is (= "territory-grant-draft" (get record "kind")))
    (is (= "draft-unsigned-non-binding" (get certificate "status"))
        "the certificate is explicitly UNSIGNED/non-binding (see README legal disclaimer)"))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (registry/register-territory-grant "" {:country "JPN"} "app-1" 0 "2026-07-19T00:00:00Z"))))

(deftest territory-key-normalizes
  (is (= (registry/territory-key "v" {:country "jpn" :region "  Tokyo  "})
         (registry/territory-key "v" {:country "JPN" :region "tokyo"})))
  (is (not= (registry/territory-key "v" {:country "JPN"})
            (registry/territory-key "v" {:country "USA"}))))

(deftest append-never-mutates-in-place
  (let [h1 []
        h2 (registry/append h1 {"record" {"a" 1}})]
    (is (= [] h1))
    (is (= [{"a" 1}] h2))))
