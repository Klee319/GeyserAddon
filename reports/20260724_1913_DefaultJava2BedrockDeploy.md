# Task

3D カスタムアイテムの既定 hold 変換を Rainbow 単 bone から **java2bedrock 多段 bone** に切り替え、JAR を TrinityForge へ配置・再起動して auto pack を再生成した。

# Files Changed

- `paper/src/main/java/com/geyserextra/paper/pack/BedrockAttachableWriter.java`
- `paper/src/test/java/com/geyserextra/paper/pack/BedrockAttachableWriterTest.java`
- `core/src/main/java/com/geyserextra/core/config/GeyserExtraConfig.java`（javadoc）
- `README.md`
- `log/BugAndFix/20260724_1709_ItemHoldPoseWrong.md`

# Details

## 方針（承認済み）

1. 全アイテム同一規則（アイテム別分岐なし）
2. 3D 既定 = java2bedrock（`root → x → y → z` + `geyserextra_geo` @ `[0,8,0]`）
3. `firstPersonBasePose` は任意の**全体**上書きのみ（経路切替ではない）

## 実装

- `rainbowSingleBone` を常に `false`（Rainbow 経路は死コードとして残置）
- テストを java2bedrock 期待値へ更新
- README の変換方式説明を更新

## 配置

- `plugins/geyserExtra-1.0.0-SNAPSHOT.jar`
- `plugins/Geyser-Spigot/extensions/extension-1.0.0-SNAPSHOT.jar`
- Paper 再起動（RCON stop → `start.bat`）
- `geyserextra_auto.zip` 再生成確認: `custom_golden_sword_20/32` に `geyserextra_x` + `geyserextra_geo`、FP base `60/-40`、TP base `-3`、pivot `[0,8,0]`

# README

§ attachable 変換（一人称／三人称）を java2bedrock 既定に合わせて同期済み。
