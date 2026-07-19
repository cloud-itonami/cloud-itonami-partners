(ns partners.advisor
  "ScreeningAdvisor -- the *contained intelligence node*.

  It reads a `partner-application` (name, contact, itonami-vertical-ref,
  territory, pitch, capital-tier, language) and drafts a screening
  proposal: a 0..1 score and a human-readable rationale for whether this
  applicant looks like a plausible local operator for the requested
  vertical/territory. CRITICAL: it is a smart-but-untrusted advisor. It
  NEVER decides approve/reject/waitlist and it NEVER checks territory
  exclusivity, required-field completeness, or catalog membership --those
  are `partners.governor`'s job, independently, downstream, on real store
  data (never trusting this proposal's self-reported claims). Every output
  is censored by `partners.governor` before anything commits, and
  `:territory/grant-recorded` (minting a CACAO identity + granting a
  territory) NEVER auto-commits at any point -- see README 'Human
  approval': it always reaches a human via `interrupt-before` once the
  governor is clean.

  Like `vcfund.ddllm`/`talent.hrllm`, `mock-advisor` is a deterministic mock
  so the actor graph runs offline and the governor contract is exercised
  end-to-end. In production `llm-advisor` calls a real LLM (kotoba-llm or
  equivalent, resolved via the `murakumo-main` alias per CLAUDE.md -- never
  a hardcoded concrete model id) with the same proposal shape.

  Proposal shape:
    {:summary    str            ; human-facing draft finding
     :rationale  str             ; why -- the human approver reads this
     :cites      [kw|str ..]     ; fields the advisor actually used
     :score      0..1            ; plausibility score, NOT a decision
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.set :as set]
            [clojure.string :as str]
            [partners.catalog :as catalog]
            [partners.store :as store]
            [langchain.model :as model]))

(defn- score-pitch
  "A deliberately simple, explainable heuristic (never a hidden score): a
  longer, more specific pitch mentioning the vertical's own domain
  vocabulary reads as more plausible than a one-line pitch. This is NOT a
  decision -- PartnerGovernor never even looks at this number for anything
  but display; it only gates on catalog membership, territory exclusivity,
  required-field completeness and non-discrimination (see
  `partners.governor`)."
  [pitch vertical-title]
  (let [len-score (min 1.0 (/ (count (str pitch)) 400.0))
        title-words (set (map str/lower-case (str/split (str vertical-title) #"\s+")))
        pitch-words (set (map str/lower-case (str/split (str pitch) #"\s+")))
        overlap (count (set/intersection title-words pitch-words))
        overlap-score (min 1.0 (/ overlap 3.0))]
    (double (max 0.05 (min 1.0 (+ (* 0.7 len-score) (* 0.3 overlap-score)))))))

(defn- screen-application
  [db {:keys [subject]}]
  (let [a (store/application db subject)
        vertical (catalog/describe (:itonami-vertical-ref a))
        known? (some? vertical)
        pitch (:pitch a)
        score (if known? (score-pitch pitch (:title vertical)) 0.0)]
    {:summary (if known?
                ;; portable JVM/CLJS numeric round -- avoids `format`
                ;; (JVM-only, absent from cljs.core) purely to render a
                ;; 2-decimal score for the human-facing summary string.
                (str (:applicant-name a) " -> " (:itonami-vertical-ref a)
                     " (" (get-in a [:territory :country]) ") screening score "
                     (/ (Math/round (* 100.0 score)) 100.0))
                (str (:applicant-name a) " -> unknown itonami-vertical-ref "
                     (:itonami-vertical-ref a) " (not in partners.catalog)"))
     :rationale (if known?
                  (str "Pitch length + vertical-vocabulary overlap heuristic against '"
                       (:title vertical) "'. This is a PROPOSAL ONLY -- PartnerGovernor"
                       " independently re-checks catalog membership, territory"
                       " exclusivity and required fields; a human makes the actual"
                       " approve/reject/waitlist call.")
                  "itonami-vertical-ref does not resolve in partners.catalog/verticals -- PartnerGovernor will HOLD this regardless of any score.")
     :cites (cond-> [:pitch] known? (conj :itonami-vertical-ref))
     :score score
     :confidence (if known? 0.85 0.3)}))

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the heuristic above). Default everywhere --
  offline, explainable, zero external dependency."
  []
  (reify Advisor (-advise [_ st req] (screen-application st req))))

(def ^:private system-prompt
  (str "You are the screening advisor for a program that recruits individual"
       " human local operators (\"partners\") for itonami business verticals in"
       " specific territories. Given ONE partner-application's facts, return"
       " EXACTLY ONE EDN map, no preamble, no markdown fence. Keys:"
       " :summary(human-facing draft) :rationale(why, grounded ONLY in the"
       " given facts) :cites(vector of fact keys you actually used)"
       " :score(0.0-1.0 plausibility, NOT a decision) :confidence(0.0-1.0)."
       " You NEVER decide approve/reject/waitlist -- an independent governor"
       " and a human do that. You NEVER invent facts not present in the input"
       " (no fabricated credentials, licenses, or territory law)."))

(defn- facts-for [st {:keys [subject]}]
  (let [a (store/application st subject)]
    {:application a
     :vertical (catalog/describe (:itonami-vertical-ref a))}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe zero-score noop so PartnerGovernor's downstream catalog/
  territory checks are the ones that actually gate -- an LLM hiccup can
  never auto-approve or auto-reject a real territory grant (only a human,
  via `interrupt-before`, ever does either once the governor is clean)."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :score #(if (number? %) (double %) 0.0))
          (update :confidence #(if (number? %) (double %) 0.0)))
      {:summary "Could not parse advisor response" :rationale (str content)
       :cites [] :score 0.0 :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference).
  `chat-model` should resolve via CLAUDE.md's `murakumo-main` alias
  convention (env/arg override -> alias resolution -> endpoint-only
  fallback), never a hardcoded concrete model id -- see README 'Advisor'."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "application-id: " (:subject req)
                                              "\nfacts: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the ledger regardless of
  the eventual disposition (screening always logs, even for applications
  the governor later holds)."
  [request proposal]
  {:t :advisor-proposal
   :op :partner/screen
   :subject (:subject request)
   :summary (:summary proposal)
   :rationale (:rationale proposal)
   :cites (:cites proposal)
   :score (:score proposal)
   :confidence (:confidence proposal)})
