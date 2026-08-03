# Task

Valhalla 系カスタム武器の Bedrock 1人称/3人称姿勢ずれを修正するため、Rainbow 単 bone 経路の pivot を bounds 中心から Java モデル空間中心 `[0, 8, 0]` へ変更した。

# Files Changed

- `paper/src/main/java/com/geyserextra/paper/pack/BedrockAttachableWriter.java`
- `paper/src/main/java/com/geyserextra/paper/pack/BedrockGeometryConverter.java`（javadoc のみ）
- `paper/src/test/java/com/geyserextra/paper/pack/BedrockAttachableWriterTest.java`
- `README.md`
- `log/BugAndFix/20260724_1709_ItemHoldPoseWrong.md`

# Details

## 原因

生成済み `geyserextra_auto.zip` の FP/TP AnimationMapper 式は正しかったが、geo bone pivot がメッシュ AABB 中心（大斧例: `[1.75, 0.25, 1.75]`）だった。Java の item display はベイク原点 `(8,8,8)`（Bedrock `[0,8,0]`）周りで回転する。Valhalla 武器は y≈0 に偏るため、約 90° の hold 回転が AABB 中心で振られ、手から離れる・胴体に埋まる症状になっていた。

## 修正

- Rainbow 単 bone 経路: pivot を常に `[0, 8, 0]`
- FP/TP 式・単 bone 構造は維持
- `firstPersonBasePose` 明示時の legacy geo leaf は従来どおり bounds 中心

## Verification

- `:paper:test --tests BedrockAttachableWriterTest`: BUILD SUCCESSFUL

## Deploy (2026-07-24 17:45)

- Paper: `plugins/geyserExtra-1.0.0-SNAPSHOT.jar`（bak: `.bak_20260724_174502`）
- Extension: `plugins/Geyser-Spigot/extensions/extension-1.0.0-SNAPSHOT.jar`（同 bak）
- 反映にはサーバ再起動 + Bedrock パック再取得が必要
