(ns partners.cacao
  "CACAO/did:key identity for the cloud-itonami-partners actor -- minted and
  held in the actor's OWN runtime (CLAUDE.md Actors: 'actor が自分の鍵で
  CACAO を自己発行'), never a shared operator secret. Adapted from
  `cloud-itonami/src/cloud_itonami/identity.clj` (the flagship actor's own
  JVM self-mint identity pattern) with TWO identity kinds instead of one:

    1. THE ACTOR'S OWN identity -- `.cloud-itonami-partners/identity.edn`
       (gitignored) -- used to self-authenticate to kotobase.net /
       itonami.cloud the same way every actor in this fleet does.
       `load-or-create-identity!` (this file, no args) bootstraps it.

    2. ONE identity PER APPROVED PARTNER -- `.partner-<application-id>/
       identity.edn` (gitignored, same glob pattern) -- minted ONLY by
       `partners.operation`'s `:onboard` node, ONLY for an application that
       has ALREADY passed PartnerGovernor AND been explicitly human-approved
       (ADR-2607194000 step 5: 'パートナー用 CACAO identity を自己 mint
       し... load-or-create-identity! パターン... but keyed to the
       partner's application id, not to this actor's own identity').
       `mint-for-partner!` generates a FRESH, INDEPENDENT Ed25519 keypair
       per partner -- never derived from or shared with the actor's own key
       -- because a partner's identity must be independently revocable
       (dropping `.partner-<id>/` doesn't touch the actor's own auth) and
       must never let a compromised partner key sign as the actor itself.

  The actor's Ed25519 key IS its graph: the key-derived IPNS name
  (`ipns-name`, the 'k51…' name) per kotoba/write.cljs (AUTHORITY is the
  Ed25519 signature over a key-derived IPNS name, NOT a server). Same for
  every minted partner identity -- the partner's key is ITS OWN graph, not
  a sub-resource of the actor's.

  JVM-only (java.security.SecureRandom / java.time), not `.cljc` -- matches
  `cloud-itonami.identity`'s own docstring ('local operator tool... not
  edge/browser code'). `partners.operation` requires this ns ONLY behind a
  `#?(:clj ...)` reader-conditional (see that ns) so the CLJS build (the
  public intake Pages Function) never tries to compile java.security.*."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ed25519.core :as ed]
            [ipns.core :as ipns])
  (:import (java.security SecureRandom)
           (java.nio.file Files StandardOpenOption FileAlreadyExistsException)
           (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)))

(def ^:private actor-pattern
  "Guards `identity-path` against a `slug` containing `..`/`/` segments
  resolving OUTSIDE the intended `.{slug}/` gitignored sandbox -- the same
  hardening `cloud-itonami.identity/identity-path` applies (its own
  docstring documents why: this generates and WRITES a fresh private
  signing key on first use, so an unvalidated path is both an
  arbitrary-path key-write and, if something parseable already exists
  there, an arbitrary-path read)."
  #"^[a-zA-Z0-9_-]+$")

(defn- identity-path ^java.io.File [slug]
  (when-not (and (string? slug) (re-matches actor-pattern slug))
    (throw (ex-info "partners.cacao: invalid identity slug" {:slug slug})))
  (io/file (str "." slug) "identity.edn"))

(defn- random-seed ^bytes []
  (let [b (byte-array 32)]
    (.nextBytes (SecureRandom.) b)
    b))

(def ^:private iso-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss'Z'"))

(defn- iso [^Instant instant]
  (.format iso-formatter (.atZone (.truncatedTo instant ChronoUnit/SECONDS) ZoneOffset/UTC)))

(defn- read-identity-with-retry
  "slurp+parse `path`, retrying briefly instead of failing on a transient
  empty/partial read -- see `cloud-itonami.identity/read-identity-with-
  retry`'s docstring for why a bare single slurp+parse is unsafe even after
  an atomic CREATE_NEW write (concurrent-agent operation is routine in this
  workspace, per CLAUDE.md)."
  [path]
  (loop [attempts 0]
    (let [parsed (try
                   (let [content (slurp path)]
                     (when-not (str/blank? content)
                       (edn/read-string content)))
                   (catch Exception _ nil))]
      (cond
        parsed parsed
        (< attempts 50) (do (Thread/sleep 5) (recur (inc attempts)))
        :else (throw (ex-info "partners.cacao: identity file unreadable after retry"
                              {:path (str path) :attempts attempts}))))))

(defn- graph-name
  "The identity's own graph: the key-derived libp2p-key IPNS name ('k51…')
  of its Ed25519 public key. Pure function of `seed-hex`, so it is never
  persisted -- every load recomputes it instead of risking a stale cached
  copy diverging from the seed actually on disk."
  [seed-hex]
  (ipns/pubkey->name (ed/pubkey-from-seed (ed/unhex seed-hex))))

(defn- load-or-create-identity-at!
  "Load the identity persisted at `slug`'s `.{slug}/identity.edn`, or
  generate + persist a new one. Returns {:slug :seed-hex :did :graph}.
  `:seed-hex` is the private signing key -- the containing `.{slug}/`
  directory MUST stay gitignored (see this repo's `.gitignore`).

  Uses `Files/write`'s atomic `CREATE_NEW` (fails if the path already
  exists) so two concurrent first-time bootstraps for the SAME slug
  converge on one winner's identity rather than each generating a
  different random seed and racing to overwrite -- see
  `cloud-itonami.identity/load-or-create-identity!`'s docstring for the
  full race analysis this mirrors."
  [slug]
  (let [path (identity-path slug)
        identity (if (.exists path)
                   (read-identity-with-retry path)
                   (let [seed (random-seed)
                         seed-hex (ed/hexify seed)
                         did (ed/did-key-from-seed seed)
                         identity {:slug slug :seed-hex seed-hex :did did}]
                     (io/make-parents path)
                     (try
                       (Files/write (.toPath path)
                                    (.getBytes (pr-str identity) "UTF-8")
                                    (into-array StandardOpenOption [StandardOpenOption/CREATE_NEW]))
                       identity
                       (catch FileAlreadyExistsException _
                         (read-identity-with-retry path)))))]
    (assoc identity :graph (graph-name (:seed-hex identity)))))

(def actor-slug
  "This actor's own identity slug -- `.cloud-itonami-partners/identity.edn`."
  "cloud-itonami-partners")

(defn load-or-create-identity!
  "The actor's OWN persisted identity (bootstraps on first call). See ns
  docstring kind 1."
  []
  (load-or-create-identity-at! actor-slug))

(defn- partner-slug [application-id]
  ;; e.g. "partner-app-1" / "partner-PARTNER-APP-00000001" ->
  ;; .partner-app-1/identity.edn -- application-id's own charset
  ;; (`partners.registry/register-application`'s `PARTNER-APP-########`
  ;; format, or a test id like "app-1") is already `actor-pattern`-safe,
  ;; but this still normalizes defensively rather than trusting the caller.
  (let [safe (str/replace (str application-id) #"[^a-zA-Z0-9_-]" "-")]
    (str "partner-" safe)))

(defn mint-for-partner!
  "Mint (or, if already minted for this application-id, reload) a FRESH,
  INDEPENDENT CACAO identity for an approved partner -- ns docstring kind
  2. `application` (the store's application map) is accepted but not
  currently embedded in the identity file -- kept as a parameter so a
  future caller can bind additional claims without changing this fn's
  call sites. Returns ONLY the public parts ({:did :graph}) -- the private
  seed-hex stays inside `.partner-<slug>/identity.edn` and is never
  returned to a caller that might log it (see `partners.operation`'s
  `:onboard` node, which only ever destructures `:did`/`:graph` from this
  return value into the audit ledger)."
  [application-id _application]
  (let [identity (load-or-create-identity-at! (partner-slug application-id))]
    {:did (:did identity) :graph (:graph identity)}))

(defn mint-token
  "Mint a CACAO for `identity` ({:seed-hex ...}) with an explicit `:aud` and
  `:resources`, valid for `:ttl-seconds` (default 24h). Requires
  `org-chainagnostic-cacao`'s `cacao.core/mint` (the canonical SIWE/CBOR/
  Ed25519 mint this actor's own `.cloud-itonami-partners/identity.edn`
  session tokens use) -- resolved lazily via `requiring-resolve` so a
  caller that only needs `load-or-create-identity!`/`mint-for-partner!`
  (no session-token minting) never pays for loading `cacao.core`."
  [{:keys [seed-hex]} {:keys [aud resources ttl-seconds]
                       :or {ttl-seconds (* 24 3600)}}]
  (let [mint (requiring-resolve 'cacao.core/mint)
        now (Instant/now)
        exp (.plusSeconds now ttl-seconds)]
    (:cacao-b64
     (mint {:seed (ed/unhex seed-hex)
            :aud aud
            :iat (iso now)
            :exp (iso exp)
            :nonce (str (random-uuid))
            :resources resources}))))

(def default-kotobase-aud
  "net-kotobase pod enforces `aud == did:web:kotobase.net` -- same default
  `cloud-itonami.identity-core/default-kotobase-aud` uses."
  "did:web:kotobase.net")

(defn kotobase-resources
  "CACAO resource scope for a kotobase.net graph -- same shape
  `cloud-itonami.identity-core/kotobase-resources` grants (op-capability +
  graph scope)."
  [graph]
  ["kotoba://op/datom:read"
   "kotoba://op/datom:transact"
   "kotoba://can/kotobase:pin"
   (str "kotoba://graph/" graph)])

(defn mint-kotobase-session
  "Mint a kotobase.net CACAO session for the ACTOR'S OWN identity (never a
  partner identity -- partners do not self-authenticate to kotobase.net in
  v1, see README 'Non-goals')."
  ([] (mint-kotobase-session (load-or-create-identity!)))
  ([identity]
   (mint-token identity {:aud default-kotobase-aud
                         :resources (kotobase-resources (:graph identity))})))
