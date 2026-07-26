(ns jp-go-dds.core
  "デジタル庁デザインシステム(DADS)の HTML コンポーネント例
  (digital-go-jp/design-system-example-components-html, MIT)を cljc hiccup で
  提供するライブラリ。ADR-2607141915 (com-junkawasaki/root)。

  - markup と class 名(`dads-*`)は上流の HTML 例に忠実に従う。CSS は
    resources/jp_go_dds/dds.css に vendor(上流 commit と MIT 表記入り。
    scripts/vendor.sh で再生成 — 手編集禁止)。
  - light mode 前提(上流に dark palette は無い)。`page` は color-scheme light を明示。
  - 上流に無い layout 補助(container/section/grid/stack/card/hero)は
    `dds-ext-*` prefix + ext-css で明確に区別する(上流 class と混ぜない)。
  - 純 cljc — 描画は kotoba-lang/html(html.core/->html)。"
  (:require [clojure.string :as str]
            [css.core :as css]))

;; --- primitives ---------------------------------------------------------------

(defn button
  "DADS button。opts: :type(:solid-fill|:outline|:text 既定 :solid-fill)
  :size(\"lg\"|\"md\"|\"sm\"|\"xs\" 既定 \"md\") :href(あれば <a>) :submit? :disabled :id :aria-label"
  ([label] (button label {}))
  ([label {:keys [type size href submit? disabled id aria-label]
           extra :attrs
           :or {type :solid-fill size "md"}}]
   ;; :attrs は任意属性の passthrough(data-* / aria-* / hx-* 等)。上流の
   ;; class / data-type / data-size は **後**に merge しており consumer からは
   ;; 上書きできない — component の同一性を壊させないため。これが無いと
   ;; consumer は selectable hook を得るために DADS button の CSS を app 側へ
   ;; 複製することになる(kotoba-ui.shell が :attrs を持つのと同じ理由)。
   (let [attrs (cond-> (merge (or extra {})
                              {:class "dads-button"
                               :data-type (name type)
                               :data-size size})
                 id (assoc :id id)
                 aria-label (assoc :aria-label aria-label))]
     (if href
       [:a (assoc attrs :href href) label]
       [:button (cond-> (assoc attrs :type (if submit? "submit" "button"))
                  disabled (assoc :disabled true))
        label]))))

(defn heading
  "DADS heading。level 1-6、size は上流の data-size(\"64\"〜\"16\")。"
  ([level text] (heading level text {}))
  ([level text {:keys [size id]
                :or {size (get {1 "45" 2 "32" 3 "24" 4 "20" 5 "18" 6 "16"} level "20")}}]
   [(keyword (str "h" level))
    (cond-> {:class "dads-heading" :data-size size} id (assoc :id id))
    text]))

(defn accordion
  "DADS accordion(native details/summary)。opts: :id :open? :heading-level(既定 3)"
  [summary-text content {:keys [id open? heading-level] :or {heading-level 3}}]
  [:details (cond-> {:class "dads-accordion"} open? (assoc :open true))
   [:summary (cond-> {:class "dads-accordion__summary"} id (assoc :id id))
    [:span {:class "dads-accordion__icon"}
     [:svg {:class "dads-accordion__icon-svg" :width 24 :height 24
            :viewBox "0 0 24 24" :aria-hidden "true"}
      [:path {:d "M3.3 7.3L12 16L20.7 7.3" :fill "none"
              :stroke "currentcolor" :stroke-width 2}]]]
    [(keyword (str "h" heading-level)) summary-text]]
   [:div {:class "dads-accordion__content"} content]])

(defn input-text
  "DADS input-text(素の input。ラベルは form-field で)。opts は input 属性を透過。"
  [{:keys [size] :or {size "md"} :as opts}]
  [:span {:class "dads-input-text"}
   [:input (-> opts
               (dissoc :size)
               (assoc :class "dads-input-text__input" :data-size size)
               (update :type #(or % "text")))]])

(defn textarea
  [{:keys [rows] :or {rows 4} :as opts}]
  [:span {:class "dads-textarea"}
   [:textarea (-> opts (assoc :class "dads-textarea__textarea" :rows rows))]])

(defn checkbox
  "DADS checkbox。opts: :id :checked :disabled :size(既定 \"md\")"
  [label {:keys [id checked disabled size] :or {size "md"}}]
  [:label {:class "dads-checkbox" :data-size size}
   [:span {:class "dads-checkbox__checkbox"}
    [:input (cond-> {:class "dads-checkbox__input" :type "checkbox"}
              id (assoc :id id)
              checked (assoc :checked true)
              disabled (assoc :disabled true))]]
   [:span {:class "dads-checkbox__label"} label]])

(defn form-field
  "DADS form-control-label で control を包む。
  opts: :label :for :support :size(既定 \"md\") :support-id
  :requirement(必須/任意マーカーのテキスト) :required?(true で赤字)
  :status(グレーの塗りバッジ。状態表示用で必須マーカーではない)"
  [{:keys [label for support status requirement required? size support-id]
    :or {size "md"}} control]
  [:div {:class "dads-form-control-label" :data-size size}
   [:label (cond-> {:class "dads-form-control-label__label"} for (assoc :for for))
    label
    ;; 必須/任意の表示は __requirement(data-required=true で赤字)。
    ;; __status はグレーの塗りバッジで、「入力済み」等の状態表示用の別物 ——
    ;; 必須マーカーに __status を使うのは意味的に誤り(実測でこれをやっていた)。
    (when requirement
      [:span {:class "dads-form-control-label__requirement"
              :data-required (if required? "true" "false")}
       requirement])
    (when status [:span {:class "dads-form-control-label__status"} status])]
   (when support
     [:p (cond-> {:class "dads-form-control-label__support-text"}
           support-id (assoc :id support-id))
      support])
   [:div control]])

(defn select
  "DADS select。markup は上流 select/with-form-control-label.html に忠実
  (span.dads-select > span.dads-select__control > select + chevron svg)。

  opts: :id :name :size(既定 \"md\") :required :disabled :aria-describedby
        :error(あれば dads-select__error-text を出す)
        :attrs(select への追加属性)
  options: [[value label] ...]。value が nil/\"\" の項目は placeholder として
  disabled + selected にする(上流の「選択してください」と同じ扱い)。"
  [{:keys [id name size required disabled aria-describedby error attrs]
    :or {size "md"}}
   options]
  [:span {:class "dads-select"}
   [:span {:class "dads-select__control"}
    (into [:select (cond-> (merge {:class "dads-select__select" :data-size size} attrs)
                     id (assoc :id id)
                     name (assoc :name name)
                     required (assoc :required true)
                     disabled (assoc :disabled true)
                     aria-describedby (assoc :aria-describedby aria-describedby))]
          (map (fn [[v label]]
                 (if (or (nil? v) (= "" v))
                   [:option {:value "" :disabled true :selected true} label]
                   [:option {:value v} label]))
               options))
    [:svg {:class "dads-select__chevron" :width 16 :height 16
           :viewBox "0 0 24 24" :aria-hidden "true"}
     [:path {:d "M12 17L3 8L4 7L12 15L20 7L21 8L12 17Z" :fill "currentcolor"}]]]
   (when error
     [:span {:class "dads-select__error-text"} error])])

(defn table
  "DADS table。{:caption :headers [..] :rows [[..]..] :row-header? bool}
  row-header? true なら各行の先頭セルを th scope=row にする。"
  [{:keys [caption headers rows row-header?]}]
  [:div {:class "dads-table"}
   [:table {:class "dads-table__table"}
    (when caption [:caption caption])
    (when (seq headers)
      [:thead
       (into [:tr]
             (map (fn [h] [:th {:class "dads-table__col-header" :scope "col"} h])
                  headers))])
    (into [:tbody]
          (map (fn [row]
                 (into [:tr]
                       (map-indexed
                        (fn [i cell]
                          (if (and row-header? (zero? i))
                            [:th {:class "dads-table__row-header" :scope "row"} cell]
                            [:td cell]))
                        row)))
               rows))]])

(defn chip-label
  "DADS chip-label。opts: :color(\"blue\"|\"gray\"|…) :style(既定 \"text\")"
  ([label] (chip-label label {}))
  ([label {:keys [color style] :or {color "blue" style "text"}}]
   [:span {:class "dads-chip-label" :data-style style :data-color color} label]))

(defn divider
  ([] (divider {}))
  ([_opts] [:hr {:class "dads-divider"}]))

;; notification-banner の icon path は上流 src/components/notification-banner/
;; {success,error,warning,info-1}.html をそのまま写したもの。fill="Canvas" も
;; 上流どおり(CSS system color = バナー背景色で抜く指定)。
(def ^:private banner-icons
  {:success {:label "成功"
             :paths [[:circle {:cx 12 :cy 12 :r 10 :fill "currentcolor"}]
                     [:path {:d "m17.6 9.6-7 7-4.3-4.3L7.7 11l2.9 2.9 5.7-5.6 1.3 1.4Z" :fill "Canvas"}]]}
   :error   {:label "エラー"
             :paths [[:path {:d "M8.25 21 3 15.75v-7.5L8.25 3h7.5L21 8.25v7.5L15.75 21h-7.5Z" :fill "currentcolor"}]
                     [:path {:d "m12 13.4-2.85 2.85-1.4-1.4L10.6 12 7.75 9.15l1.4-1.4L12 10.6l2.85-2.85 1.4 1.4L13.4 12l2.85 2.85-1.4 1.4L12 13.4Z" :fill "Canvas"}]]}
   :warning {:label "警告"
             :paths [[:path {:d "M1 21 12 2l11 19H1Z" :fill "currentcolor"}]
                     [:path {:d "M13 15h-2v-5h2v5Z" :fill "Canvas"}]
                     [:circle {:cx 12 :cy 17 :r 1 :fill "Canvas"}]]}
   :info-1  {:label "インフォメーション"
             :paths [[:circle {:cx 12 :cy 12 :r 10 :fill "currentcolor"}]
                     [:circle {:cx 12 :cy 8 :r 1 :fill "Canvas"}]
                     [:path {:d "M11 11h2v6h-2z" :fill "Canvas"}]]}
   :info-2  {:label "インフォメーション"
             :paths [[:circle {:cx 12 :cy 12 :r 10 :fill "currentcolor"}]
                     [:circle {:cx 12 :cy 8 :r 1 :fill "Canvas"}]
                     [:path {:d "M11 11h2v6h-2z" :fill "Canvas"}]]}})

(defn notification-banner
  "DADS notification-banner。opts:
  :type(:success|:error|:warning|:info-1|:info-2 既定 :info-1)
  :style(\"standard\"|\"color-chip\" 既定 \"standard\")
  :heading(見出しテキスト。必須相当) :heading-level(既定 2)
  :timestamp({:datetime \"2024-07-01\" :text \"2024年7月1日\"})
  :actions(hiccup の seq。無ければ actions ブロックを出さない)
  :id(root の id) :attrs(root への追加属性)

  上流との差分: 上流の例は常に閉じるボタンを持つが、閉じる挙動は JS 依存なので
  本ライブラリでは既定で出さない(静的 SSR ページに動かない閉じるボタンを置かない)。
  必要なら :closable? true + :close-id で上流どおりの markup を出す。"
  [{:keys [type style heading heading-level timestamp actions id attrs
           closable? close-id close-label]
    :or {type :info-1 style "standard" heading-level 2
         close-label "閉じる" close-id "notification-banner-close"}}
   & body]
  (let [{:keys [label paths]} (get banner-icons type (get banner-icons :info-1))]
    [:div (cond-> (merge {:class "dads-notification-banner"
                          :data-style style
                          :data-type (name type)}
                         attrs)
            id (assoc :id id))
     [(keyword (str "h" heading-level)) {:class "dads-notification-banner__heading"}
      (into [:svg {:class "dads-notification-banner__icon" :width 24 :height 24
                   :viewBox "0 0 24 24" :role "img" :aria-label label}]
            paths)
      [:span {:class "dads-notification-banner__heading-text"} heading]]
     (when closable?
       [:button {:class "dads-notification-banner__close" :type "button"
                 :aria-labelledby close-id}
        [:svg {:class "dads-notification-banner__close-icon" :width 24 :height 24
               :viewBox "0 0 24 24" :aria-hidden "true"}
         [:path {:d "m6.4 18.6-1-1 5.5-5.6-5.6-5.6 1.1-1 5.6 5.5 5.6-5.6 1 1.1L13 12l5.6 5.6-1 1L12 13l-5.6 5.6Z"
                 :fill "currentcolor"}]]
        [:span {:id close-id :class "dads-notification-banner__close-label"} close-label]])
     (into [:div {:class "dads-notification-banner__body"}
            (when timestamp
              [:p {:class "dads-notification-banner__timestamp"}
               [:time {:datetime (:datetime timestamp)} (:text timestamp)]])]
           body)
     (when (seq actions)
       (into [:div {:class "dads-notification-banner__actions"}] actions))]))

;; --- layout 拡張(上流に無い。dds-ext-* prefix、ext-css が対) --------------------

(def ext-rules
  "上流に無い layout 補助を **EDN で** 書いたもの(生 CSS 記法を持ち込まない)。
  DADS token のみ参照し raw hex は書かない。

  map ではなく `[selector decls]` の **ベクタ列**にしているのは順序のため —
  Clojure の map は要素数が閾値を超えるとハッシュ順になり、CSS のカスケード
  (後勝ち)が壊れる。"
  [[".dds-ext-container" {:max-width "64rem" :margin-inline "auto"
                          ;; page は viewport-fit=cover を張るので notch /
                          ;; home indicator の領域はこちらの責任になる。これが
                          ;; 無いと notched device で内容がその下に潜る。
                          :padding-inline (str "max(1rem,env(safe-area-inset-left)) "
                                               "max(1rem,env(safe-area-inset-right))")}]
   [".dds-ext-section" {:padding-block "3rem"
                        :border-top "1px solid var(--color-neutral-solid-gray-200)"}]
   [".dds-ext-section:first-of-type" {:border-top "none"}]
   [".dds-ext-grid" {:display "grid"
                     :grid-template-columns "repeat(auto-fill,minmax(var(--dds-ext-grid-min,16rem),1fr))"
                     :gap "1.5rem"}]
   [".dds-ext-grid>*" {:min-width 0}]
   [".dds-ext-stack" {:display "flex" :flex-direction "column"
                      :gap "var(--dds-ext-stack-gap,1rem)"}]
   [".dds-ext-row" {:display "flex" :flex-wrap "wrap" :gap "1rem" :align-items "center"}]
   [".dds-ext-card" {:border "1px solid var(--color-neutral-solid-gray-200)"
                     :border-radius 12 :padding "1.5rem"
                     :background "var(--color-neutral-white)"}]
   [".dds-ext-hero" {:padding-block "4rem 3rem" :text-align "center"}]
   [".dds-ext-hero .dads-heading" {:margin "0 0 1rem"}]
   [".dds-ext-center" {:display "flex" :flex-direction "column" :align-items "center"
                       :gap "1rem" :text-align "center"}]
   [".dds-ext-lead" {:color "var(--color-neutral-solid-gray-600)"
                     :font-size "1.125rem" :line-height 1.7 :margin 0}]
   ["body" {:margin 0 :background "var(--color-neutral-white)"
            :color "var(--color-neutral-solid-gray-800)"
            :font-family "var(--font-family-sans)"}]
   ["img,svg" {:max-width "100%" :height "auto"}]
   ;; 上流 .dads-table は overflow を持たず .dads-table__table にも width 指定が
   ;; 無いので、列が多い/内容が長い表は狭い viewport で**ページ全体**を横スクロール
   ;; させる(実測: 500px 幅で body scrollWidth が clientWidth を超える)。表は
   ;; 自分の中だけでスクロールさせる。上流 class を触るが、これは restyle ではなく
   ;; はみ出しの封じ込め(ext-rules は既に body / img,svg も指定している)。
   [".dads-table" {:max-width "100%" :min-width 0 :overflow-x "auto"}]
   ;; light-only を CSS にも明示する。上流デジタル庁に dark palette は無く
   ;; (ADR-2607261600 決定2)、`page` は meta を張っているが、form control を
   ;; light に従わせるには CSS 側の宣言も要る。dark を自作するのではなく
   ;; 「light だけである」ことを述べている。
   [":root" {:color-scheme "light"}]
   ["body" {:padding-bottom "env(safe-area-inset-bottom)"}]])

(def ext-media
  "viewport / 入力デバイス依存の ext 規則。上流の vendored subset は
  safe-area / tap-target / breakpoint を持たないため、DADS を素で使うと
  kotoba-lang/design-quality の決定論的 HIG/WCAG 監査(ADR-2607132300)で
  65.34 になる(safe-area 0.13 / tap-targets 0.13 / responsive 0.07 /
  color-scheme 0.06 を落とす)。これは design system 層の欠落であって
  個々のページの責任ではないので、各 consumer の app CSS ではなくここに置く。

  entry が少ないので map の順序は問題にならない(ext-rules がベクタ列なのは
  カスケード順のため — そちらの docstring 参照)。"
  {;; HIG は touch で 44pt 以上を要求する。coarse pointer に限定し、desktop では
   ;; DADS 自身の寸法を保つ(全環境で太らせない)。
   "(pointer:coarse)"
   [[".dads-button,.dads-accordion__summary" {:min-height "44px"}]]

   "(max-width:48rem)"
   [[".dds-ext-section" {:padding-block "2rem"}]
    [".dds-ext-hero" {:padding-block "2.5rem 2rem"}]
    [".dds-ext-grid" {:--dds-ext-grid-min "100%"}]]})

(def ext-css
  "ext-rules + ext-media を CSS 文字列にしたもの(page が <style> に流し込む)。"
  (css/css {:rules ext-rules :media ext-media}))

(defn container [& children] (into [:div {:class "dds-ext-container"}] children))
(defn section
  [{:keys [title id]} & children]
  (into [:section (cond-> {:class "dds-ext-section"} id (assoc :id id))
         (when title (heading 2 title {:size "32"}))]
        children))
(defn grid
  [{:keys [min]} & children]
  (into [:div (cond-> {:class "dds-ext-grid"}
                min (assoc :style {:--dds-ext-grid-min min}))]
        children))
(defn stack [& children] (into [:div {:class "dds-ext-stack"}] children))
(defn row [& children] (into [:div {:class "dds-ext-row"}] children))
(defn card [& children] (into [:div {:class "dds-ext-card"}] children))
