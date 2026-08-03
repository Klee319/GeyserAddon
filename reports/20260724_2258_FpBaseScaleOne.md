# Task

Java/Bedrock スクリーンショット比較に基づき、FP root base scale を `1.5 → 1.0` に変更して配置した（全アイテム共通）。

# Files Changed

- `core/.../GeyserExtraConfig.java`（`BasePose.DEFAULT_SCALE`）
- `README.md`
- `paper/.../BedrockAttachableWriterTest.java`（コメント）
- live `config.json`（`firstPersonBasePose.scale: 1.0`）
- `log/BugAndFix/20260724_1709_ItemHoldPoseWrong.md`

# Details

## 根拠（画像比較）

| | Java | Bedrock（修正前: root 1.5 × display） |
|--|--|--|
| 大斧 | 画面内・大きい | 右端スラバーのみ |
| モーニングスター | 画面内・大きい | 右下に見える（握り先端寄り） |

Java の FP には java2bedrock 由来の **root 追加 scale 1.5 が無い**。Bedrock だけ `1.5 × displayScale` となり、大斧は `1.5×1.7≈2.55` で視錐台外、モーニングスターは `1.5×0.68≈1.02` で残る、という差と整合。

EXP-B（display を 1.0 にクランプ）で「おおむね良い」だった事実とも一致（実効サイズを下げると画面内に入る）。

## 修正（汎用）

- FP `firstPersonBasePose.scale` 既定 / 設定を **1.0**
- display scale は Java のまま（大斧 1.7 / MS 0.68）
- FP translation 0 は維持（実験フラグ）

## パック検証

- greataxe: x.scale `1.7`, root.scale `1.0`
- morningstar: x.scale `0.68`, root.scale `1.0`
