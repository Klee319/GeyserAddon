# Task

EXP-A 成功を踏まえ、根拠上最有力の **EXP-B**（FP display scale 上限 1.0）を実装・配置した。

# Files Changed

- `core/.../GeyserExtraConfig.java`（javadoc）
- `paper/.../BedrockAttachableWriter.java`
- `paper/.../BedrockAttachableWriterTest.java`
- `README.md`
- `log/BugAndFix/20260724_1709_ItemHoldPoseWrong.md`

# Details

## 選定理由

| 候補 | 根拠 | 順位 |
|--|--|--|
| EXP-B scale≤1 | EXP-A 後の残差は大斧 scale 1.7 vs MS 0.68。平行移動ゼロは維持 | **1** |
| EXP-C 減衰平行移動 | k が任意。MS はゼロで既に良好 → 平行移動再導入は逆行リスク | 2 |
| EXP-D 逆回転補正 | 大きい translation を戻す。EXP-A の「載せない方が良い」と矛盾しうる | 3 |

## 実験条件（フラグ true 時・全アイテム同一）

1. FP `position = [0,0,0]`（EXP-A）
2. FP scale 各軸 `min(s, 1.0)`（EXP-B）

## パック検証

- greataxe: pos `[0,0,0]` scale `[1,1,1]`
- morningstar: pos `[0,0,0]` scale `[0.68,0.68,0.68]`

# ユーザー確認

Bedrock 再取得後、金大斧の待機が「先だけ」から改善するか。
