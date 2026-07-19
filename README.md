# cloud-itonami-partners

Recruits, screens, and (with a human's explicit sign-off) grants exclusive
territory rights to real individual humans who want to become the local
operator ("partner"/franchisee) of a specific
[cloud-itonami](https://github.com/cloud-itonami) itonami business vertical
in a specific country. Design record:
[ADR-2607194000](../../90-docs/adr/2607194000-cloud-itonami-partners-actor.edn)
in the `com-junkawasaki/root` superproject.

Built on this workspace's
[`langgraph-clj`](https://github.com/kotoba-lang/langgraph) StateGraph
runtime -- the same **Sealed-LLM ⊣ independent Governor** actor pattern as
[`cloud-itonami-isic-6499`](https://github.com/cloud-itonami/cloud-itonami-isic-6499)
(DD-LLM ⊣ InvestmentCommitteeGovernor) and
[`cloud-itonami-isic-6310`](https://github.com/cloud-itonami/cloud-itonami-isic-6310)
(HR-LLM ⊣ PolicyGovernor). Here it is **ScreeningAdvisor ⊣
PartnerGovernor**.

> **Why an actor layer at all?** An LLM is good at reading a pitch and
> drafting a plausibility score -- but it has no fiduciary/legal authority
> to grant an exclusive real-world business territory to a stranger who
> filled in a web form, and no business being the one that decides that.
> This project seals the ScreeningAdvisor into a single node, wraps it with
> an independent **PartnerGovernor**, and requires an explicit **human
> approval** before any territory is ever granted -- permanently, not as a
> rollout milestone (see "Why no phase.cljc" below).

## The core contract

```
partner-application (public form)
        |
        v
   ┌──────────────────┐   proposal        ┌────────────────────┐
   │ ScreeningAdvisor  │ ────────────────▶│  PartnerGovernor    │  (independent system)
   │  (sealed LLM)     │  score+rationale  │  territory-exclusive │
   └──────────────────┘                    │  known-vertical ·    │
                                    hold ◀──┼──required-fields    │
                                     │       └──────────┬──────────┘
                              REJECT (auto,       clean, ALWAYS escalates
                              never reaches                  │
                              a human)                        v
                                                    human owner approves /
                                                    rejects / waitlists
                                                               │
                                                     approve ──┴──▶ onboard:
                                                        mint CACAO identity
                                                        for the partner +
                                                        territory-grant record
```

**PartnerGovernor never lets the actor grant a territory it has rejected,
and a territory grant is ALWAYS a human decision -- there is no confidence
threshold or rollout phase anywhere in this codebase that lets it
auto-commit.** A hard governor violation (unknown vertical, missing
fields, an already-granted territory, a malformed screening proposal)
routes straight to `reject`, never reaching a human at all. A **clean**
application still always pauses at `interrupt-before #{:request-approval}`
for the repo owner.

## Legal disclaimer (also shown on the public form)

Submitting the public application form is a **non-binding expression of
interest**. **No franchise fee or any payment is collected at this
stage.** **No binding agreement exists** until a separately reviewed,
formal contract is signed following legal review. This scope limitation is
deliberate (ADR-2607194000): this fleet's `/legal/*` pages are still
DRAFT, so v1 is scoped to "collect interest," never to imply a binding
commitment or collect money.

## Architecture

| File | Role |
|---|---|
| `src/partners/catalog.cljc` | The verified itonami-vertical catalog (see "Vertical catalog" below) |
| `src/partners/registry.cljc` | Pure `partner-application`/`screening-result`/`territory-grant` draft-record builders |
| `src/partners/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`, via `kotoba-lang/langchain-store`'s field-spec entity helpers, ADR-2607141600) + append-only audit ledger + territory-grant history |
| `src/partners/advisor.cljc` | **ScreeningAdvisor** -- `mock-advisor` (deterministic, offline) ‖ `llm-advisor` (real `langchain.model/ChatModel`). Proposal only -- score + rationale, never a decision |
| `src/partners/governor.cljc` | **PartnerGovernor** -- 4 HARD runtime checks (territory exclusivity, required fields, known vertical, screening-proposal well-formedness) + 2 structural invariants (fairness/non-discrimination, minimal disclosure) verified by `governor_contract_test.cljc` |
| `src/partners/operation.cljc` | **OperationActor** -- the langgraph-clj StateGraph: `intake → screen → govern → decide → [reject \| request-approval → {onboard \| reject \| waitlist}]` |
| `src/partners/cacao.clj` | JVM-only CACAO/did:key self-mint identity -- the actor's OWN identity (`.cloud-itonami-partners/identity.edn`) AND one independent identity PER approved partner (`.partner-<application-id>/identity.edn`) |
| `src/partners/sim.cljc` | Demo driver (`clojure -M:dev:run`) -- also the literal template for the owner's real review procedure, see "Human approval" |
| `src/partners/edge/intake.cljs` | Cloudflare Pages Function source (`POST /api/intake`) -- compiled to `functions/api/intake.js` via shadow-cljs |
| `web/generate.cljs` | nbb static-site generator -- reads `partners.catalog` and writes `public/index.html` (the public form) |

### Why no `phase.cljc`

`cloud-itonami-isic-6499`/`cloud-itonami-isic-6310` each have a `phase.cljc`
(a 0→3 rollout table choosing which *clean* ops may skip the human as trust
grows). This actor deliberately has none: ADR-2607194000 makes territory
grants **permanently** human-gated, not a trust milestone to relax later
("既存 actor の「耐空性サインオフ」相当の人間ゲートを踏襲する" -- the same
posture this fleet uses for consequential real-world sign-offs like
airworthiness certification). A phase table's only job is picking which
clean writes may auto-commit; for this actor's one real write, the answer
is permanently "none" -- so the table would have exactly one, unchanging
row. `partners.governor`'s own docstring documents this in detail.

## Vertical catalog (honest, v1 subset)

`orgs/cloud-itonami/` has ~1120 sibling repos. Only two PREFIXES name an
operable itonami *business* a human partner could run:
`cloud-itonami-isic-####` (UN ISIC-classified companies) and
`cloud-itonami-isco-####` (ISCO-08-classified solo/fractional
practitioner businesses). Every other prefix (`assoc-*` trade-association
data partnerships, `lei-*` Legal Entity Identifier records,
`municipality-*` city open-data, `iso3166-*` country/regulator reference
data, `unspsc-*`/`cofog-*`/`gtin-*`/`jsic-*`/`hygiene-*`/`regulatory-*`
classification catalogs) is a data catalog, not a franchisable business,
and is excluded. Full reasoning + the exclusion list is in
`src/partners/catalog.cljc`'s own docstring.

Within the ~643 isic/isco repos, v1 seeds **10** verified verticals --
chosen because each repo's own `README.md` (read directly) opens with a
concrete, already-*implemented* "Open Business/Occupation Blueprint"
statement, not a bare scaffold:

| Ref | Standard | Title |
|---|---|---|
| `cloud-itonami-isic-6399` | ISIC Rev.4/5 | Meta job-search |
| `cloud-itonami-isic-6310` | ISIC Rev.5 | HR / talent-management SaaS |
| `cloud-itonami-isic-7810` | ISIC Rev.5 | Employment agency |
| `cloud-itonami-isic-5820` | ISIC Rev.4 | Commercial CRM / subscription-commerce SaaS |
| `cloud-itonami-isic-6499` | ISIC Rev.4/5 | Venture capital fund |
| `cloud-itonami-isic-6492` | ISIC Rev.5 | Consumer/commercial credit granting |
| `cloud-itonami-isco-1212` | ISCO-08 | Independent/fractional HR manager |
| `cloud-itonami-isic-6511` | ISIC Rev.5 | Life insurance underwriting |
| `cloud-itonami-isic-8291` | ISIC Rev.4 | Corporate/compliance intelligence SaaS |
| `cloud-itonami-isic-6910` | ISIC Rev.5 | Company incorporation / registration-agent services |

This is a **subset**, not the full fleet (ADR-2607194000 Non-goals
explicitly scope v1 this way). Extending it is additive: verify the next
repo's README, add one map entry to `partners.catalog/verticals`, run
`npx nbb --classpath src web/generate.cljs` to regenerate the form.

## Run tests

```bash
clojure -M:lint     # clj-kondo, errors fail
clojure -M:dev:test  # 30 tests / 195 assertions -- governor contract,
                     # store parity (MemStore ‖ DatomicStore), StateGraph
                     # end-to-end flows, catalog honesty, CACAO identity
clojure -M:dev:run   # walk 5 demo applications through the real actor
                     # (approve / 2 governor-auto-reject / human-reject /
                     # human-waitlist), print the ledger + grant records
```

## Human approval -- how the owner actually reviews a pending application

There is **no dashboard/admin UI in v1** -- the review procedure is a
short, concrete REPL/CLI loop, deliberately simple because approval
frequency is expected to be low and the stakes (an exclusive real-world
territory grant) warrant a human actually reading the pitch, not clicking
through a UI:

1. **Pull pending applications out of the intake endpoint's KV.** Every
   `POST /api/intake` that passes the endpoint's structural pre-filter
   (required fields, known vertical, territory shape, not already granted)
   is written to Cloudflare KV as `pending:<application-id>`. List and
   read them with `wrangler`:
   ```bash
   npx wrangler kv key list --namespace-id=<PARTNERS_KV id> --remote
   npx wrangler kv key get "pending:PARTNER-APP-..." --namespace-id=<id> --remote
   ```
2. **Load each pending application into the real actor** (a REPL, or add
   entries to `partners.sim`/a small script using `partners.operation/
   intake!` with the KV JSON's fields) and run it through
   `intake → screen → govern`:
   ```clojure
   (require '[partners.store :as store] '[partners.operation :as op] '[langgraph.graph :as g])
   (def db (store/seed-db))
   (def actor (op/build db))
   (def id (op/intake! db {:applicant-name "..." :applicant-contact "..." ...})) ; from the KV JSON
   (g/run* actor {:request {:op :partner/govern :subject id} :context {:actor-id "cloud-itonami-partners"}} {:thread-id id})
   ```
   If this returns `:status :done`, the governor auto-rejected it (a HARD
   violation) -- read `(store/ledger db)` for the reason, no further action
   needed. If it returns `:status :interrupted`, it's clean and waiting for
   you.
3. **Read the ScreeningAdvisor's rationale and PartnerGovernor's verdict**
   (`(store/screening-result-of db id)`), and the pitch itself, then
   decide.
4. **Resume the graph with your decision:**
   ```clojure
   (g/run* actor {:approval {:status :approved :by "jun"}} {:thread-id id :resume? true})   ; grants the territory + mints a CACAO identity
   (g/run* actor {:approval {:status :rejected :by "jun" :reason "..."}} {:thread-id id :resume? true})
   (g/run* actor {:approval {:status :waitlisted :by "jun" :reason "..."}} {:thread-id id :resume? true})
   ```
   On `:approved`, the `:onboard` node mints a fresh, independent CACAO
   identity for the partner (`.partner-<id>/identity.edn` -- gitignored,
   never committed) and appends a `territory-grant` record, which
   `PartnerGovernor`'s territory-exclusivity check reads for every future
   application. `partners.sim`'s `-main` runs exactly this sequence end to
   end (5 applications, all 4 outcomes) and is the literal reference
   implementation of this procedure.
5. **(Optional, not yet wired) write a `granted:<vertical>:<country>:
   <region>` KV key** so the intake endpoint's own best-effort exclusivity
   pre-check (409s a duplicate submission before it even reaches you)
   picks up the new grant -- otherwise it's only enforced by
   `PartnerGovernor` at review time (step 2), which is still authoritative.

## Deploy

The public form + intake endpoint are a standalone Cloudflare Pages
project (`cloud-itonami-partners`, account `ai-gftd-cloud`) -- **not**
integrated into the shared `gftdcojp/cloud-itonami` tenant-routing
platform (that repo is read-only reference for this project; ADR-2607194000
scopes this actor to its own deployment).

```bash
npm install
npx nbb --classpath src web/generate.cljs   # regenerate public/index.html from partners.catalog
npx shadow-cljs release intake-api          # regenerate functions/api/intake.js from src/partners/edge/intake.cljs
npx wrangler pages deploy public --project-name=cloud-itonami-partners --branch=main
```

Live: **https://cloud-itonami-partners.pages.dev**

## Non-goals (v1, ADR-2607194000)

- No binding legal contract generation/signature flow.
- No payment/franchise-fee collection anywhere.
- No auto-approval -- human sign-off is mandatory and permanent.
- Not the full 1120-vertical fleet -- a verified 10-vertical subset (see above).

## License

Code and implementation templates are AGPL-3.0-or-later.
