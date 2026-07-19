(ns partners.governor
  "PartnerGovernor -- the independent gate that earns the ScreeningAdvisor
  the right to move an application toward a territory grant. The advisor
  has no fiduciary/legal authority to grant an exclusive real-world
  territory right to a stranger who filled in a web form, and no business
  being the one that decides it, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD -- the partners analog of
  `vcfund.governor`'s InvestmentCommitteeGovernor and
  `underwriting.governor`'s UnderwritingGovernor. Style (independent,
  every-violation-collected, human-readable `:rule`/`:detail` pairs) follows
  `cloud-itonami.edge.jobs-governor`'s docstring convention.

  Single invariant (CLAUDE.md Actors / ADR-2607194000): the governor never
  lets the actor perform a write/disclosure/territory-grant it has
  rejected. `:territory/grant-recorded` (minting a CACAO identity for the
  partner + granting the territory) is high-stakes REGARDLESS of how clean
  the application is -- see `partners.operation`: unlike `vcfund`/
  `underwriting`'s phased rollout tables, EVERY governor-clean application
  here still always reaches a human via `interrupt-before` (ADR-2607194000
  step 3: 'territory 付与はオーナー最終承認を必須とする... 既存 actor の
  「耐空性サインオフ」相当の人間ゲートを踏襲する'). There is no rollout
  phase in which this becomes autonomous, by design, permanently -- so
  unlike `vcfund.phase`/`talent.phase`, this actor has no `phase.cljc`: a
  phase table's only job is choosing WHICH clean ops may skip the human, and
  for this actor's one real write, the answer is 'none, ever' -- see
  README 'Why no phase.cljc'.

  FOUR HARD checks, each independently checked (an application can violate
  more than one at once, and ALL violations are reported, not just the
  first -- a human approver reviewing a HOLD sees the complete list, not
  whichever check happened to run first):

    1. Territory exclusivity  -- does a `territory-grant` ALREADY exist for
                                 the SAME `(itonami-vertical, territory)`
                                 pair (`partners.store/territory-grants`,
                                 normalized via `partners.registry/
                                 territory-key`)? Re-checked from live store
                                 data, never trusting the application's own
                                 claim that a territory is open.
    2. Required fields        -- applicant-name, applicant-contact,
                                 itonami-vertical-ref, territory (with a
                                 valid-shaped country code), and pitch must
                                 ALL be present and non-blank
                                 (`partners.catalog/valid-territory?`).
    3. Known vertical          -- `itonami-vertical-ref` must resolve to a
                                 REAL, catalogued itonami business
                                 (`partners.catalog/known-vertical?`) -- an
                                 applicant cannot be granted a territory for
                                 a fabricated or unlisted business.
    4. Screening parse failure -- the ScreeningAdvisor proposal itself must
                                 actually be a well-formed proposal (a
                                 non-numeric/`nil` `:score` means the
                                 advisor's output could not be trusted at
                                 all, e.g. a malformed real-LLM response --
                                 see `partners.advisor/parse-proposal`'s own
                                 fail-closed default). A malformed proposal
                                 is treated as a hold, never silently
                                 defaulted to a passing score.

  TWO structural (non-runtime) invariants that this governor upholds BY
  CONSTRUCTION rather than as a data check, verified by
  `test/partners/governor_contract_test.cljc`:

    5. Fairness / non-discrimination -- this file contains NO rule that
                                 reads `:applicant-name`, nationality,
                                 language, or any other identity/protected
                                 attribute as a basis for rejection. The
                                 ONLY grounds `check` can ever hold on are
                                 rules 1-4 above (objective: an existing
                                 grant, missing fields, an unlisted
                                 vertical, or a malformed proposal). The
                                 contract test asserts two applications
                                 identical except for name/language/
                                 nationality-coded contact fields produce
                                 IDENTICAL verdicts.
    6. Minimal disclosure     -- `partners.store`'s `partner-application`
                                 field-spec (see `partners.store`) carries
                                 ONLY the seven ADR-2607194000 fields
                                 (name, contact, vertical-ref, territory,
                                 pitch, capital-tier, language) -- this
                                 governor has no code path that could even
                                 reference an eighth field, because none is
                                 collected. The contract test asserts the
                                 application field-spec's key set is
                                 EXACTLY that set, so an accidental future
                                 field addition fails CI rather than
                                 silently over-collecting."
  (:require [partners.catalog :as catalog]
            [partners.registry :as registry]
            [partners.store :as store]))

(defn- blank? [v] (or (nil? v) (= v "")))

(defn- territory-grants-keys
  "The set of `(itonami-vertical, territory)` keys ALREADY granted, read
  live from the store -- never trusted from the application itself."
  [st]
  (into #{}
        (map (fn [g] (registry/territory-key (get g "itonami_vertical") (get g "territory"))))
        (store/territory-grants st)))

(defn- territory-exclusivity-violations
  [{:keys [op subject]} st]
  (when (= op :partner/govern)
    (let [a (store/application st subject)
          key-now (registry/territory-key (:itonami-vertical-ref a) (:territory a))
          existing (territory-grants-keys st)]
      (when (contains? existing key-now)
        [{:rule :territory-already-granted
          :detail (str "既に territory grant が存在する itonami-vertical/territory への重複応募: "
                       (pr-str key-now))}]))))

(defn- required-field-violations
  [{:keys [op subject]} st]
  (when (= op :partner/govern)
    (let [a (store/application st subject)
          missing (cond-> []
                    (blank? (:applicant-name a)) (conj :applicant-name)
                    (blank? (:applicant-contact a)) (conj :applicant-contact)
                    (blank? (:itonami-vertical-ref a)) (conj :itonami-vertical-ref)
                    (not (catalog/valid-territory? (:territory a))) (conj :territory)
                    (blank? (:pitch a)) (conj :pitch))]
      (when (seq missing)
        [{:rule :required-field-missing
          :detail (str "必須項目が欠落/不正: " (pr-str missing))}]))))

(defn- known-vertical-violations
  [{:keys [op subject]} st]
  (when (= op :partner/govern)
    (let [a (store/application st subject)]
      (when-not (catalog/known-vertical? (:itonami-vertical-ref a))
        [{:rule :unknown-itonami-vertical
          :detail (str "itonami-vertical-ref が partners.catalog に存在しない実在検証不能なコード: "
                       (pr-str (:itonami-vertical-ref a)))}]))))

(defn- screening-parse-violations
  [{:keys [op]} proposal]
  (when (= op :partner/govern)
    (when-not (number? (:score proposal))
      [{:rule :screening-unparseable
        :detail "ScreeningAdvisor の proposal が well-formed でない (score が数値でない)"}])))

(defn check
  "Censors a ScreeningAdvisor proposal against the governor rules. Returns
   {:ok? bool :violations [..] :hard? bool}.

   ALL applications reach `:hard? true` (HOLD, terminal reject) or
   `:ok? true` (clean -> ALWAYS escalates to a human via `interrupt-before`,
   see `partners.operation` -- there is no auto-commit path for a territory
   grant at any point, unlike `vcfund`/`underwriting`'s phased rollout).
   `:escalate?` is therefore always the mirror of `:ok?` here (kept as an
   explicit key, matching the vcfund/underwriting governor result shape,
   for anyone building tooling against multiple cloud-itonami governors)."
  [request _context proposal st]
  (let [terr-v (territory-exclusivity-violations request st)
        req-v  (required-field-violations request st)
        vert-v (known-vertical-violations request st)
        scr-v  (screening-parse-violations request proposal)
        hard (into [] (concat terr-v req-v vert-v scr-v))]
    {:ok?        (empty? hard)
     :violations hard
     :hard?      (boolean (seq hard))
     :escalate?  (empty? hard)}))

(defn hold-fact
  "The audit fact written when an application is rejected (HOLD) --
  ALWAYS includes the FULL violation list (rule 5/6's every-violation-
  collected discipline), never truncated to the first hit."
  [request _context verdict]
  {:t :governor-hold
   :op (:op request)
   :subject (:subject request)
   :disposition :hold
   :basis (mapv :rule (:violations verdict))
   :violations (:violations verdict)})
