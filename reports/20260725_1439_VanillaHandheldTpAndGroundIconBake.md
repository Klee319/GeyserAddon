# Task

1. バニラ親（`item/handheld`）だけの CMD 剣が三人称で誤ポーズになる問題を修正
2. パス1 A: 共有アイコン bake を `display.ground` 優先（なければ `gui`）に変更

# Files Changed

- `paper/.../pack/VanillaBuiltinDisplays.java`（新規）
- `paper/.../pack/JavaPackReader.java`
- `paper/.../pack/JavaModelDisplay.java`
- `paper/.../pack/BedrockAttachableWriter.java`（コメント）
- `paper/.../pack/AutoBedrockPackBuilder.java`
- `paper/.../pack/GuiIconTransformer.java`
- `paper/src/test/.../VanillaBuiltinDisplaysTest.java`（新規）
- `README.md`

# Details

## バグ修正（三人称持ち方）

- **原因**: `parent: item/handheld` のみの CMD モデルはバニラ親 JSON がパックに無く display が null → attachable 未生成。一方 Extension は `geyserextra:*` を登録するため、Bedrock のバニラ剣ポーズにも戻れない。
- **対応**: `resolveDisplayFromModel` が未解決 parent で `item/handheld` / `item/generated` に当たったとき、Minecraft 1.21.11 公式定数を注入。handheld のあと `item/generated` へ連鎖し ground/head も埋める。
- **handheld TP**: rot `[0,-90,55]` / trans `[0,4,0.5]` / scale `0.85`

## パス1 A（ドロップ／INV アイコン）

- `selectIconBakeTransform`: `ground` があればそれを、なければ `gui` を bake
- Geyser 制約により INV＝ドロップは同一スプライトのまま（分離は不可）
- zip 内の `_gui.png` サフィックスは互換のため維持

# Verification

- `VanillaBuiltinDisplaysTest` / `GuiIconTransformerTest` / `BedrockAttachableWriterTest` 緑
- 実機: Paper JAR 差し替え → 再起動 → パック再取得後、バニラモデル CMD 剣の三人称持ち方＋ドロップサイズを確認
