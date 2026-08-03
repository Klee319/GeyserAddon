# Task

java2bedrock 経路で三人称は回復したが一人称がカメラ後方になる問題への対応。legacy geo leaf の pivot を bounds 中心から Java モデル空間中心 `[0, 8, 0]` へ変更した。

# Files Changed

- `paper/src/main/java/com/geyserextra/paper/pack/BedrockAttachableWriter.java`
- `paper/src/main/java/com/geyserextra/paper/pack/BedrockGeometryConverter.java`（javadoc）
- `paper/src/test/java/com/geyserextra/paper/pack/BedrockAttachableWriterTest.java`
- `log/BugAndFix/20260724_1709_ItemHoldPoseWrong.md`

# Details

## 根拠

config で `firstPersonBasePose` を明示し java2bedrock 多段 bone に切り替えた実験で、三人称は Java に近づいた。一人称は手元に見えず、攻撃時に後ろからメッシュが振れる → FP base `[90,60,-40]` が、y≈0 の薄いメッシュを AABB 中心 pivot の geo leaf ごとカメラ後方へ振っていた。

## 修正

- legacy `geyserextra_geo` pivot を常に `[0, 8, 0]`
- 実験用 `firstPersonBasePose` config は維持（java2bedrock 経路のまま検証）

## Deploy

- JAR 差し替え + 再起動（bak `_20260724_183037`）
- パック 18:31:07 再生成、`custom_golden_sword_20` geo leaf pivot `[0,8,0]` を確認

## Verification

- `BedrockAttachableWriterTest`: BUILD SUCCESSFUL
- 実機: Bedrock パック再取得後の FP/TP 確認待ち
