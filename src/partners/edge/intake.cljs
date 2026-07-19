(ns partners.edge.intake
  "Cloudflare Pages Function for `POST /api/intake` -- the public partner-
  application intake endpoint (ADR-2607194000: 'a public web form ... POSTs
  to your intake endpoint, which runs intake -> screen -> govern').

  This Function does NOT run the full langgraph-clj StateGraph (that's
  `partners.operation`, JVM/portable-cljs, run by the repo owner from a
  REPL/CLI against a KV export -- see README 'Human approval'). It runs
  the STRUCTURAL SUBSET of PartnerGovernor's checks that make sense at
  public, unauthenticated, single-request intake time -- required fields
  present, `itonami-vertical-ref` resolves in `partners.catalog`, territory
  wire-shape valid, AND (best-effort, read-through KV) territory
  exclusivity against already-GRANTED territories -- and persists a
  structurally-valid submission to KV as a `pending` application for the
  owner to pull into the real actor. This mirrors
  `cloud-itonami.edge.register`'s shape (CACAO-gated claim -> KV write) but
  deliberately has NO CACAO gate on the submitter: an ordinary human filling
  a public form has no forged keypair, unlike that registry's
  developer-facing self-registration flow (ADR-2607194000's whole point is
  serving exactly this audience). `partners.governor`'s FULL check (which
  also re-validates the ScreeningAdvisor proposal and is the actual
  authoritative gate) still runs downstream, in the real actor -- this
  endpoint's validation is a fast, honest pre-filter, never the final word.

  CLJS-only ESM Cloudflare Pages Function (same target/:output-feature-set
  convention as `cloud-itonami.edge.register`'s own shadow-cljs build) --
  `(aget obj \"foo\")` bracket access throughout, never `.-foo`, for
  :advanced-optimization safety (property renaming) exactly as that ns's
  own docstring documents.

  Bindings this Function expects (see `wrangler.jsonc`):
    PARTNERS_KV -- a Workers KV namespace, used ONLY as a durable inbox
                   (`pending:<id>` -> JSON) and a read-through territory-
                   exclusivity index (`granted:<vertical>:<country>:
                   <region>` -> application-id, written by the owner's CLI
                   AFTER a real onboard, never by this Function itself)."
  (:require [clojure.string :as str]
            [partners.catalog :as catalog]))

(def ^:private required-fields
  ["applicantName" "applicantContact" "itonamiVerticalRef" "territory" "pitch"])

(defn- blank? [v] (or (nil? v) (= v "") (undefined? v)))

(defn- territory->clj [t]
  (when t
    {:country (aget t "country") :region (aget t "region")}))

(defn- validation-errors
  "Runs the intake-time structural subset of PartnerGovernor's checks --
  required fields, catalog membership, territory shape. Returns a vector
  of human-readable error strings (empty = passes)."
  [body]
  (let [territory (territory->clj (aget body "territory"))
        vertical-ref (aget body "itonamiVerticalRef")]
    (cond-> []
      (some #(blank? (aget body %)) required-fields)
      (conj "Missing required field(s): all of name, contact, itonami vertical, territory country, and pitch are required.")

      (and (not (blank? vertical-ref)) (not (catalog/known-vertical? vertical-ref)))
      (conj (str "\"" vertical-ref "\" is not a recognized itonami vertical."))

      (and territory (not (catalog/valid-territory? territory)))
      (conj "Territory country must be a 2- or 3-letter ISO 3166-1 code, e.g. \"JPN\" or \"US\"."))))

(defn- territory-grant-kv-key [vertical-ref territory]
  (str "granted:" vertical-ref ":" (some-> (:country territory) str/upper-case)
       ":" (or (some-> (:region territory) str/trim str/lower-case not-empty) "-")))

(defn- json-response [status obj]
  (js/Response. (js/JSON.stringify (clj->js obj))
                #js {:status status
                     :headers #js {"content-type" "application/json"}}))

(defn- gen-id []
  (str "PARTNER-APP-" (.toString (js/Date.now) 36) "-"
       (.toString (js/Math.floor (* (js/Math.random) 1e9)) 36)))

(defn- already-granted-response [vertical-ref territory]
  (json-response 409
                 {:ok false
                  :errors [(str "This itonami vertical is already granted for this territory ("
                               vertical-ref " / " (:country territory) "). "
                               "Consider a different vertical or territory, or contact us about a waitlist.")]}))

(defn- accepted-response [id]
  (json-response 201
                 {:ok true :id id :status "pending-owner-review"
                  :note (str "This is a NON-BINDING expression of interest. No fee was collected. "
                             "A human reviews every application before any territory is granted.")}))

(defn- write-pending-application!
  "Writes the structurally-valid submission to KV (if bound) and resolves
  to the 201 response -- separated from `handle-body` so its own promise
  chain stays flat (one `.then`, not nested inside the exclusivity check)."
  [kv body vertical-ref territory]
  (let [id (gen-id)
        record {:id id
                :applicantName (aget body "applicantName")
                :applicantContact (aget body "applicantContact")
                :itonamiVerticalRef vertical-ref
                :territory territory
                :pitch (aget body "pitch")
                :capitalTier (aget body "capitalTier")
                :language (aget body "language")
                :submittedAt (.toISOString (js/Date.))
                :status "pending-owner-review"}
        put! (if kv
               (.put kv (str "pending:" id) (js/JSON.stringify (clj->js record)))
               (js/Promise.resolve nil))]
    (.then put! (fn [_] (accepted-response id)))))

(defn- handle-valid-body
  "`body` has already passed `validation-errors` (empty). Checks
  territory-exclusivity read-through KV, then either 409s or writes the
  pending application. Returns a Promise<Response>."
  [kv body]
  (let [territory (territory->clj (aget body "territory"))
        vertical-ref (aget body "itonamiVerticalRef")
        grant-key (territory-grant-kv-key vertical-ref territory)
        lookup (if kv (.get kv grant-key) (js/Promise.resolve nil))]
    (.then lookup
           (fn [existing-grant]
             (if existing-grant
               (already-granted-response vertical-ref territory)
               (write-pending-application! kv body vertical-ref territory))))))

(defn- handle-body [kv body]
  (let [errors (validation-errors body)]
    (if (seq errors)
      (json-response 400 {:ok false :errors errors})
      (handle-valid-body kv body))))

(defn on-request-post
  "onRequestPost({request, env}) -> Promise<Response>. Cloudflare Pages
  Functions convention -- `on-request-post` is the exported name
  `shadow-cljs.edn`'s `:intake-api` build maps to `onRequestPost`."
  [ctx]
  (let [request (aget ctx "request")
        env (aget ctx "env")
        kv (aget env "PARTNERS_KV")]
    (-> (.json request)
        (.then (fn [body] (handle-body kv body)))
        (.catch (fn [err]
                  (json-response 400 {:ok false :errors [(str "Malformed request: " (aget err "message"))]}))))))
