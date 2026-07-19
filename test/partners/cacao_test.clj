(ns partners.cacao-test
  "partners.cacao is the JVM-only identity boundary (SecureRandom seed
  generation, .{slug}/identity.edn persistence -- CLAUDE.md's kotoba-server
  convention, gitignored, never committed). Every test here uses a
  disposable, never-before-seen application-id (never a real partner) and
  deletes its `.partner-<slug>/` directory afterward -- no real secret is
  ever written, logged, or left behind, mirroring
  `cloud-itonami.identity-test`'s own cleanup discipline. The actor's OWN
  identity (`load-or-create-identity!`, no args, fixed slug
  `cloud-itonami-partners`) is exercised too and ALSO cleaned up afterward."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ed25519.core :as ed]
            [ipns.core :as ipns]
            [partners.cacao :as cacao]))

(defn- fresh-application-id []
  (str "coverage-tmp-app-" (System/nanoTime) "-" (rand-int 1000000)))

(defn- delete-dir! [^java.io.File d]
  (when (.exists d)
    (doseq [f (reverse (file-seq d))] (.delete f))))

(deftest actor-own-identity-generates-persists-and-reloads
  (let [dir (io/file (str "." cacao/actor-slug))
        already-existed? (.exists dir)]
    (let [first-id (cacao/load-or-create-identity!)
          second-id (cacao/load-or-create-identity!)]
      (testing "returns {:slug :seed-hex :did :graph}"
        (is (= cacao/actor-slug (:slug first-id)))
        (is (re-matches #"[0-9a-f]{64}" (:seed-hex first-id)))
        (is (= (ed/did-key-from-seed-hex (:seed-hex first-id)) (:did first-id))
            "did is actually derived from the persisted seed"))
      (testing "graph is derived from the seed, not independent"
        (is (= (ipns/pubkey->name (ed/pubkey-from-seed (ed/unhex (:seed-hex first-id))))
               (:graph first-id))))
      (testing "a second call reloads the SAME persisted identity, never regenerates"
        (is (= first-id second-id))))
    (when-not already-existed? (delete-dir! dir))))

(deftest mint-for-partner-is-independent-per-application-and-never-returns-the-seed
  (let [app-a (fresh-application-id)
        app-b (fresh-application-id)]
    (try
      (let [ida (cacao/mint-for-partner! app-a {:id app-a})
            idb (cacao/mint-for-partner! app-b {:id app-b})]
        (testing "return shape is ONLY public identifiers -- no :seed-hex leaks to the caller"
          (is (= #{:did :graph} (set (keys ida))))
          (is (nil? (get ida :seed-hex))))
        (testing "two different applications mint two DIFFERENT, independent identities"
          (is (not= (:did ida) (:did idb)))
          (is (not= (:graph ida) (:graph idb))))
        (testing "the actor's own identity is a THIRD, still-different identity"
          (let [own (cacao/load-or-create-identity!)]
            (is (not= (:did own) (:did ida)))
            (is (not= (:did own) (:did idb)))))
        (testing "minting again for the SAME application-id reloads the SAME identity (idempotent onboard)"
          (is (= ida (cacao/mint-for-partner! app-a {:id app-a})))))
      (finally
        (delete-dir! (io/file (str ".partner-" app-a)))
        (delete-dir! (io/file (str ".partner-" app-b)))))))

(deftest mint-for-partner-rejects-a-path-traversal-application-id
  ;; application-id ultimately flows into a filesystem path
  ;; (.partner-<slug>/identity.edn) -- partner-slug's char-substitution
  ;; normalization must make traversal structurally impossible rather than
  ;; relying on every future caller to pre-sanitize (same posture
  ;; `cloud-itonami.identity`'s actor-pattern regression test enforces).
  (let [malicious "../../etc/passwds-canary"]
    (try
      (let [id (cacao/mint-for-partner! malicious {:id malicious})]
        (is (not (.exists (io/file "../../etc/passwds-canary" "identity.edn")))
            "no file was written outside this repo's own directory tree")
        (is (some? (:did id))))
      (finally
        ;; partner-slug replaces every non [a-zA-Z0-9_-] char with "-", so
        ;; the malicious id above normalizes to a single sandboxed dir --
        ;; clean it up by re-deriving the same normalization.
        (delete-dir! (io/file (str ".partner-"
                                   (str/replace malicious #"[^a-zA-Z0-9_-]" "-"))))))))
