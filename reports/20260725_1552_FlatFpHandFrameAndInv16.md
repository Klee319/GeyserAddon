# Task

1. バニラ型（flat）一人称の浮き: 手フレーム FP に変更
2. テクスチャ専用 INV: trim → 16×16 fit

# Files Changed

- `paper/.../pack/BedrockAttachableWriter.java`
- `paper/.../pack/IconSpriteNormalizer.java`
- `paper/.../pack/AutoBedrockPackBuilder.java`
- tests / `README.md`

# Details

## FP（texture-only）

- rot/pos: operator `firstPersonBasePose`（未設定時は `[90,60,-28]/[25,7,10]`）
- scale: 固定 `1.0`（Valhalla の 3.2 は使わない）
- FP translation: ゼロ
- 3D（elements あり）: 従来どおり

## INV（texture-only）

- `IconSpriteNormalizer.forInventoryIcon`: 余白トリム＋正方なら 16×16 fit
- `_gui.png` に分離し `item_texture` のみリダイレクト（手持ち生 PNG は維持）
- 3D 武器アイコンは非対象

# Verification

- unit tests 緑
- デプロイ後: 緑剣 FP が手元／中央2スロットの hi-res がスロットサイズに近づくか確認
