# web/vendor — self-hosted 第三者アセット

このディレクトリのファイルは**手編集禁止**（上流からそのまま取得したもの）。
`web/generate.cljs` が `public/` へコピーする。

## scittle.js

- 上流: <https://github.com/babashka/scittle> (borkdude)
- バージョン: **0.6.22**
- 取得元: `https://cdn.jsdelivr.net/npm/scittle@0.6.22/dist/scittle.js`
- ライセンス: EPL-1.0
- sha256: `9ffcf57c4ba9002dcc90c4f2048f166dda19d3d24dfb43f47befae223fb9fe25`
  （更新時は必ず再計算すること）

### なぜ CDN でなく self-host か

姉妹デモ（isic-6310/6399/7810・fleet カタログ）は jsDelivr の CDN から
scittle を読んでいるが、**この申込フォームは「既定で外部リクエストゼロ」を
維持する**（jp-go-dds / ADR-2607141915 の設計方針。公開の申込フォームとして
第三者 CDN への接続を発生させない）。オーナー判断で self-host を選択した。

更新手順:

```bash
curl -fL -o web/vendor/scittle.js \
  https://cdn.jsdelivr.net/npm/scittle@<version>/dist/scittle.js
shasum -a 256 web/vendor/scittle.js   # 上の sha256 を更新
```
