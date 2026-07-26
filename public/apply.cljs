;; 公開申込フォームの送信処理 — scittle(ブラウザ内 ClojureScript)が実行する。
;; 姉妹デモ(isic-6310/6399/7810・fleet カタログ)の search.cljs / catalog.cljs と
;; 同じパターンで、**生 JS も生 HTML も書かない**。
;;
;; 成否バナーは jp-go-dds.core/notification-banner —— ページを生成した
;; web/generate.cljs が使うのと**同一の関数** —— を html.core で文字列化して
;; 差し込む。上流デジタル庁の SVG アイコンや class 名をここに書き写す必要は無く、
;; ライブラリ側を直せばサーバ側描画と同時にここも直る。
;;
;; scittle と依存 3 namespace は public/ に self-host されている
;; (web/vendor/README.md: この申込フォームは外部リクエストゼロを維持する)。
(ns partners.apply
  (:require [html.core :as h]
            [jp-go-dds.core :as dds]))

(defn- el [id] (js/document.getElementById id))

(defn- show-banner!
  "結果表示。`text` は html.core がエスケープするので、サーバ応答が markup として
  解釈されることはない。"
  [kind heading text]
  (let [r (el "result")]
    (set! (.-innerHTML r)
          (h/->html (dds/notification-banner {:type kind :heading heading}
                                             [:p text])))
    (.setAttribute r "data-shown" "")))

(defn- field
  "空文字は nil に落とす(未入力の任意項目を空文字で送らない)。"
  [fd k]
  (let [v (.get fd k)]
    (when-not (or (nil? v) (= "" v)) v)))

(defn- request-body [form]
  (let [fd (js/FormData. form)]
    #js {:applicantName      (field fd "applicantName")
         :applicantContact   (field fd "applicantContact")
         :itonamiVerticalRef (field fd "itonamiVerticalRef")
         :territory #js {:country (some-> (field fd "territoryCountry") .toUpperCase)
                         :region  (field fd "territoryRegion")}
         :pitch        (field fd "pitch")
         :capitalTier  (field fd "capitalTier")
         :language     (field fd "language")}))

(defn- errors-text [data]
  (let [errs (.-errors data)]
    (if (and errs (pos? (.-length errs)))
      (.join errs " ")
      "Submission failed.")))

(defn- submit! [ev]
  (.preventDefault ev)
  (let [form (.-target ev)
        btn  (.querySelector form "button[type=\"submit\"]")]
    (set! (.-disabled btn) true)
    (.removeAttribute (el "result") "data-shown")
    (-> (js/fetch "/api/intake"
                  #js {:method  "POST"
                       :headers #js {"content-type" "application/json"}
                       :body    (js/JSON.stringify (request-body form))})
        (.then (fn [res] (.then (.json res) (fn [data] #js [res data]))))
        (.then (fn [pair]
                 (let [res (aget pair 0) data (aget pair 1)]
                   (if (and (.-ok res) (.-ok data))
                     (do (show-banner! :success "Received"
                                       (str "(id " (.-id data) ") " (.-note data)))
                         (.reset form))
                     (show-banner! :error "Submission failed" (errors-text data))))))
        (.catch (fn [_] (show-banner! :error "Network error" "Please try again.")))
        (.finally (fn [] (set! (.-disabled btn) false))))))

(.addEventListener (el "apply-form") "submit" submit!)
