(ns partners.catalog-test
  (:require [clojure.test :refer [deftest is testing]]
            [partners.catalog :as catalog]))

(deftest catalog-is-honest-and-non-empty
  (testing "every seeded vertical carries a real ISIC/ISCO code + repo + verified description"
    (doseq [[ref {:keys [code standard title description repo]}] (catalog/options)]
      (is (re-matches #"cloud-itonami-(isic|isco)-\d{4}" ref) (str ref " has the expected repo-slug shape"))
      (is (re-matches #"\d{4}" code))
      (is (contains? #{"ISIC Rev.4" "ISIC Rev.5" "ISIC Rev.4/5" "ISCO-08"} standard))
      (is (not-empty title))
      (is (not-empty description))
      (is (re-matches #"cloud-itonami/cloud-itonami-(isic|isco)-\d{4}" repo))))
  (testing "coverage is reported honestly, never as full-fleet"
    (let [{:keys [catalogued note]} (catalog/coverage)]
      (is (= catalogued (count catalog/verticals)))
      (is (re-find #"starting catalog" note)))))

(deftest known-vertical?-resolves-only-catalogued-refs
  (is (true? (catalog/known-vertical? "cloud-itonami-isic-6499")))
  (is (false? (catalog/known-vertical? "cloud-itonami-isic-9999")))
  (is (false? (catalog/known-vertical? "cloud-itonami-assoc-0126-idn-gapki")))
  (is (false? (catalog/known-vertical? nil))))

(deftest valid-territory?-checks-wire-shape-only
  (is (true? (catalog/valid-territory? {:country "JPN"})))
  (is (true? (catalog/valid-territory? {:country "US" :region "CA"})))
  (is (false? (catalog/valid-territory? {:country "japan"})))
  (is (false? (catalog/valid-territory? {:country nil})))
  (is (false? (catalog/valid-territory? {})))
  (is (false? (catalog/valid-territory? nil))))
