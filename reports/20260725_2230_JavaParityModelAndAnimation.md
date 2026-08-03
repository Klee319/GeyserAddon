# Task

統合版に変換されたリソパで「モデル・アニメーションが Java と同じにならない」問題を調査し、
根拠付きで修正する。KGI: Java と同じ描画（到達可能な範囲を明示）。

# 調査（根拠）

## 参照した一次情報

- Kas-tle **java2bedrock.sh** `converter.sh`（本リポジトリが「verbatim移植」と称している元実装）
- GeyserMC **Rainbow** `master`（2026-07-13 更新、公式のパック生成器）
  - `mapping/geometry/GeometryMapper.java`
  - `mapping/animation/AnimationMapper.java`
  - `pack/geometry/BedrockGeometry.java`
- Microsoft 公式 Bedrock リファレンス `minecraft:geometry.v1.21.0`
- 実配信物 `.tmp_live_pack_fp/*`（2026-07-25 08:47 のライブパック）
- 実パック `trinityforge/resourcepack/trinityforge-items`（3D モデル 49件）

## 確定した事実

| # | 事実 | 根拠 |
|---|------|------|
| 1 | 3D 形状は生成・配信されていた（形状欠落ではない） | ライブ geo に cube 72個 / 34個 |
| 2 | 三人称は java2bedrock と完全一致 | `x.position=[6.5,4,0.5]` ← Java `[-6.5,4,0.5]` |
| 3 | 一人称は Java の display 平行移動を**全捨て**していた | ライブ `x.position=[-0,0,0]`、`FP_EXPERIMENT_TRANSLATION_FACTOR=0` |
| 4 | 捨てた原因は root scale が子 bone の position にも掛かること | Bedrock の bone 合成則。root scale 2.0 で大斧 `[0,4,9]`→`[0,8,18]` |
| 5 | Bedrock と Java の手座標系は 1:1（root scale は「見た目サイズ補正」であって単位変換ではない） | Rainbow は root scale を一切使わず display scale をそのまま適用 |
| 6 | 面 UV 回転 90/270 が破棄されていた（18,654面中 6,218面＝33%） | 実パック集計。ただし全て east/west の薄い側面 |
| 7 | Bedrock は per-face `uv_rotation` を持つ（1.21.0+、90/180/270） | MS 公式リファレンス。Rainbow も `face.rotation()` をそのまま転送 |
| 8 | `*_lefthand` を読んでいなかった | `JavaPackReader` に該当キーなし。短剣は右手 z=+90 / 左手 z=-90 で符号反転では再現不能 |
| 9 | 2アイテムが attachable ゼロ＝手持ちが平面のまま | `netherite_spear` CMD 119/120。`minecraft:select`(display_context) の分岐先が client-jar のバニラモデルで pack 内に無く display 解決不能 |
| 10 | cube 回転の Y 符号は現行が正しい（j2b は古い） | Rainbow `getBedrockRotation`: Y は反転しない。build 39 で Z の反転を廃止した旨のコメントあり |

## 誤りだった仮説（自分で潰したもの）

- **`texture_size` の解釈ずれ**: 実パックは `texture_size:[32,32]` 宣言があっても UV 実値は全て 0..16 レンジ。
  現行の `scale = textureWidth / 16` が正しい。Rainbow も `sprite.width()/16` で一致。
- **`visible_bounds` による frustum culling が大斧消失の原因**: 実測すると全モデルが既定の 4×4.5 box に収まる
  （最大でも 16unit × display 2.0 × root 1.5 = 3 block）。原因ではない。ただし上限硬直化としては有効なので実装は入れた。

# Files Changed

- `paper/.../pack/BedrockGeometryConverter.java` — `uv_rotation` 転送 / `convertTranslation` の mirrorX 化 / `computeBounds` / `requiresUvRotationFormat`
- `paper/.../pack/BedrockAttachableWriter.java` — FP 平行移動復活＋root scale 相殺 / oversize 系ヒューリスティクス全撤去 / visible bounds 算出 / format_version 切替 / offhand 配線
- `paper/.../pack/JavaModelDisplay.java` — `firstperson_lefthand` / `thirdperson_lefthand` スロット追加
- `paper/.../pack/JavaPackReader.java` — 同スロットのパース
- `paper/.../pack/VanillaBuiltinDisplays.java` — 未解決の `item/*` バニラモデルを handheld にフォールバック
- `core/.../config/GeyserExtraConfig.java` — `debugZeroFirstPersonTranslation` 削除
- `paper/src/test/.../BedrockAttachableWriterTest.java` / `BedrockGeometryConverterTest.java` — 期待値更新＋新規ケース
- `paper/src/test/.../TrinityForgePackConversionIT.java`（新規）— 実パックを通す回帰テスト＋成果物ダンプ
- `README.md`

# 修正内容

1. **一人称の平行移動を復活させ、root scale で割ってから書き出す。**
   Bedrock は親 scale が子 position に掛かるが Java は掛からない。割ることで Java と同じオフセットになる。
   例（短剣）: `x.position = [-0.75, 2.13, 1.41]`（旧: `[0,0,0]`）。
2. **root base pose をアイテム非依存の固定値に戻す。** `oversizeFpRootPosition` / `adaptiveFpRootScale` /
   `FP_EXPERIMENT_TRANSLATION_FACTOR` / `debugZeroFirstPersonTranslation` を削除。
   アイテム差は Java display 側だけが持つ、という Java の構造に合わせた。
3. **面 UV 回転を `uv_rotation` として出力**（必要なモデルだけ geometry format を 1.21.0 に）。
4. **オフハンドが `*_lefthand` を使う**。宣言があるとき X 再反転しない。
5. **未解決バニラ親を handheld にフォールバック**。attachable ゼロ（＝手持ちが平面）を解消。
6. **visible bounds を実メッシュから算出**（既定値より縮まない）。

# Verification

- `:paper:test` / `:core:test` = **108 tests / 3 failed**。失敗は全て `GuiIconTransformerTest` の
  **既存 stale**（着手前ベースラインと同一の 3件、本変更と無関係）。
- 実パック回帰 `TrinityForgePackConversionIT`:
  - 86 CMD エントリ → **3D 28 / flat 58 / SKIPPED 0**（修正前は SKIPPED 2）
  - `uv_rotation` を出したモデル 28件（＝3D 全件）
  - 一人称オフセットが全件 |16px| 以内、かつ Java が平行移動を持つ物は 0 でない
  - 成果物ダンプ: `paper/build/pack-conversion-dump/`（Rainbow 出力との差分比較用）
- ビルド: `paper/build/libs/geyserExtra-1.0.0-SNAPSHOT.jar`, `extension/build/libs/extension-1.0.0-SNAPSHOT.jar`

# 未配備・ユーザー作業

1. 両 jar の配置とサーバー**フル再起動**（pack 再生成のため）。
2. **live `config.json` の `customItems.attachableGeneration.firstPersonBasePose` を削除**すること。
   現在 `[4,10,-2] / scale 2.0` が入っているが、これは撤去した oversize 補正込みで調整された値。
   既定（`[90,60,-40] / [4,10,4] / 1.5`）に戻す必要がある。
3. Bedrock クライアントのパック再取得。

# 原理的な上限（KGI に対する正直な限界）

Bedrock の attachable 3D は**装備スロット専用**。インベントリ・ドロップ・額縁は 2D スプライト固定で、
Java の `display.gui` / `ground` / `fixed` は再現不可能（`20260725_1426_DroppedItemRenderResearch.md` と同結論）。
「Java と全く同じ描画」は**手持ち（一人称・三人称・頭）に限れば到達可能**、それ以外は不可。

# 次段（ユーザー承認済み方針）

Rainbow で同一アイテムのパックを生成し、`paper/build/pack-conversion-dump/` と差分比較して残差を潰す。
特に検証したい点:
- cube bone の pivot: 本実装は Java 準拠の `[0,8,0]`、Rainbow は cube AABB 中心。両者が食い違っている。
- Rainbow は単 bone ＋ 座標軸置換（`X→Z, Y→X, Z→Y`, base `-90°` / `+12.5Y`）で root scale を使わない。
  本実装の j2b 多段 bone ＋ root scale 1.5 とどちらが実機で Java に近いか。

---

# 追補（2026-07-25 23:30）— 実機フィードバック後の2件

配備後のユーザー実機確認: 「2D モデルのテクスチャは一人称/三人称とも OK・スケールも反映。
**オフハンドはダメ**。**3D モデルのテクスチャは全部だめ**」。前者は前回変更で解決、後者2件を追撃修正した。

## A. 3D モデルのテクスチャ総崩れ（私が入れた回帰）

**症状の対象範囲が根拠**: 崩れたのは 3D モデル **28件**＝私が `uv_rotation` を出して
geometry `format_version` を `1.21.0` に上げたモデルと**完全に一致**する（flat 58件は無傷）。

**原因**: パック `manifest.json` の `min_engine_version` が `[1, 16, 100]` にハードコードされたまま、
geometry だけ `1.21.0` を宣言していた。1.16.100 を宣言したパックに 1.21.0 の geometry を入れると
クライアントは古い解釈で読むため per-face UV が壊れる。
GeyserMC Rainbow は `PackConstants.ENGINE_VERSION` と `BedrockGeometry.FORMAT_VERSION` を
**どちらも 1.21.0 に揃えて**いる。

**修正**: `AutoBedrockPackBuilder.requiresModernGeometry()` を新設し、
`1.21.0` の geometry を1件でも出したら manifest も `[1, 21, 0]` に連動させる。
出していなければ従来どおり `[1, 16, 100]` のまま（古いクライアントを不要に締め出さない）。

**ロールバック弁**: `customItems.attachableGeneration.faceUvRotation`（既定 `true`）。
`false` にすると `uv_rotation` を一切出さず、180° は旧来の UV 点対称化で近似、90°/270° は破棄し、
geometry も manifest も 1.16 系のままになる。**再ビルド不要で切り戻せる**。
Gson はコンストラクタを通さないため、既存 config で誤って OFF にならないよう `Boolean` で保持し
「キー無し = true」にしてある。

## B. オフハンドが崩れる（前回の私の修正が誤り）

**根拠（バニラ一次ソース）**: `net.minecraft.client.renderer.block.model.ItemTransform#apply`

```java
public void apply(boolean pLeftHand, PoseStack pPoseStack) {
   if (this != NO_TRANSFORM) {
      float f = rotation.x(), f1 = rotation.y(), f2 = rotation.z();
      if (pLeftHand) { f1 = -f1; f2 = -f2; }      // ← 回転 Y/Z を反転
      int i = pLeftHand ? -1 : 1;
      pPoseStack.translate(i * translation.x(), translation.y(), translation.z());  // ← X 反転
      ...
```

さらに `ItemTransforms.Deserializer` は `*_lefthand` が無いとき**右手の transform オブジェクトを代入**する
（`NO_TRANSFORM` ではない）ので `apply` の反転はそのまま走る。
つまり**左手の反転はスロットの有無に関係なく無条件**であり、「`*_lefthand` があるから反転しない」は誤り。

**前回の私の実装の誤り**: `*_lefthand` を宣言しているモデルでは X ミラーを外し、回転は宣言値をそのまま出していた。
X については Java の `-1` と Bedrock 側の X ミラーが打ち消し合うので**結果的に正しかった**が、
**回転 Y/Z の反転を入れていなかった**。
実パックの短剣は右手 `rotation.z = +90` / 左手 `-90`。作者の `-90` は
「Java が反転して +90 になる」ことを見越した書き方なので、`-90` をそのまま出すと**刃が 180° 逆を向く**。

**実パックでの裏付け**: 左右両スロットを持つ 102 スロットのうち
rot Y 反転 98 / rot Z 反転 84 / rot X 一致 84。作者は一貫して反転前提の値を書いている。

**修正**: `BedrockGeometryConverter.applyJavaLeftHandRotation()`（`(x, -y, -z)`）を新設し、
オフハンドは**スロットの出所によらず**必ず適用してから Java→Bedrock 変換に渡す。
`buildHoldAnimation` の引数を `mirrorX` から `offHand` に変更（`mirrorX = !offHand`）。

**変換後の実パック検証**（`TrinityForgePackConversionIT` のダンプ 87 スロット）:
- `*_lefthand` 宣言あり **28件**（＝3D 全件）: オフハンド Z が主手と同じ `+90`（修正前は `-90`）
- 宣言なし **59件**: オフハンドは主手のミラー（`-Z`, `-X`）。これも Java と同じ挙動

# 追補の Files Changed

- `core/.../config/GeyserExtraConfig.java` — `faceUvRotation`（`Boolean`, 既定 true）追加
- `paper/.../pack/AutoBedrockPackBuilder.java` — `requiresModernGeometry()` 新設・manifest 連動、死んだ 1引数オーバーロード削除
- `paper/.../pack/BedrockGeometryConverter.java` — `applyJavaLeftHandRotation()` 新設 / `emitUvRotation` を全段に配線
- `paper/.../pack/BedrockAttachableWriter.java` — `offHand` 引数化・左手則適用 / `faceUvRotation` を変換へ伝播
- `paper/.../pack/JavaModelDisplay.java` — 不要になった `hasLeftHandTransform` 削除
- テスト3件追加（`faceUvRotationCanBeDisabled` / `faceUvRotationDefaultsOn` / manifest 連動アサート）、オフハンド既存2件を修正後の正解値へ更新

# 追補の Verification

- `:paper:test` / `:core:test` = **110 tests / 3 failed**。失敗は着手前と同一の `GuiIconTransformerTest` 3件（既存 stale）
- 実パック 87 スロットのオフハンド出力を上記のとおり全件分類・確認
- 配備: `plugins/geyserExtra-1.0.0-SNAPSHOT.jar` 23:29（extension 側は変更なしのため 23:21 のまま）

# ユーザー質問への回答: ドロップ/額縁が 2D・16×16 止まりなのは制限か

**はい、Bedrock 側の原理的制限です。**回避策はありません。
Bedrock の attachable（3D ジオメトリ＋アニメーション）は**装備スロットに持ったエンティティにのみ**適用されます。
インベントリ・ドロップアイテム・額縁・体力バー横のアイコンは、すべて custom item の
`minecraft:icon` に指定した**2D スプライトをそのまま描画**する経路で、ジオメトリを差し込む口がありません。
したがって Java の `display.gui` / `display.ground` / `display.fixed` は再現不能です。
（本リポジトリの `20260725_1426_DroppedItemRenderResearch.md` も独立に同じ結論。）

KGI「Java と全く同じ描画」は**手持ち（一人称・三人称・オフハンド・頭）に限れば到達可能**、
インベントリ/ドロップ/額縁は**到達不能**、というのが正直な線引きです。

---

# 追補2（2026-07-26 00:10）— 実機フィードバック第2回

ユーザー報告: 三人称は 2D/3D/オフハンド全て OK ／ **一人称メインハンドの位置が少し高い（角度は OK）**
／ **一人称オフハンドが 2D/3D とも映らない** ／ **大斧だけメインハンド範囲に描画されない（攻撃を振れば見える）**
／ **3D モデルのインベントリ用テクスチャが UV シート状態**。

## C. 一人称オフハンドが映らない（構造的原因を特定・修正済）

**根拠**: 生成物の root ベースポーズを並べると、三人称と一人称で対称性が違う。

| スロット | root rotation | root position | ミラー不変か |
|---|---|---|---|
| thirdperson（両手共通） | `[90, 0, 0]` | `[0, 13, -3]` | **不変**（rotY/rotZ/posX が全部 0） |
| firstperson 3D | `[90, 60, -40]` | `[4, 10, 4]` | **不変でない** |
| firstperson flat | `[90, 60, -40]` | `[0, 15, 4]` | **不変でない** |

ベースポーズは「Bedrock の腕フレーム → Java のカメラ空間フレーム」の対応付けなので**利き手を持つ**。
三人称が両手とも正しく出ていたのは、たまたま三人称のポーズがミラー不変で「ミラーし忘れ」が露見しなかったから。
一人称は非対称なので、右腕用の対応付けを左腕に流すと視野外へ飛ぶ。
**2D も 3D も同時に映らない**という報告は、両方とも非対称な一人称ポーズを使っている事実と一致する。

**修正**: オフハンドの一人称 root に、Java 自身と同じ左手則を適用
（rotation `(x, -y, -z)` / position `(-x, y, z)`）。三人称はミラー不変なので出力バイト同一（テストで固定）。
実パック **87/87 スロット**でミラーを確認。

## D. 大斧が描画されない（visible_bounds を是正。ただし断定はしない）

`visible_bounds` はメッシュの宣言位置だけから算出しており、**アニメーションがメッシュを運ぶ距離**を
数えていなかった。大斧の display 平行移動は三人称 `-15.5` / 一人称 `-9`、長物では `26` に達する。
Bedrock はこの AABB で frustum カリングするため、箱が実際の描画位置を含んでいないと
「静止時は消えて、アニメで動いた瞬間だけ見える」という症状になる。報告の
「攻撃を振れば見える」はこの挙動と一致する。

**修正**: アイテム自身の display 平行移動（4スロットの最大絶対値）を箱に加算。
固定ベースポーズは全アイテム共通なので加算しない（全87件を定数分太らせるだけで、
j2b の既定値はそれ込みで決められたと見るのが自然）。実パックで **29件**（＝3D 全件）の箱が拡大。

**正直な但し書き**: これは「症状と整合する原因を潰した」であって、実機で確認するまで
**大斧が直ったとは主張しない**。カリングでなく視野外配置が原因の可能性も残る。
なお 07-25 の本文で「visible_bounds は原因ではない」と書いたのは、
display scale とルート scale を掛ける前のモデル単位で測っていたための誤りだった。撤回する。

## E. 一人称メインハンドが少し高い（未修正・要ユーザー入力）

2D（flat, root `[0,15,4]` scale 1.75）と 3D（root `[4,10,4]` scale 1.5）の**両方**が「少し高い」。
両者は Y も scale も違うので、私の側から「どちらの軸を何ピクセル下げるか」を一意に決められない。
角度が合っている以上、残差は root position の平行移動成分だけであり、**1回の実測値があれば確定できる**。
定数総当たりは 07-25 に 40 回失敗している（[[bedrock-attachable-fp-scale-rule]]）ので、
今回は当て推量で動かさない。

代わりに**比較手段を用意した**（下記 F）。

## F. Rainbow マッピングを config スイッチ化（新規）

一人称のフレーム定義は j2b と Rainbow で根本的に違う（多段 bone ＋ root scale ／ 単 bone ＋軸置換）。
どちらが実機で Java に近いかは**実機比較でしか決まらない**。
コード上は Rainbow 実装が既にあったが `boolean rainbowSingleBone = false` で固定されていたので、
`customItems.attachableGeneration.rainbowFirstPersonMapping`（既定 `false`）で切替可能にした。
三人称は両者一致なので影響しない。**再ビルド不要**で A/B できる。

## G. 3D アイテムのインベントリ絵が UV シート（原理と、実は可能な解）

07-25 の回答を**精密化する**。前回「ドロップ/額縁/インベントリは 2D 固定なので不可能」と書いたが、
2つを分けるべきだった:

- **3D として立体表示すること**: 不可能（attachable は装備スロット専用）。前回の回答どおり。
- **インベントリ絵を Java と同じ見た目にすること**: **可能**。ただし現状は未実装。

Java はインベントリ描画時に 3D モデルを `display.gui` 変換つきで**その場でレンダリング**して絵にしている。
Bedrock は `minecraft:icon` に指定された PNG をそのまま出すだけなので、
3D モデルの場合そこに入っているのは**テクスチャアトラス（UV シート）**であり、報告どおりの見た目になる。
既存の `GuiIconTransformer` は 2D アフィン変換しかできず（クラス doc に明記のとおり）、これは解決できない。

**解**: パック生成時に 3D モデルを**オフラインでラスタライズ**して 2D アイコン PNG を焼き、
それを `minecraft:icon` に使う。Java のインベントリ絵と同じ手順を事前計算に移すだけなので原理的な障害はない。
必要なもの: cube の面ごと UV サンプリング、`display.gui` の回転/スケール適用、正射影、深度ソート、
アンチエイリアス無しの整数ピクセル出力。**未着手・要ユーザー判断**（規模が大きいため）。

# 追補2 の Files Changed

- `paper/.../pack/BedrockAttachableWriter.java` — 一人称 root のオフハンドミラー / `maxAnimationOffset()` 新設し visible bounds に加算 / `rainbowSingleBone` を config 化
- `core/.../config/GeyserExtraConfig.java` — `rainbowFirstPersonMapping`（`Boolean`, 既定 false）追加
- テスト2件追加（`firstPersonOffHandMirrorsBasePose` / `visibleBoundsCoverAnimationReach`）、`visibleBoundsNeverShrink` の前提を修正（大斧 display を使っていたため新仕様では正当に拡大してしまう）

# 追補2 の Verification

- `:paper:test` / `:core:test` = **112 tests / 3 failed**（失敗は着手前と同一の `GuiIconTransformerTest` 3件）
- 実パック: 一人称オフハンド root ミラー **87/87**、visible bounds 拡大 **29件（3D 全件）**
- 配備: `plugins/geyserExtra-1.0.0-SNAPSHOT.jar` 00:08（extension 側は変更なし）

## H. 3D アイテムのインベントリ絵をモデルからレンダリング（新規実装・G の解）

`ItemIconRenderer` を新設し、パック生成時に 3D モデルをオフラインでラスタライズして
インベントリ用アイコン PNG を焼くようにした。Java が描画時に毎回やっていることを事前計算に移しただけで、
インベントリ・ドロップ・額縁の**すべて**が同時に直る（立体にはならないが、絵が正しくなる）。

**バニラ移植した箇所**（自作すると必ずテクスチャが反転・回転する部分なので、推測せず一次ソースから移植）:

| 項目 | 出典 | 内容 |
|---|---|---|
| 面ごとの頂点順 | `FaceInfo`（1.21.11） | DOWN/UP/NORTH/SOUTH/WEST/EAST の 4 隅テーブルをそのまま定数化 |
| 隅ごとの UV | `BlockElementFace.UVs.getVertexU/V` | 0,1→minU / 0,3→minV |
| 面回転 | `Quadrant.rotateVertexIndex` | `(index + rotation/90) % 4` |
| 頂点座標 | `FaceBakery.bakeVertex` | `select(from,to)/16`、element 回転は `origin/16` 周り |
| display 適用順 | `ItemTransform#apply` ＋ 後段の `translate(-0.5)` | `v = T + R·(S·(v01 - 0.5))` |

投影は正射影（`-Z` 方向、`+X` 右・`+Y` 上）で GUI のポーズスタック（`scale(16,-16,16)`）と一致。
深度は z-buffer。出力 64×64（Bedrock のアイコンは 16×16 に縛られない）、4× スーパーサンプリング後に
プリマルチプライドでボックスダウンサンプル。完全透明テクセルは深度を占有しない
（手前の切り抜き面が奥の面を隠さないため）。

**アトラスとアイコンの分離**: attachable の 3D ジオメトリは引き続きアトラスを UV サンプリングする必要があるので、
生 PNG は従来どおり `textures/items/<base>.png` に残し、焼いたアイコンは `<base>_gui.png` に書いて
`item_texture.json` だけをそちらに向ける（既存の `_gui` 経路をそのまま利用）。
**レンダリング失敗時は必ず従来の 2D アフィン bake にフォールバック**し、パックビルドは決して失敗しない。

**検証**: 実パックの 3D モデル **29件すべて**が非空アイコンにレンダリングされることをテストで固定
（`paper/build/icon-render-dump/` に PNG を出力）。目視でも大斧・戦鎚・短剣・長槍・剣が
UV シートではなく武器の絵として出ている。合成シート: `paper/build/icon-render-dump/_contact_sheet.png`。

**既知の制限**（実装済みだが完全ではない点）:
- 半透明面の混色は行わない（z-buffer のみ）。本パックの武器はすべて不透明なので影響なし。
- element の `rescale` フラグは `JavaPackReader` が読んでいないため未適用。回転 element のサイズが数％違う。
- 面ごとの複数テクスチャ参照（`#1` / `#2`）は未配線で、全面が主テクスチャを使う。
  実パックでは全 18,942 面中 `#1`=846 / `#2`=234（約 5.7%）、対象は 6 モデル。

# 追補3 の Files Changed

- `paper/.../pack/ItemIconRenderer.java`（新規）— 3D モデル → アイコン PNG のラスタライザ
- `paper/.../pack/AutoBedrockPackBuilder.java` — `TextureCopyTask` に geometry を追加、`renderModelIcon()` を 2D bake の前段に挿入
- `paper/src/test/.../ItemIconRendererTest.java`（新規）— 合成6件＋実パック全 3D モデルの回帰

# 追補3 の Verification

- `:paper:test` / `:core:test` = **118 tests / 3 failed**（失敗は着手前と同一の `GuiIconTransformerTest` 3件）
- 配備: `plugins/geyserExtra-1.0.0-SNAPSHOT.jar` 02:30

---

# 追補4（2026-07-26 03:20）— 自己レビューで確定した分の修正

「確定している箇所」＝**Java の挙動が既知で、こちらが未実装 or 誤っているだけ**のもの。
実機確認を待つ必要がない4件を修正・配備した。仮説段階の B（一人称オフハンド root ミラー）/
C（高さ -16）/ D（大斧 visible_bounds）はそのまま据え置き。

## I. レビューで見つけた自分のバグ 2件

**I-1. アニメーションテクスチャ判定が危険だった（撤去）**
`ItemIconRenderer.spriteHeight` が「高さが幅の整数倍 ⇒ N フレームのフィルムストリップ」と
**寸法だけで推測**し、上端フレームのみをサンプルしていた。`.mcmeta` を一切見ていないので、
アニメではない縦長アトラスを**無言で半分に切り落とす**。加えて `BedrockGeometryConverter` は
PNG 全高で UV をスケールするため、**同じテクスチャをアイコンと 3D モデルで別解釈**していた。
→ 推測を撤去し全高使用に統一。アニメーション対応は両者共通の既知の制限として明示。

**I-2. 「空でない」ことしか保証していなかった（カバレッジ下限を追加）**
`display.gui` がカメラを向けないモデルは真横から見た薄片になる。Java 的には忠実だが、
アイコンとしては置き換え前のアトラスより悪い。→ 塗り面積 2% 未満なら生スプライトへフォールバック。
実パックの実アイコンは **15.5%〜28.5%** なので約 8 倍の余裕がある。

## J. 既知の制限として残していた 2件を実装

**J-1. element の `rescale` フラグ**
Java は回転 element の垂直2軸を `1/cos(angle)` で伸ばす（45° で √2）。
`JavaPackReader` がフラグを読んでいなかったので未適用だった。
→ `ElementRotation` に `rescale` を追加（3引数の後方互換コンストラクタ付き）、パース、レンダラで適用。

**J-2. 面ごとの複数テクスチャ参照（`#1` / `#2`）**
`JavaModelDefinition` は主テクスチャ 1枚しか持たず、`#1` を参照する面も主テクスチャで塗っていた。
→ `resolveTextureMapSafe()` を新設。`textures` マップは Mojang が親チェーンで**マージする**
（子が勝つ）ので、ヒット後も走査を続けて未取得キーだけを埋める。`"#other"` の間接参照も追跡。
`JavaModelDefinition.textureFiles` として保持し、3D モデルのときだけ追加走査する。

## 現行パックへの影響: **見た目は変わらない**（正直な計測結果）

期待させないために明記する。実パックを計測した結果:

| 項目 | 実測 |
|---|---|
| 回転を持つ element | 48個 |
| うち `rescale: true` | **0個** |
| 面が参照するテクスチャキー | `#0`=17862 / `#1`=846 / `#2`=234 |
| 面参照が**異なる画像パス**に解決されるモデル | **0個** |
| 非正方形テクスチャ | **0個** |

`#1` / `#2` を使うモデルは、その `textures` マップに `"1"` しか無い（＝`#1` が主テクスチャ）か、
2つ目のパスが `particle`（面からは参照されない）だった。
つまり **J-1 / J-2 / I-1 はいずれも本パックでは no-op** であり、
**将来のパックで壊れないための正しさの修正**である。生成アイコン 29件は追補3 と同一。

I-2 のカバレッジ下限だけは、`display.gui` を持たないモデルが今後入った場合に
「アイコンが 1px の線になる」のを防ぐガードとして実効性がある。

# 追補4 の Files Changed

- `paper/.../pack/ItemIconRenderer.java` — フィルムストリップ推測を撤去 / カバレッジ下限 2% / `rescale` 適用
- `paper/.../pack/JavaModelGeometry.java` — `ElementRotation.rescale` 追加（後方互換コンストラクタ付き）
- `paper/.../pack/JavaPackReader.java` — `rescale` パース / `resolveTextureMapSafe()` 新設 / `JavaModelDefinition.textureFiles` 追加（7引数の後方互換コンストラクタ付き）
- `paper/.../pack/AutoBedrockPackBuilder.java` — `TextureCopyTask` にテクスチャマップを追加しレンダラへ配線
- テスト4件追加（`edgeOnProjectionFallsBack` / `tallTextureIsNotTreatedAsAnimated` / `perFaceTextureReferencesAreHonoured` / `elementRescaleStretchesGeometry`）＋実パックテストに複数テクスチャ解決のアサート

# 追補4 の Verification

- `:paper:test` / `:core:test` = **122 tests / 3 failed**（失敗は着手前と同一の `GuiIconTransformerTest` 3件）
- 実パック 3D モデル 29件が引き続き全件レンダリング成功、複数レイヤ解決モデルの存在をテストで固定
- 配備: `plugins/geyserExtra-1.0.0-SNAPSHOT.jar` 03:20（extension 側は変更なしのため 23:21 のまま）

# レビュー時に確認して問題なかった点

- 焼いたアイコンのバイトが `patchVersionFromContent` に渡る `itemTextureBytes` に入っている
  → パックバージョンが上がりクライアントが再取得する（ここが漏れていたら「直したのに反映されない」になっていた）
- attachable は `bedrockTextureRelative`（生アトラス）を直接参照し、`customIconToTexturePath` の
  `_gui` リダイレクトとは独立 → 3D ジオメトリの UV サンプリングが壊れない
- レンダリング失敗時は必ず従来の 2D アフィン bake にフォールバックし、パックビルドは失敗しない

---

## 追補5: 一人称の高さ — 符号を実機で確定（2026-07-26 07:02 配置）

### 症状と経過

| 配置 | 一人称 root Y (3D / flat) | ユーザー報告 |
|---|---|---|
| 初期 | `10` / `15`（素の参照値） | 「アイテム1個分くらい画面の上すぎる」 |
| 06:59 前 | `-6` / `-1`（`-16`） | **「逆に手から離れてた」** |
| 07:02 | `26` / `31`（`+16`） | 確認待ち |

### 何を間違えたか

三人称 root は `y=13`、head は `y=19.9`。ここから「Y を増やす＝画面上」と推定し、
下げるために `-16` を適用した。**一人称アームフレームの Y は逆向き**だった。
他フレームからの類推は成り立たない。

反証のチャンスは事前にあった。2D（`Y=15`, scale 1.75）と 3D（`Y=10`, scale 1.5）が
Y 値も scale も違うのに「**同じだけ**高い」と報告されていた。Y 単独が原因なら
差が出るはずで、この不一致は出荷前に自分で指摘もしていた。指摘しただけで
モデルを疑い直さなかったのが実際の失敗。

### 現在の実装

補正を定数から**実行時設定**に移した。素の参照値（3D `[4,10,4]` / flat `[0,15,4]`）は
そのまま残し、ビルド時に `firstPersonHeightOffset`（既定 `16`）を両者の Y に加算する。

- 1 つの数値で flat と 3D が同時に動く（症状が「両方同じだけ高い」だったため）
- 明示 `firstPersonBasePose` は絶対値として扱い、オフセットを適用しない
- Gson がコンストラクタを通さないため `Float` 保持。明示 `0`（補正無効）とキー無し（既定 16）を区別

これで次の微調整は config 編集＋再起動で済み、リビルドを挟まない。

### 検証状態

- テスト 122件 / 失敗3件 = `GuiIconTransformerTest` の既存失敗のみ（フォールバック経路、今回の変更と無関係）
- 配置: `plugins/geyserExtra-1.0.0-SNAPSHOT.jar` 07:02。Extension 側は変更なし
- **未確認（実機待ち）**: 一人称の高さ / 一人称オフハンドの root ミラー / 大斧の visible_bounds

この 3 点は実機でしか判定できず、根拠は「実機で 1 回振って符号を取る」以上のものが無い。
推定で詰めず、報告を待って確定させる。
