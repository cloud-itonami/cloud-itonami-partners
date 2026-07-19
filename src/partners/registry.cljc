(ns partners.registry
  "Pure-function partner-application / screening-result / territory-grant
  record construction -- an append-only draft-record builder, the
  `partners` analog of `vcfund.registry` / `underwriting.registry`.

  There is no single international identifier standard for a 'territory
  grant' record -- every operator/franchisor assigns its own reference
  format. This namespace does NOT invent one; it builds a monotonically
  increasing sequence number and validates the record's required fields,
  the same honest, non-fabricating discipline `vcfund.registry` uses.

  Pure data + pure functions -- no I/O, no network call, no CACAO minting
  (that is `partners.cacao`, invoked only by `partners.operation`'s
  `:onboard` node on an APPROVED application). This namespace builds the
  RECORD an operator would keep, not the act of granting a territory or
  minting an identity itself (both are always human-gated -- see README
  'Human approval')."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED and explicitly
  NON-BINDING -- see README's legal-disclaimer discussion. Signature /
  binding legal effect is a separately reviewed, formal contract's act, not
  this actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned-non-binding"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-application
  "Validate + construct a partner-application intake DRAFT. Pure function --
  does not touch any real CRM/territory-grant system. `capital-tier` and
  `language` are free-form self-declarations (minimal-disclosure: this
  namespace does not validate them against any external identity/credit
  system -- see `partners.governor`'s minimal-disclosure invariant)."
  [{:keys [applicant-name applicant-contact itonami-vertical-ref territory
           pitch capital-tier language sequence]}]
  (when-not (and applicant-name (not= applicant-name ""))
    (throw (ex-info "application: applicant-name required" {})))
  (when-not (and applicant-contact (not= applicant-contact ""))
    (throw (ex-info "application: applicant-contact required" {})))
  (when-not (and itonami-vertical-ref (not= itonami-vertical-ref ""))
    (throw (ex-info "application: itonami-vertical-ref required" {})))
  (when-not (map? territory)
    (throw (ex-info "application: territory must be a map {:country ..}" {})))
  (when-not (and pitch (not= pitch ""))
    (throw (ex-info "application: pitch required" {})))
  (when (< sequence 0)
    (throw (ex-info "application: sequence must be >= 0" {})))
  (let [id (str "PARTNER-APP-" (zero-pad sequence 8))]
    {"record" {"record_id" id
               "kind" "partner-application-draft"
               "applicant_name" applicant-name
               "applicant_contact" applicant-contact
               "itonami_vertical_ref" itonami-vertical-ref
               "territory" territory
               "pitch" pitch
               "capital_tier" capital-tier
               "language" language
               "immutable" true}
     "application_id" id}))

(defn register-screening-result
  "Validate + construct a screening-result DRAFT -- the ScreeningAdvisor's
  proposal-only score + rationale, persisted for audit but NEVER itself a
  decision (see `partners.governor`/`partners.operation`)."
  [application-id llm-score llm-rationale advisor-model-ref]
  (when-not (and application-id (not= application-id ""))
    (throw (ex-info "screening-result: application-id required" {})))
  (when-not (number? llm-score)
    (throw (ex-info "screening-result: llm-score must be a number" {})))
  {"record" {"record_id" (str application-id "#screening")
             "kind" "screening-result"
             "application_id" application-id
             "llm_score" llm-score
             "llm_rationale" llm-rationale
             "advisor_model_ref" advisor-model-ref
             "immutable" true}})

(defn register-territory-grant
  "Validate + construct a territory-grant DRAFT -- the record
  `partners.governor`'s territory-exclusivity check (rule 1) reads for every
  future application against the SAME `(itonami-vertical, territory)` pair.
  Only ever constructed by `partners.operation`'s `:onboard` node, itself
  only reachable after explicit human approval (`interrupt-before`) -- see
  README 'Human approval'. UNSIGNED/non-binding per `unsigned-certificate`:
  this is the internal record of an operational territory assignment, not
  the formal franchise contract itself (out of scope for v1, see ADR-
  2607194000 Non-goals)."
  [itonami-vertical territory partner-application-id sequence granted-at]
  (when-not (and itonami-vertical (not= itonami-vertical ""))
    (throw (ex-info "territory-grant: itonami-vertical required" {})))
  (when-not (map? territory)
    (throw (ex-info "territory-grant: territory must be a map" {})))
  (when-not (and partner-application-id (not= partner-application-id ""))
    (throw (ex-info "territory-grant: partner-application-id required" {})))
  (when (< sequence 0)
    (throw (ex-info "territory-grant: sequence must be >= 0" {})))
  (when-not (and granted-at (not= granted-at ""))
    (throw (ex-info "territory-grant: granted-at required" {})))
  (let [grant-id (str "TERRITORY-GRANT-" (zero-pad sequence 8))]
    {"record" {"record_id" grant-id
               "kind" "territory-grant-draft"
               "itonami_vertical" itonami-vertical
               "territory" territory
               "partner_application_id" partner-application-id
               "granted_at" granted-at
               "immutable" true}
     "certificate" (unsigned-certificate "TerritoryGrantCertificate" partner-application-id grant-id)
     "grant_id" grant-id}))

(defn territory-key
  "Normalized `(itonami-vertical, territory)` key for exclusivity lookups --
  `partners.governor`'s territory-exclusivity check and this registry's
  `register-territory-grant` must agree on the SAME normalization
  (upper-cased country code + trimmed region), or a grant could silently
  fail to collide with a semantically-identical future request."
  [itonami-vertical {:keys [country region]}]
  [itonami-vertical
   (some-> country str/upper-case)
   (some-> region str/trim str/lower-case not-empty)])

(defn append
  "Append a record, returning a NEW list (never mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
