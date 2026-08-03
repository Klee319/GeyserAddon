# Task

EXP-C: FP display 平行移動を ×0.25 で復帰し、scale 上限を 1.25 に緩和して配置した。

# Files Changed

- `paper/.../BedrockAttachableWriter.java`
- `core/.../GeyserExtraConfig.java`（javadoc）
- `paper/.../BedrockAttachableWriterTest.java`
- `README.md`
- `log/BugAndFix/20260724_1709_ItemHoldPoseWrong.md`

# Details

## 動機（EXP-B フィードバック）

- やや小さい → scale 1.0 が強い
- 根元でなく先端寄り → 平行移動ゼロで握りオフセットまで消えていた

## 条件（`debugZeroFirstPersonTranslation: true`、全アイテム同一）

- `position = convertTranslation * 0.25`
- `scale = min(s, 1.25)`

## パック検証

- greataxe: pos `(-0, 1.0, 2.25)` scale `1.25`
- morningstar: pos 減衰済み / scale `0.68`

# ユーザー確認

サイズと握り（根元寄りか）を Bedrock 再取得後に確認。
