#!/usr/bin/env node
;; nbb static-site generator: reads `partners.catalog` (pure .cljc data --
;; no JVM/browser-specific code) and writes `public/index.html`, the public
;; partner-application form (ADR-2607194000). Same nbb-generates-a-static-
;; page pattern `kotoba-lang/kototama`'s `web/generate.cljs` and
;; `cloud-itonami-isic-6310`'s own `web/generate.cljs` use, run as:
;;
;;   nbb --classpath src web/generate.cljs
;;
;; This is intentionally NOT the full kotoba-ui design-system stack (see
;; README 'UI' for why: pulling in kotoba-ui/liquid-glass-ui/shitsuke was a
;; large detour for a single-page public form, and the kotoba-uiux skill
;; explicitly allows falling back to the simpler pattern the sibling
;; isco-1212/isic-6492 self-registration UIs establish when that detour
;; isn't worth it) -- plain semantic HTML + a small inline <style>/<script>,
;; honest and reasonably clean rather than perfectly on-brand.
(ns generate
  (:require ["fs" :as fs]
            [clojure.string :as str]
            [partners.catalog :as catalog]))

(defn- esc [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- vertical-option [[ref {:keys [code standard title description]}]]
  (str "        <option value=\"" (esc ref) "\">"
       (esc title) " -- " (esc standard) " " (esc code) "</option>\n"
       "        <!-- " (esc description) " -->\n"))

(defn- vertical-card [[ref {:keys [code standard title description repo]}]]
  (str "<li class=\"vertical-card\" data-ref=\"" (esc ref) "\">"
       "<strong>" (esc title) "</strong> "
       "<span class=\"badge\">" (esc standard) " " (esc code) "</span>"
       "<p>" (esc description) "</p>"
       "<p class=\"repo\">Source: <code>" (esc repo) "</code></p>"
       "</li>\n"))

(def html
  (str
   "<!doctype html>\n"
   "<html lang=\"en\">\n"
   "<head>\n"
   "<meta charset=\"utf-8\">\n"
   "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
   "<title>cloud-itonami partners -- become a local operator</title>\n"
   "<meta name=\"description\" content=\"Apply to become the local human operator of an itonami business vertical in your territory. Non-binding expression of interest, no fee collected.\">\n"
   "<style>\n"
   ":root{color-scheme:light dark;--fg:#1a1a1a;--bg:#ffffff;--muted:#5a5a5a;--accent:#0b5fff;--border:#d8d8d8;--card:#f7f7f9;}\n"
   "@media (prefers-color-scheme:dark){:root{--fg:#f0f0f0;--bg:#111214;--muted:#a3a3a3;--accent:#6ea8ff;--border:#33363b;--card:#1a1c1f;}}\n"
   "*{box-sizing:border-box;} body{font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Helvetica,Arial,sans-serif;max-width:44rem;margin:0 auto;padding:1.5rem;color:var(--fg);background:var(--bg);line-height:1.55;}\n"
   "h1{font-size:1.6rem;} h2{font-size:1.15rem;margin-top:2rem;}\n"
   "label{display:block;margin-top:1rem;font-weight:600;} .hint{font-weight:400;color:var(--muted);font-size:.9rem;}\n"
   "input,select,textarea{width:100%;padding:.6rem;margin-top:.35rem;border:1px solid var(--border);border-radius:.4rem;background:var(--bg);color:var(--fg);font-size:1rem;}\n"
   "textarea{min-height:6rem;resize:vertical;}\n"
   "button{margin-top:1.5rem;padding:.7rem 1.4rem;border:0;border-radius:.4rem;background:var(--accent);color:#fff;font-size:1rem;font-weight:600;cursor:pointer;}\n"
   "button:disabled{opacity:.6;cursor:not-allowed;}\n"
   ".disclaimer{background:var(--card);border:1px solid var(--border);border-radius:.5rem;padding:1rem;margin:1.25rem 0;font-size:.92rem;}\n"
   ".disclaimer strong{display:block;margin-bottom:.35rem;}\n"
   "ul.verticals{list-style:none;padding:0;margin:0;}\n"
   ".vertical-card{border:1px solid var(--border);border-radius:.5rem;padding:.75rem 1rem;margin-bottom:.6rem;background:var(--card);}\n"
   ".vertical-card .badge{display:inline-block;font-size:.75rem;color:var(--muted);border:1px solid var(--border);border-radius:1rem;padding:.05rem .5rem;margin-left:.4rem;}\n"
   ".vertical-card p{margin:.35rem 0 0;color:var(--muted);font-size:.9rem;}\n"
   ".vertical-card .repo code{font-size:.8rem;}\n"
   "#result{margin-top:1rem;padding:.75rem 1rem;border-radius:.4rem;display:none;}\n"
   "#result.ok{display:block;background:#dff6e0;color:#123d17;}\n"
   "#result.err{display:block;background:#fbdede;color:#5c1212;}\n"
   "@media (prefers-color-scheme:dark){#result.ok{background:#123d17;color:#dff6e0;}#result.err{background:#5c1212;color:#fbdede;}}\n"
   "footer{margin-top:3rem;color:var(--muted);font-size:.85rem;}\n"
   "</style>\n"
   "</head>\n"
   "<body>\n"
   "<h1>Become a local operator of an itonami business</h1>\n"
   "<p>cloud-itonami runs a fleet of AI-operated business verticals (\"itonami\"), one per industry. "
   "This program recruits real individual humans, worldwide, to become the local operator/partner of a "
   "specific itonami vertical in a specific country -- the human side of the business, in your territory.</p>\n"

   "<div class=\"disclaimer\">\n"
   "<strong>Before you apply, please read:</strong>\n"
   "Submitting this form is a <strong>NON-BINDING expression of interest</strong> only. "
   "<strong>No franchise fee or any payment is collected at this stage</strong>, and none will be requested "
   "by this form. <strong>No binding agreement exists</strong> until a separately reviewed, formal contract "
   "is signed following legal review. A human reviews every application before any territory is granted -- "
   "there is no automatic approval.\n"
   "</div>\n"

   "<h2>Available itonami verticals (v1 subset)</h2>\n"
   "<p class=\"hint\">This is a starting subset, not the full cloud-itonami fleet -- see this program's "
   "<a href=\"https://github.com/cloud-itonami/cloud-itonami-partners\">source repository</a> for the full, "
   "honest catalog and how it's extended.</p>\n"
   "<ul class=\"verticals\">\n"
   (apply str (map vertical-card (catalog/options)))
   "</ul>\n"

   "<h2>Apply</h2>\n"
   "<form id=\"apply-form\">\n"
   "  <label>Full name\n"
   "    <input type=\"text\" name=\"applicantName\" required autocomplete=\"name\">\n"
   "  </label>\n"
   "  <label>Email address\n"
   "    <input type=\"email\" name=\"applicantContact\" required autocomplete=\"email\">\n"
   "  </label>\n"
   "  <label>Itonami vertical\n"
   "    <select name=\"itonamiVerticalRef\" required>\n"
   "      <option value=\"\" disabled selected>Select a vertical...</option>\n"
   (apply str (map vertical-option (catalog/options)))
   "    </select>\n"
   "  </label>\n"
   "  <label>Territory (country)\n"
   "    <span class=\"hint\">ISO 3166-1 code, e.g. JPN, USA, DEU, or a 2-letter code</span>\n"
   "    <input type=\"text\" name=\"territoryCountry\" required maxlength=\"3\" pattern=\"[A-Za-z]{2,3}\" placeholder=\"JPN\">\n"
   "  </label>\n"
   "  <label>Region (optional)\n"
   "    <input type=\"text\" name=\"territoryRegion\" placeholder=\"e.g. Kanto, California\">\n"
   "  </label>\n"
   "  <label>Your pitch / relevant experience\n"
   "    <textarea name=\"pitch\" required placeholder=\"Why you, why this vertical, why this territory.\"></textarea>\n"
   "  </label>\n"
   "  <label>Capital tier (self-declared)\n"
   "    <select name=\"capitalTier\" required>\n"
   "      <option value=\"\" disabled selected>Select...</option>\n"
   "      <option value=\"tier-1\">Tier 1 -- bootstrap / solo</option>\n"
   "      <option value=\"tier-2\">Tier 2 -- small team, some capital</option>\n"
   "      <option value=\"tier-3\">Tier 3 -- funded / institutional backing</option>\n"
   "    </select>\n"
   "  </label>\n"
   "  <label>Preferred working language\n"
   "    <input type=\"text\" name=\"language\" required placeholder=\"e.g. en, ja, pt\">\n"
   "  </label>\n"
   "  <button type=\"submit\">Submit expression of interest</button>\n"
   "</form>\n"
   "<div id=\"result\" role=\"status\"></div>\n"

   "<footer>\n"
   "This form and its intake endpoint are open source:\n"
   "<a href=\"https://github.com/cloud-itonami/cloud-itonami-partners\">github.com/cloud-itonami/cloud-itonami-partners</a>.\n"
   "</footer>\n"

   "<script>\n"
   "document.getElementById('apply-form').addEventListener('submit', async function (ev) {\n"
   "  ev.preventDefault();\n"
   "  var form = ev.target;\n"
   "  var btn = form.querySelector('button');\n"
   "  var result = document.getElementById('result');\n"
   "  var fd = new FormData(form);\n"
   "  var body = {\n"
   "    applicantName: fd.get('applicantName'),\n"
   "    applicantContact: fd.get('applicantContact'),\n"
   "    itonamiVerticalRef: fd.get('itonamiVerticalRef'),\n"
   "    territory: { country: (fd.get('territoryCountry') || '').toUpperCase(), region: fd.get('territoryRegion') || null },\n"
   "    pitch: fd.get('pitch'),\n"
   "    capitalTier: fd.get('capitalTier'),\n"
   "    language: fd.get('language')\n"
   "  };\n"
   "  btn.disabled = true;\n"
   "  result.className = ''; result.style.display = 'none';\n"
   "  try {\n"
   "    var res = await fetch('/api/intake', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });\n"
   "    var data = await res.json();\n"
   "    if (res.ok && data.ok) {\n"
   "      result.className = 'ok';\n"
   "      result.textContent = 'Received (id ' + data.id + '). ' + data.note;\n"
   "      form.reset();\n"
   "    } else {\n"
   "      result.className = 'err';\n"
   "      result.textContent = (data.errors || ['Submission failed.']).join(' ');\n"
   "    }\n"
   "  } catch (e) {\n"
   "    result.className = 'err';\n"
   "    result.textContent = 'Network error -- please try again.';\n"
   "  } finally {\n"
   "    btn.disabled = false;\n"
   "    result.style.display = 'block';\n"
   "  }\n"
   "});\n"
   "</script>\n"
   "</body>\n"
   "</html>\n"))

(fs/mkdirSync "public" #js {:recursive true})
(fs/writeFileSync "public/index.html" html)
(println (str "wrote public/index.html (" (count (catalog/options)) " verticals)"))
