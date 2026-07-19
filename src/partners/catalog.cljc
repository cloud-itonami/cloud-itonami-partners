(ns partners.catalog
  "The itonami-vertical catalog `partners.governor`'s hard check #3 resolves
  `itonami-vertical-ref` against, and the public application form's dropdown
  renders from -- see `web/generate.cljs`.

  HONEST, VERIFIED, NON-FABRICATED, and DELIBERATELY A SUBSET (ADR-2607194000
  Non-goals: 'do not attempt to launch/catalog all 1120 verticals at once').
  This actor is built inside `orgs/cloud-itonami/` (west-managed, ~1120
  sibling repos: `cloud-itonami-isic-####` / `cloud-itonami-isco-####` /
  `cloud-itonami-assoc-*` / `cloud-itonami-lei-*` / `cloud-itonami-
  municipality-*` / `cloud-itonami-iso3166-*` / `cloud-itonami-unspsc-*` /
  `cloud-itonami-cofog-*` / `cloud-itonami-gtin-*` / `cloud-itonami-jsic-*` /
  `cloud-itonami-hygiene-*` / `cloud-itonami-regulatory-*`), and only TWO of
  those prefixes name an operable itonami BUSINESS a human partner could
  actually run day to day:

    - `cloud-itonami-isic-####` -- UN ISIC Rev.4/5 industry-classified
      businesses (the `Advisor ⊣ Governor` actor-pattern operating
      companies -- VC fund, life-insurer, CRM SaaS, credit lender, ...).
    - `cloud-itonami-isco-####` -- ISCO-08 occupation-classified
      solo/fractional-practitioner businesses (e.g. an independent HR
      manager's outsourced HR-as-a-service practice).

  Every other prefix is a DATA partnership/reference catalog, not a
  franchisable business, and is deliberately EXCLUDED here:
    - `assoc-*`        -- trade-association data-sharing integrations (the
                          association is cloud-itonami's counterparty, not
                          a business a partner would run).
    - `lei-*`           -- individual Legal Entity Identifier records.
    - `municipality-*`  -- city/municipal open-data integrations.
    - `iso3166-*`       -- country/regulator reference data (ISO 3166 country
                          entries, plus national-ministry sub-entries).
    - `unspsc-*` / `cofog-*` / `gtin-*` / `jsic-*` / `hygiene-*` /
      `regulatory-*` -- classification/reference-data catalogs, same reason.

  Within the (still ~643-repo) isic-#### + isco-#### set, this catalog seeds
  a SMALL, VERIFIED subset -- the entries below were chosen because each
  repo's own `README.md` (read directly, not guessed) opens with an
  \"Open Business/Occupation Blueprint\" statement citing a real ISIC/ISCO
  code and a concrete, already-implemented (not merely scaffolded) business
  description. `:description` below is a close paraphrase of that README's
  own opening description -- never invented. Extending this catalog is
  additive: verify the next repo's README the same way, add one map, done --
  never fabricate a vertical's description or code to make coverage look
  bigger (the same honesty discipline `vcfund.facts`/`underwriting.facts`
  use for jurisdiction coverage).")

(def verticals
  "vertical-ref -> {:code :standard :title :description :repo}.
  `:vertical-ref` (the map key) is what `partner-application/itonami-
  vertical-ref` stores and what `partners.governor`'s
  `known-vertical-violations` resolves against."
  {"cloud-itonami-isic-6399"
   {:code "6399" :standard "ISIC Rev.4/5" :repo "cloud-itonami/cloud-itonami-isic-6399"
    :title "Meta job-search (other information service activities n.e.c.)"
    :description "Job-posting aggregation, verification, publication into a public search index, and freshness-driven delisting."}

   "cloud-itonami-isic-6310"
   {:code "6310" :standard "ISIC Rev.5" :repo "cloud-itonami/cloud-itonami-isic-6310"
    :title "HR / talent-management SaaS (computing infrastructure, data processing, hosting and related activities)"
    :description "An HR/talent-management SaaS business any qualified operator can fork, deploy, run, improve and sell."}

   "cloud-itonami-isic-7810"
   {:code "7810" :standard "ISIC Rev.5" :repo "cloud-itonami/cloud-itonami-isic-7810"
    :title "Employment agency"
    :description "Candidate intake, per-jurisdiction anti-discrimination/work-authorization regulatory assessment, candidate matching and candidate placement/follow-up."}

   "cloud-itonami-isic-5820"
   {:code "5820" :standard "ISIC Rev.4" :repo "cloud-itonami/cloud-itonami-isic-5820"
    :title "Commercial CRM / subscription-commerce SaaS (software publishing)"
    :description "A commercial CRM / subscription-commerce SaaS platform business -- the Salesforce/HubSpot class of business."}

   "cloud-itonami-isic-6499"
   {:code "6499" :standard "ISIC Rev.4/5" :repo "cloud-itonami/cloud-itonami-isic-6499"
    :title "Venture capital fund (other financial service activities n.e.c., own-account investing)"
    :description "Deal pipeline/sourcing, LP subscription intake, deal due diligence, versioned term-sheet negotiation, capital calls, Investment Committee capital deployment, portfolio-company monitoring, and exit distribution back to LPs."}

   "cloud-itonami-isic-6492"
   {:code "6492" :standard "ISIC Rev.5" :repo "cloud-itonami/cloud-itonami-isic-6492"
    :title "Consumer/commercial credit granting (other credit granting)"
    :description "Loan-application intake, creditworthiness screening, underwriting approval and loan disbursement, for a qualified, licensed lender."}

   "cloud-itonami-isco-1212"
   {:code "1212" :standard "ISCO-08" :repo "cloud-itonami/cloud-itonami-isco-1212"
    :title "Independent/fractional HR manager (Human Resource Managers)"
    :description "Outsourced 'HR-as-a-service' practice for small and mid-size employers that cannot support a full in-house HR department -- org-chart, employee-record and personnel-action management."}

   "cloud-itonami-isic-6511"
   {:code "6511" :standard "ISIC Rev.5" :repo "cloud-itonami/cloud-itonami-isic-6511"
    :title "Life insurance underwriting"
    :description "A life-insurance underwriting/policy-binding execution business, for a qualified, licensed operator."}

   "cloud-itonami-isic-8291"
   {:code "8291" :standard "ISIC Rev.4" :repo "cloud-itonami/cloud-itonami-isic-8291"
    :title "Corporate/compliance intelligence SaaS (collection agencies and credit bureaus)"
    :description "A corporate/compliance intelligence SaaS -- the D&B / Moody's Orbis(BvD) / Refinitiv World-Check class of business."}

   "cloud-itonami-isic-6910"
   {:code "6910" :standard "ISIC Rev.5" :repo "cloud-itonami/cloud-itonami-isic-6910"
    :title "Company incorporation / registration-agent services (legal activities)"
    :description "A global company-formation business, for a qualified, licensed operator."}})

(defn known-vertical? [vertical-ref]
  (contains? verticals vertical-ref))

(defn describe [vertical-ref]
  (get verticals vertical-ref))

(defn options
  "Sorted `[vertical-ref {...}]` pairs -- what the public form's dropdown
  (`web/generate.cljs`) renders, one `<option>` per entry, labeled with the
  vertical's own verified `:title`/`:description`, never invented text."
  []
  (sort-by first verticals))

(defn coverage
  "Honest coverage note, the same discipline `vcfund.facts/coverage` /
  `underwriting.facts/coverage` use -- never claim this is the full fleet."
  []
  {:catalogued (count verticals)
   :note (str "cloud-itonami-partners v1: " (count verticals)
              " verified itonami-isic/isco business verticals seeded as a"
              " starting catalog, out of ~643 isic-####/isco-#### repos in"
              " orgs/cloud-itonami/ (and ~1120 repos total, most of which"
              " are data catalogs, not franchisable businesses -- see this"
              " namespace's own docstring). Extending coverage is additive:"
              " verify the next repo's README, add one map entry, never"
              " fabricate a vertical.")})

(defn valid-territory?
  "A territory is `{:country <ISO 3166-1 alpha-2 OR alpha-3, upper-case>
  :region <optional free-text>}` -- alpha-3 (\"JPN\"/\"USA\"/\"ITA\", the
  same convention `vcfund.facts`/`underwriting.facts` use for
  `:jurisdiction`) is the expected default; alpha-2 is also accepted. This
  catalog does not maintain a full ISO 3166 table (unlike
  `orgs/cloud-itonami/cloud-itonami-iso3166-*`, out of scope for this actor
  per its own catalog exclusion above) -- it only checks the WIRE SHAPE
  (two- or three-letter upper-case code), the same minimal, non-fabricating
  discipline as the rest of this namespace. A real deployment wanting full
  ISO 3166 membership validation should consult the `cloud-itonami-iso3166-*`
  catalog repos rather than have this actor duplicate that data."
  [{:keys [country]}]
  (boolean (and (string? country) (re-matches #"[A-Z]{2,3}" country))))
