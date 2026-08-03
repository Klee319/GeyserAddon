# Task: INV display.gui bake + flat FP j2b restore

## Task
1. INV: trim 後に `GuiIconTransformer.bake(display.gui)` を再接続（左＝正常スロットに近づける）。
2. Flat FP: `[4,18,4]/1.0` → j2b `[4,10,4]/1.5`。

## Files Changed
- `paper/src/main/java/com/geyserextra/paper/pack/AutoBedrockPackBuilder.java`
- `paper/src/main/java/com/geyserextra/paper/pack/BedrockAttachableWriter.java`
- `paper/src/test/java/com/geyserextra/paper/pack/BedrockAttachableWriterTest.java`
- `README.md`

## Details
- `TextureCopyTask` に `gui` を追加。Phase 2: `forInventoryIcon` → `GuiIconTransformer.bake`。変化時のみ `_gui.png`。
- `display.ground` は焼かない（共有アイコン縮小の再発防止）。
- `FLAT_FIRST_PERSON_POSE` = `[90,60,-40]/[4,10,4]/1.5`。

## Verify
- ホットバー右（CMD）が左（正常）に近いサイズ／位置か
- flat 1人称がバニラ剣に近いサイズ・高さか
