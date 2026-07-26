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
            [css.core :as css]
            [jp-go-dds.core :as dds]
            [jp-go-dds.page :as page]
            [partners.catalog :as catalog]))

(def dds-root
  (or (some-> js/process.env.JP_GO_DDS_ROOT not-empty)
      "../../kotoba-lang/jp-go-digital-design-system"))
(def dds-css-path
  (or (some-> js/process.env.JP_GO_DDS_CSS not-empty)
      (str dds-root "/resources/jp_go_dds/dds.css")))
(def dds-css (fs/readFileSync dds-css-path "utf8"))

;; ページ固有の微調整のみ。色は DADS token 参照で raw hex は書かない。
;; select は上流 DADS の vendored subset に含まれない(dds.css に .dads-select が
;; 無い)ので、.dads-input-text__input と同じ寸法・境界・focus 挙動になるよう
;; ここで最小限に合わせる。上流 class 名は騙らず app 固有の名前を使う。
(def app-rules
  [[".pt-lead" {:color "var(--color-neutral-solid-gray-700)" :line-height 1.7}]
   [".pt-banner" {:margin-block "1.5rem"}]
   [".pt-verticals" {:list-style "none" :padding 0 :margin 0 :display "flex"
                     :flex-direction "column" :gap ".75rem"}]
   [".pt-vertical .pt-vertical-title" {:font-weight 700 :margin 0}]
   [".pt-vertical p" {:margin ".35rem 0 0"
                      :color "var(--color-neutral-solid-gray-700)"
                      :font-size ".9375rem" :line-height 1.7}]
   [".pt-vertical .pt-repo" {:font-size ".875rem"}]
   [".pt-form" {:display "flex" :flex-direction "column" :gap "1.25rem"
                :max-width "36rem"}]
   [".dads-input-text__input,.dads-textarea__textarea" {:width "100%"}]
   [".dads-textarea__textarea" {:min-height "7rem" :resize "vertical"}]
   [".pt-submit" {:margin-top ".5rem"}]
   ["#result" {:display "none" :margin-top "1.5rem"}]
   ["#result[data-shown]" {:display "block"}]
   [".pt-footer" {:border-top "1px solid var(--color-neutral-solid-gray-200)"
                  :margin-top "3rem" :padding-block "1.5rem 3rem"
                  :color "var(--color-neutral-solid-gray-600)"
                  :font-size ".875rem" :line-height 1.8}]
   [".pt-footer p" {:margin 0}]
   ["code" {:font-family "var(--font-family-mono)"
            :background "var(--color-neutral-solid-gray-50)"
            :border "1px solid var(--color-neutral-solid-gray-200)"
            :border-radius 4 :padding "1px 5px" :font-size ".9em"}]])

(def app-css (css/css {:rules app-rules}))

;; --- catalog ------------------------------------------------------------

(defn- vertical-card [[ref {:keys [code standard title description repo]}]]
  [:li {:class "pt-vertical" :data-ref ref}
   (dds/card
    [:p {:class "pt-vertical-title"}
     title " " (dds/chip-label (str standard " " code) {:color "gray" :style "outlined"})]
    [:p description]
    [:p {:class "pt-repo"} "Source: " [:code repo]])])

(defn- vertical-option [[ref {:keys [code standard title]}]]
  [ref (str title " — " standard " " code)])

;; --- form ---------------------------------------------------------------

(defn- select-field
  "公式 DADS の select(dds/select)を使う。以前はここで自前の .pt-select を
  当てていたが、それは「vendor していなかっただけ」で上流には select が
  存在する —— jp-go-dds 側に取り込んだので自前 CSS は撤去した。"
  [{:keys [id name label support required? placeholder]} options]
  (dds/form-field
   (cond-> {:label label :for id}
     support (assoc :support support :support-id (str id "-support"))
     required? (assoc :requirement "Required" :required? true))
   (dds/select (cond-> {:id id :name name}
                 required? (assoc :required true)
                 support (assoc :aria-describedby (str id "-support")))
               (into [["" placeholder]] options))))

(def apply-form
  [:form {:id "apply-form" :class "pt-form"}
   (dds/form-field
    {:label "Full name" :for "f-name" :requirement "Required" :required? true}
    (dds/input-text {:id "f-name" :name "applicantName" :type "text"
                     :required true :autocomplete "name"}))
   (dds/form-field
    {:label "Email address" :for "f-email" :requirement "Required" :required? true}
    (dds/input-text {:id "f-email" :name "applicantContact" :type "email"
                     :required true :autocomplete "email"}))
   (select-field {:id "f-vertical" :name "itonamiVerticalRef"
                  :label "Itonami vertical" :required? true
                  :placeholder "Select a vertical…"}
                 (map vertical-option (catalog/options)))
   (dds/form-field
    {:label "Territory (country)" :for "f-country"
     :requirement "Required" :required? true
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
    {:label "Your pitch / relevant experience" :for "f-pitch" :requirement "Required" :required? true}
    (dds/textarea {:id "f-pitch" :name "pitch" :required true
                   :placeholder "Why you, why this vertical, why this territory."}))
   (select-field {:id "f-capital" :name "capitalTier"
                  :label "Capital tier (self-declared)" :required? true
                  :placeholder "Select…"}
                 [["tier-1" "Tier 1 — bootstrap / solo"]
                  ["tier-2" "Tier 2 — small team, some capital"]
                  ["tier-3" "Tier 3 — funded / institutional backing"]])
   (dds/form-field
    {:label "Preferred working language" :for "f-language" :requirement "Required" :required? true}
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
(def scripts
  [[:script {:src "scittle.js"}]
   [:script {:type "application/x-scittle" :src "css_core.cljs"}]
   [:script {:type "application/x-scittle" :src "html_core.cljs"}]
   [:script {:type "application/x-scittle" :src "jp_go_dds_core.cljs"}]
   [:script {:type "application/x-scittle" :src "apply.cljs"}]])

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
   ;; scittle と依存 namespace は self-host(外部リクエストゼロを維持する)。
   ;; 読み込み順は依存順: css.core -> html.core -> jp-go-dds.core -> apply。
   scripts))

(fs/mkdirSync "public" #js {:recursive true})

;; ブラウザ側で走らせる .cljs は「コピーするだけ」— ビルド無し。
;; ライブラリ 3 本はモノレポ/兄弟 clone から取り、apply.cljs と一緒に public/ へ置く。
;; scittle は web/vendor/ に self-host 済み(web/vendor/README.md 参照)。
(def css-root
  (or (some-> js/process.env.KOTOBA_CSS_ROOT not-empty) (str dds-root "/../css")))
(def html-root
  (or (some-> js/process.env.KOTOBA_HTML_ROOT not-empty) (str dds-root "/../html")))

(def browser-assets
  [[(str css-root  "/src/css/core.cljc")              "public/css_core.cljs"]
   [(str html-root "/src/html/core.cljc")             "public/html_core.cljs"]
   [(str dds-root  "/src/jp_go_dds/core.cljc")        "public/jp_go_dds_core.cljs"]
   ["web/vendor/scittle.js"                           "public/scittle.js"]
   ["web/apply.cljs"                                  "public/apply.cljs"]])

(doseq [[src dst] browser-assets]
  (fs/copyFileSync src dst))

(fs/writeFileSync "public/index.html" html)
(println (str "wrote public/index.html (" (count (catalog/options)) " verticals, "
              (count browser-assets) " browser assets)"))
