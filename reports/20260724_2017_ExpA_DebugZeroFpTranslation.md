# Task

EXP-A（FP display 平行移動が大斧待機非表示の主因か）を AutoPack 再生成後も残るよう、一時 debug フラグで実装し配置した。

# Files Changed

- `core/src/main/java/com/geyserextra/core/config/GeyserExtraConfig.java`
- `paper/src/main/java/com/geyserextra/paper/pack/BedrockAttachableWriter.java`
- `paper/src/test/java/com/geyserextra/paper/pack/BedrockAttachableWriterTest.java`
- `README.md`
- `log/BugAndFix/20260724_1709_ItemHoldPoseWrong.md`
- live `extensions/geyserextra/config.json`（`debugZeroFirstPersonTranslation: true`）

# Details

## 背景

ZIP 手パッチの EXP-A は AutoPack 再生成で消え、ユーザーの「変化なし」は実験未達だった。

## 実装

- `attachableGeneration.debugZeroFirstPersonTranslation`（既定 `false`）
- `true` 時、全アイテムの FP `geyserextra_x.position` を `[0,0,0]`（回転・scale・base pose は維持）
- テスト追加、ビルド配置、再起動
- 生成パック検証: `custom_golden_sword_32` FP position = `[0,0,0]`（OK）

## ユーザー確認

Bedrock パック再取得後、金大斧の**待機**表示:

- 見える → H1 支持（平行移動が主因）→ 案2へ
- 見えない → H1 反証 → 別仮説へ

実験後はフラグを `false` に戻すこと。
