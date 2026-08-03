# Task: INV crop-only + flat FP lower/closer

## Task
1. INV: 引き伸ばし fit / `display.gui` bake をやめ、透明余白の crop のみ。
2. Flat FP: `[4,10,4]` → `[0,14,4]`（少し下＋手前）、scale `1.5` 維持。

## Files Changed
- `paper/src/main/java/com/geyserextra/paper/pack/IconSpriteNormalizer.java`
- `paper/src/main/java/com/geyserextra/paper/pack/AutoBedrockPackBuilder.java`
- `paper/src/main/java/com/geyserextra/paper/pack/BedrockAttachableWriter.java`
- `paper/src/test/java/com/geyserextra/paper/pack/IconSpriteNormalizerTest.java`
- `paper/src/test/java/com/geyserextra/paper/pack/BedrockAttachableWriterTest.java`
- `README.md`

## Details
- 小さいアートの NN 拡大と gui.scale のキャンバス内クリップが「ピクセル抜け／見切れ」の主因。
- crop 後は Bedrock がスロットへスケール（ピクセル密度維持）。
- `GuiIconTransformer` はクラス残置・本番未接続。
