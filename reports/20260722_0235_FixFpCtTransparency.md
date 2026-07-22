# Task

Bedrock カスタムアイテムの一人称ずれ（Rainbow 単 bone 化）、任意プラグイン cooldown group の CT ミラー、再起動後も残る透明化（block ベース item_texture / icon 正規化）を修正した。

# Files Changed

- `paper/src/main/java/com/geyserextra/paper/pack/BedrockAttachableWriter.java`
- `paper/src/test/java/com/geyserextra/paper/pack/BedrockAttachableWriterTest.java`
- `paper/src/main/java/com/geyserextra/paper/listener/CooldownMappingSelector.java`
- `paper/src/main/java/com/geyserextra/paper/listener/CooldownBridgeListener.java`
- `paper/src/test/java/com/geyserextra/paper/listener/CooldownBridgeListenerTest.java`
- `paper/src/main/java/com/geyserextra/paper/pack/AutoBedrockPackBuilder.java`
- `paper/src/main/java/com/geyserextra/paper/pack/BedrockVanillaTexturePaths.java`
- `paper/src/test/java/com/geyserextra/paper/pack/BedrockVanillaTexturePathsTest.java`
- `extension/src/main/java/com/geyserextra/extension/handler/CustomItemsHandler.java`
- `README.md`
- `log/BugAndFix/20260722_0202_CustomItemFpCtTransparent.md`
- `reports/20260722_0230_GenericCooldownGroupBridge.md`（副次）
- `reports/20260722_0230_FixBedrockCustomItemTransparency.md`（副次）

# Details

## 一人称 / 三人称（案B）

3D（elements）かつ `firstPersonBasePose` 未設定時は Rainbow と同型の単一 bone `geyserextra` に binding・bounds 中心 pivot・cubes を載せ、FP/TP 両方の AnimationMapper 式を同一 bone に適用。binding が ROOT のまま GEO だけに Rainbow を載せるハイブリッドを解消。平面アイテムと明示 base pose は従来の java2bedrock チェーンを維持。

## CT（任意独自 group）

`CooldownMappingSelector` は `minecraft:` 以外の NamespacedKey を、recent → main → off で帰属したカスタムアイテムへ無条件ミラー。素材 group は正規化比較（裸キー ↔ `minecraft:`）を維持し、無関係な素材 CT を誤ミラーしない。`EntityDamageByEntityEvent` で近接攻撃時の main 手を recent に記録。Bukkit 型を selector から排除し単体テスト可能に。

## 透明化

初回対応では、`BLOCK_PLACER` がバニラ派生で使えない代替として block ベースへ `textures/blocks/<bedrockBlockId>` を指定した。しかし、これはアイテムアイコンではなくブロック面テクスチャであり、欠落パスでは missing-texture 表示にもなるため誤りだった。`reports/20260722_0400_PreserveVanillaItemIcons.md` で撤回し、カスタム PNG がない block／未解決アイテムはカスタム定義自体を登録せず Geyser のバニラ表示を維持する方式へ変更した。

## Verification

- `:paper:test` / `:extension:test`: BUILD SUCCESSFUL
- `clean build`: BUILD SUCCESSFUL
- README の FP/CT/block アイコン記述を同期
- TrinityForge へ JAR 差し替え完了（SHA-256 一致）
  - `plugins/geyserExtra-1.0.0-SNAPSHOT.jar`
  - `plugins/Geyser-Spigot/extensions/extension-1.0.0-SNAPSHOT.jar`
  - バックアップ: `*.bak_20260722_024250`

