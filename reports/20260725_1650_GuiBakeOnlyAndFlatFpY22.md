# Task: INV gui-bake only + flat FP Y=22

## Task
1. INV: trim/crop 廃止。生 PNG に Java `display.gui` bake のみ（ゆがみ解消＋Java 拡大）。
2. Flat FP: `[0,14,4]` → `[0,22,4]` / `1.5`。

## Files Changed
- `paper/src/main/java/com/geyserextra/paper/pack/AutoBedrockPackBuilder.java`
- `paper/src/main/java/com/geyserextra/paper/pack/BedrockAttachableWriter.java`
- `paper/src/main/java/com/geyserextra/paper/pack/GuiIconTransformer.java`（Javadoc）
- `paper/src/test/java/com/geyserextra/paper/pack/BedrockAttachableWriterTest.java`
- `README.md`

## Details
- 非正方 crop（例 22×23）が Bedrock スロットでアスペクトゆがみを起こしていた。
- `GuiIconTransformer` が Mojang ItemTransform 相当の 2D 拡大。scale>1 は Java 同様クリップしうる。
- `IconSpriteNormalizer` は本番パスから外す（クラスは残置）。
