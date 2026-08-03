# Task

4件修正: バニラ型 FP / オフハンド左右 / INV 生テクスチャ復帰 / `[GE]` lore の Java 残留防止。

# Files Changed

- `core/.../GeyserExtraConfig.java` — `FIRST_PERSON_DEFAULT` → `[4,10,4]/1.5`
- `paper/.../pack/BedrockGeometryConverter.java` — offhand X を main と同じ `-x`
- `paper/.../pack/AutoBedrockPackBuilder.java` — アイコン bake 無効（生 PNG）
- `paper/.../enchantment/BedrockEnchantmentHandler.java` — `[GE]` strip（inbound creative / Java outbound / click / drop / pickup）
- tests / `README.md`

# Details

1. **FP**: flat 用 root を実 java2bedrock 値に合わせ、手から離れて見えていたずれを修正
2. **Offhand**: righthand 流用時の二重反転を廃止（TP off の X が main と同符号に）
3. **INV**: trim/gui/ground bake を停止し `item_texture` は生 PNG
4. **Lore**: Creative 書き戻し防止＋Java 表示時 strip＋サーバ側イベント strip

# Verification

- 関連 unit test 緑
- デプロイ後: infinity_sword FP、3D オフハンド、INV 見た目、`[GE]` 付きを Java に渡して lore 消失を確認
