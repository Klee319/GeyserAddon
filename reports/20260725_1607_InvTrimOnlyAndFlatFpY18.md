# Task: INV trim-only + flat FP raise (Y=18)

## Task
1. INV アイコンの 16×16 強制ダウンサンプルをやめ、余白 trim→元解像度 fit のみに戻す（ピクセルロス解消）。
2. flat 1人称の持ち位置を画面上へ（Y=28 → Y=18。+Y は画面下）。

## Files Changed
- `paper/src/main/java/com/geyserextra/paper/pack/IconSpriteNormalizer.java`
- `paper/src/main/java/com/geyserextra/paper/pack/BedrockAttachableWriter.java`（FLAT_FIRST_PERSON_POSE Y=18）
- `paper/src/main/java/com/geyserextra/paper/pack/AutoBedrockPackBuilder.java`（コメント）
- `paper/src/test/java/com/geyserextra/paper/pack/IconSpriteNormalizerTest.java`
- `paper/src/test/java/com/geyserextra/paper/pack/BedrockAttachableWriterTest.java`
- `README.md`

## Details
- `forInventoryIcon` / `trimAndFit`: 透明余白を切り、元 `W×H` へ nearest-neighbor fit。フルブリードは no-op。解像度変更なし。
- Flat FP root: `[90,60,-40] / [4,18,4] / 1.0`
- デプロイ: `20260725_1606` bak。pack `16:07:35`。
- 検証: `custom_netherite_sword_112` FP root `4.0, 18.0, 4.0`

## Verify in-game
- INV: 以前よりシャープ／元見た目に近いこと（スロット埋めは余白ありなら維持）
- FP: 向きはそのまま、やや上（まだ低い／高いなら Y だけ微調整）
