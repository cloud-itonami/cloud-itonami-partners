#!/usr/bin/env node
;; nbb static-site generator: reads `partners.catalog` (pure .cljc data --
;; no JVM/browser-specific code) and writes `public/index.html`, the public
;; partner-application form (ADR-2607194000). Same nbb-generates-a-static-
;; page pattern `kotoba-lang/kototama`'s `web/generate.cljs` and
;; `cloud-itonami-isic-6310`'s own `web/generate.cljs` use, run as:
;;
;;   nbb --classpath "src:../../kotoba-lang/html/src:../../kotoba-lang/jp-go-digital-design-system/src" \
;;       web/generate.cljs
;;
;; UI は デジタル庁デザインシステム(DADS)を kotoba-lang/jp-go-digital-design-system
;; 経由で使う(superproject ADR-2607261600)。以前このファイルは「kotoba-ui を
;; 引き込むのは単一ページのフォームには大きすぎる」という理由で素の HTML 文字列 +
;; インライン <style> を手書きしていたが、DADS は行政手続きフォームそのものが
;; 主対象の design system で、hiccup コンポーネント + vendored CSS を渡すだけで
;; 済むため、その detour 判断はもう成り立たない。手書き HTML 文字列は撤去した。
;;
;; DADS は light mode 固定(上流に dark palette が無い)。移行前の
;; prefers-color-scheme による dark 対応は意図的に落としている。
;;
;; dds.css の読み込みパスは環境変数 JP_GO_DDS_CSS で上書きできる
;; (CI / worktree など monorepo 以外のレイアウト用)。
(ns generate
  (:require ["fs" :as fs]
            [jp-go-dds.core :as dds]
            [jp-go-dds.page :as page]
            [partners.catalog :as catalog]))

(def dds-css-path
  (or (some-> js/process.env.JP_GO_DDS_CSS not-empty)
      "../../kotoba-lang/jp-go-digital-design-system/resources/jp_go_dds/dds.css"))
(def dds-css (fs/readFileSync dds-css-path "utf8"))

;; ページ固有の微調整のみ。色は DADS token 参照で raw hex は書かない。
;; select は上流 DADS の vendored subset に含まれない(dds.css に .dads-select が
;; 無い)ので、.dads-input-text__input と同じ寸法・境界・focus 挙動になるよう
;; ここで最小限に合わせる。上流 class 名は騙らず app 固有の名前を使う。
(def app-css
  (str
   ".pt-lead{color:var(--color-neutral-solid-gray-700);line-height:1.7}"
   ".pt-banner{margin-block:1.5rem}"
   ".pt-verticals{list-style:none;padding:0;margin:0;display:flex;"
   "flex-direction:column;gap:.75rem}"
   ".pt-vertical .pt-vertical-title{font-weight:700;margin:0}"
   ".pt-vertical p{margin:.35rem 0 0;color:var(--color-neutral-solid-gray-700);"
   "font-size:.9375rem;line-height:1.7}"
   ".pt-vertical .pt-repo{font-size:.875rem}"
   ".pt-form{display:flex;flex-direction:column;gap:1.25rem;max-width:36rem}"
   ".pt-select{box-sizing:border-box;width:100%;max-width:100%;height:3rem;"
   "border:1px solid var(--color-neutral-solid-gray-600);"
   "background-color:var(--color-neutral-white);"
   "padding:calc(12 / 16 * 1rem) calc(16 / 16 * 1rem);"
   "border-radius:calc(8 / 16 * 1rem);color:var(--color-neutral-solid-gray-900);"
   "font:inherit;line-height:1}"
   "@media (hover: hover){.pt-select:hover{border-color:var(--color-neutral-black)}}"
   ".pt-select:focus-visible{outline:calc(4 / 16 * 1rem) solid var(--color-neutral-black);"
   "outline-offset:calc(2 / 16 * 1rem);"
   "box-shadow:0 0 0 calc(2 / 16 * 1rem) var(--color-primitive-yellow-300)}"
   ".dads-input-text__input,.dads-textarea__textarea{width:100%}"
   ".dads-textarea__textarea{min-height:7rem;resize:vertical}"
   ".pt-submit{margin-top:.5rem}"
   "#result{display:none;margin-top:1.5rem}"
   "#result[data-shown]{display:grid}"
   ".pt-footer{border-top:1px solid var(--color-neutral-solid-gray-200);"
   "margin-top:3rem;padding-block:1.5rem 3rem;"
   "color:var(--color-neutral-solid-gray-600);font-size:.875rem;line-height:1.8}"
   ".pt-footer p{margin:0}"
   "code{font-family:var(--font-family-mono);background:var(--color-neutral-solid-gray-50);"
   "border:1px solid var(--color-neutral-solid-gray-200);border-radius:4px;"
   "padding:1px 5px;font-size:.9em}"))

;; --- catalog ------------------------------------------------------------

(defn- vertical-card [[ref {:keys [code standard title description repo]}]]
  [:li {:class "pt-vertical" :data-ref ref}
   (dds/card
    [:p {:class "pt-vertical-title"}
     title " " (dds/chip-label (str standard " " code) {:color "gray" :style "outlined"})]
    [:p description]
    [:p {:class "pt-repo"} "Source: " [:code repo]])])

(defn- vertical-option [[ref {:keys [code standard title]}]]
  [:option {:value ref} (str title " — " standard " " code)])

;; --- form ---------------------------------------------------------------

(defn- select-field
  [{:keys [id name label support required? placeholder]} options]
  (dds/form-field
   (cond-> {:label label :for id}
     support (assoc :support support :support-id (str id "-support"))
     required? (assoc :status "Required"))
   (into [:select (cond-> {:id id :name name :class "pt-select"}
                    required? (assoc :required true)
                    support (assoc :aria-describedby (str id "-support")))
          [:option {:value "" :disabled true :selected true} placeholder]]
         options)))

(def apply-form
  [:form {:id "apply-form" :class "pt-form"}
   (dds/form-field
    {:label "Full name" :for "f-name" :status "Required"}
    (dds/input-text {:id "f-name" :name "applicantName" :type "text"
                     :required true :autocomplete "name"}))
   (dds/form-field
    {:label "Email address" :for "f-email" :status "Required"}
    (dds/input-text {:id "f-email" :name "applicantContact" :type "email"
                     :required true :autocomplete "email"}))
   (select-field {:id "f-vertical" :name "itonamiVerticalRef"
                  :label "Itonami vertical" :required? true
                  :placeholder "Select a vertical…"}
                 (map vertical-option (catalog/options)))
   (dds/form-field
    {:label "Territory (country)" :for "f-country" :status "Required"
     :support "ISO 3166-1 code, e.g. JPN, USA, DEU, or a 2-letter code"
     :support-id "f-country-support"}
    (dds/input-text {:id "f-country" :name "territoryCountry" :type "text"
                     :required true :maxlength "3" :pattern "[A-Za-z]{2,3}"
                     :placeholder "JPN" :aria-describedby "f-country-support"}))
   (dds/form-field
    {:label "Region (optional)" :for "f-region"}
    (dds/input-text {:id "f-region" :name "territoryRegion" :type "text"
                     :placeholder "e.g. Kanto, California"}))
   (dds/form-field
    {:label "Your pitch / relevant experience" :for "f-pitch" :status "Required"}
    (dds/textarea {:id "f-pitch" :name "pitch" :required true
                   :placeholder "Why you, why this vertical, why this territory."}))
   (select-field {:id "f-capital" :name "capitalTier"
                  :label "Capital tier (self-declared)" :required? true
                  :placeholder "Select…"}
                 [[:option {:value "tier-1"} "Tier 1 — bootstrap / solo"]
                  [:option {:value "tier-2"} "Tier 2 — small team, some capital"]
                  [:option {:value "tier-3"} "Tier 3 — funded / institutional backing"]])
   (dds/form-field
    {:label "Preferred working language" :for "f-language" :status "Required"}
    (dds/input-text {:id "f-language" :name "language" :type "text"
                     :required true :placeholder "e.g. en, ja, pt"}))
   [:div {:class "pt-submit"}
    (dds/button "Submit expression of interest"
                {:type :solid-fill :size "lg" :submit? true :id "submit-btn"})]])

;; --- page ---------------------------------------------------------------

(def body
  (dds/container
   [:header {:style {:padding-block "2.5rem 0"}}
    (dds/heading 1 "Become a local operator of an itonami business")
    [:p {:class "pt-lead"}
     "cloud-itonami runs a fleet of AI-operated business verticals (\"itonami\"), "
     "one per industry. This program recruits real individual humans, worldwide, "
     "to become the local operator/partner of a specific itonami vertical in a "
     "specific country — the human side of the business, in your territory."]]

   [:div {:class "pt-banner"}
    (dds/notification-banner
     {:type :warning :heading "Before you apply, please read"}
     [:p "Submitting this form is a " [:strong "NON-BINDING expression of interest"]
      " only. " [:strong "No franchise fee or any payment is collected at this stage"]
      ", and none will be requested by this form. "
      [:strong "No binding agreement exists"] " until a separately reviewed, formal "
      "contract is signed following legal review. A human reviews every application "
      "before any territory is granted — there is no automatic approval."])]

   (dds/section
    {:title "Available itonami verticals (v1 subset)"}
    [:p {:class "pt-lead"}
     "This is a starting subset, not the full cloud-itonami fleet — see this program's "
     [:a {:href "https://github.com/cloud-itonami/cloud-itonami-partners"} "source repository"]
     " for the full, honest catalog and how it's extended."]
    (into [:ul {:class "pt-verticals" :style {:margin-top "1.5rem"}}]
          (map vertical-card (catalog/options))))

   (dds/section
    {:title "Apply"}
    apply-form
    ;; 送信結果は DADS の notification-banner に差し替える。既定は非表示
    ;; (#result は display:none、data-shown が付いたときだけ出す)。
    [:div {:id "result" :role "status" :aria-live "polite"}])

   [:footer {:class "pt-footer"}
    [:p "This form and its intake endpoint are open source: "
     [:a {:href "https://github.com/cloud-itonami/cloud-itonami-partners"}
      "github.com/cloud-itonami/cloud-itonami-partners"] "."]]))

;; 送信スクリプト。成功/失敗を DADS notification-banner の markup で描画する
;; (色・アイコンは dds.css の data-type="success"/"error" が持つ)。
(def submit-script
  (str
   "var ICONS = {\n"
   "  success: '<circle cx=\"12\" cy=\"12\" r=\"10\" fill=\"currentcolor\"/>"
   "<path d=\"m17.6 9.6-7 7-4.3-4.3L7.7 11l2.9 2.9 5.7-5.6 1.3 1.4Z\" fill=\"Canvas\"/>',\n"
   "  error: '<path d=\"M8.25 21 3 15.75v-7.5L8.25 3h7.5L21 8.25v7.5L15.75 21h-7.5Z\" fill=\"currentcolor\"/>"
   "<path d=\"m12 13.4-2.85 2.85-1.4-1.4L10.6 12 7.75 9.15l1.4-1.4L12 10.6l2.85-2.85 1.4 1.4L13.4 12l2.85 2.85-1.4 1.4L12 13.4Z\" fill=\"Canvas\"/>'\n"
   "};\n"
   "var LABELS = { success: '\\u6210\\u529f', error: '\\u30a8\\u30e9\\u30fc' };\n"
   "function showBanner(kind, heading, text) {\n"
   "  var r = document.getElementById('result');\n"
   "  r.className = 'dads-notification-banner';\n"
   "  r.setAttribute('data-style', 'standard');\n"
   "  r.setAttribute('data-type', kind);\n"
   "  r.setAttribute('data-shown', '');\n"
   "  var h = document.createElement('h2');\n"
   "  h.className = 'dads-notification-banner__heading';\n"
   "  h.innerHTML = '<svg class=\"dads-notification-banner__icon\" width=\"24\" height=\"24\" "
   "viewBox=\"0 0 24 24\" role=\"img\" aria-label=\"' + LABELS[kind] + '\">' + ICONS[kind] + '</svg>';\n"
   "  var ht = document.createElement('span');\n"
   "  ht.className = 'dads-notification-banner__heading-text';\n"
   "  ht.textContent = heading;\n"
   "  h.appendChild(ht);\n"
   "  var b = document.createElement('div');\n"
   "  b.className = 'dads-notification-banner__body';\n"
   "  var p = document.createElement('p');\n"
   "  p.textContent = text;\n"           ;; textContent: サーバ応答を markup として解釈させない
   "  b.appendChild(p);\n"
   "  r.replaceChildren(h, b);\n"
   "}\n"
   "document.getElementById('apply-form').addEventListener('submit', async function (ev) {\n"
   "  ev.preventDefault();\n"
   "  var form = ev.target;\n"
   "  var btn = form.querySelector('button[type=\"submit\"]');\n"
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
   "  result.removeAttribute('data-shown');\n"
   "  try {\n"
   "    var res = await fetch('/api/intake', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });\n"
   "    var data = await res.json();\n"
   "    if (res.ok && data.ok) {\n"
   "      showBanner('success', 'Received', '(id ' + data.id + ') ' + data.note);\n"
   "      form.reset();\n"
   "    } else {\n"
   "      showBanner('error', 'Submission failed', (data.errors || ['Submission failed.']).join(' '));\n"
   "    }\n"
   "  } catch (e) {\n"
   "    showBanner('error', 'Network error', 'Please try again.');\n"
   "  } finally {\n"
   "    btn.disabled = false;\n"
   "  }\n"
   "});\n"))

(def html
  (page/->page
   {:title "cloud-itonami partners — become a local operator"
    :description (str "Apply to become the local human operator of an itonami business "
                      "vertical in your territory. Non-binding expression of interest, "
                      "no fee collected.")
    :lang "en"
    :css dds-css
    :app-css app-css}
   body
   ;; script は html.core の raw-text tag。子は素の文字列で渡す。
   [:script submit-script]))

(fs/mkdirSync "public" #js {:recursive true})
(fs/writeFileSync "public/index.html" html)
(println (str "wrote public/index.html (" (count (catalog/options)) " verticals)"))
