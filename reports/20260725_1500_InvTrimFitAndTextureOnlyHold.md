# Task

1. INV アイコン: 透明余白トリム → キャンバス fit → `gui` bake（ground bake 廃止）
2. テクスチャ差し替え専用剣（`infinity_sword` 等）: バニラ剣ホールド枠（flat FP）に分離

# Files Changed

- `paper/.../pack/IconSpriteNormalizer.java`（新規）
- `paper/.../pack/AutoBedrockPackBuilder.java`
- `paper/.../pack/GuiIconTransformer.java`
- `paper/.../pack/BedrockAttachableWriter.java`
- `core/.../config/GeyserExtraConfig.java`（javadoc）
- `paper/src/test/.../IconSpriteNormalizerTest.java`（新規）
- `paper/src/test/.../VanillaBuiltinDisplaysTest.java`
- `paper/src/test/.../BedrockAttachableWriterTest.java`
- `README.md`

# Details

## INV

- 原因: 共有アイコンへの `display.ground`（scale 0.5）bake
- 対応: `IconSpriteNormalizer.trimAndFit` → `GuiIconTransformer.bake(gui)`。ground は使わない

## テクスチャのみ剣

- 意図: モデルはバニラ剣（マテリアル）のまま、layer0 テクスチャだけ差し替え
- 判定: Java `elements` なし → flat / `texture_meshes`
- FP: `BasePose.FIRST_PERSON_DEFAULT` + translation 維持（Valhalla 用 `firstPersonBasePose` / zero-translation は **適用しない**）
- TP: 従来どおり handheld 注入値（変更なし）
- 3D（elements あり）: 現行 Valhalla 用 FP base 維持

# Verification

- unit tests 緑（IconSpriteNormalizer / VanillaBuiltin / AttachableWriter / GuiIcon）
- 実機: デプロイ後に `infinity_sword`（と類似のテクスチャ差し替え剣）の INV・FP・TP を確認

# Note

「深罪の終幕」の mapping ID は未特定。elements なしの handheld 親なら同じ経路に入る。
