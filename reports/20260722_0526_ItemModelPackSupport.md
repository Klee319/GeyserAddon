# Task

Modern Java resource pack の直接 `minecraft:item_model` 対応と、安全な自動Bedrock pack更新。

# Files Changed

- `core/src/main/java/com/geyserextra/core/api/CustomItemMapping.java`
- `core/src/main/java/com/geyserextra/core/registry/ItemMappingRegistry.java`
- `paper/src/main/java/com/geyserextra/paper/GeyserExtraPaper.java`
- `paper/src/main/java/com/geyserextra/paper/listener/ItemListener.java`
- `paper/src/main/java/com/geyserextra/paper/pack/AutoBedrockPackBuilder.java`
- `paper/src/main/java/com/geyserextra/paper/pack/JavaPackReader.java`
- `paper/src/main/java/com/geyserextra/paper/scanner/CustomItemScanner.java`
- `paper/src/main/java/com/geyserextra/paper/scanner/RecipeScanner.java`
- `extension/src/main/java/com/geyserextra/extension/GeyserExtraExtension.java`
- `extension/src/main/java/com/geyserextra/extension/handler/CustomItemsHandler.java`
- `core/src/test/java/com/geyserextra/core/registry/ItemMappingRegistryItemModelTest.java`
- `paper/src/test/java/com/geyserextra/paper/pack/JavaPackReaderDirectItemModelTest.java`
- `README.md`

# Details

- `assets/<namespace>/items/**/*.json` を再帰走査し、direct item-model IDと内部model参照を分離してPNGを解決。
- Paperの実ItemStackで明示上書きされた非vanilla `ITEM_MODEL` を検出し、`(baseItem, itemModelId)` mappingを永続化。
- Geyser v2登録ではbuilderのmodel identifierへdirect item-model IDを渡し、Java base itemはevent keyとして登録。
- 稼働中のactive ZIPを変更せずpending ZIPを生成し、次回Geyser pre-initializeで検証・昇格。
- active ZIP内の`item_texture.json`から実際のcustom icon keyを読み、sidecarとの世代不一致による透明化を防止。
- 既存CMD/PDC selectorとmapping固有cooldown groupを維持。
- 全Gradle build/test成功を確認。
