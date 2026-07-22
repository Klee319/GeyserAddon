# Fix Bedrock custom-item transparency (icon / item_texture)

## Task
Block-base custom items showed transparent Bedrock inventory icons after restart because `buildItemTextureJson` skipped `item_texture` entries when `usesBlockIcon` applied and no custom PNG existed. Icon keys could also drift when Extension sanitized `name` but not `icon`, or used default-locale lowercasing.

## Files Changed
- `paper/src/main/java/com/geyserextra/paper/pack/BedrockVanillaTexturePaths.java`
- `paper/src/main/java/com/geyserextra/paper/pack/AutoBedrockPackBuilder.java`
- `paper/src/test/java/com/geyserextra/paper/pack/BedrockVanillaTexturePathsTest.java`
- `extension/src/main/java/com/geyserextra/extension/handler/CustomItemsHandler.java`

## Details
1. **AutoBedrockPackBuilder.buildItemTextureJson**: For block-base items without a custom PNG, write `textures/blocks/<bedrockBlockId>` via new `BedrockVanillaTexturePaths.blockTextureFallbackPath`. Warn at WARNING when no mapping exists. Icon-key collision logs now include `baseItem` and `customModelData` for winner and loser. Comments updated: Geyser does not accept `useBlockIcon` on vanilla-based definitions.
2. **BedrockVanillaTexturePaths**: Added `blockTextureFallbackPath(String)`; class javadoc reflects pack fallback instead of Geyser block placer.
3. **CustomItemsHandler**: `sanitizeIdentifierValue` uses `Locale.ROOT`; `icon` field sanitized like `name`; `registerVanillaItem` always sets `bedrockOptions.icon` to sanitized icon or sanitized name.
4. **Tests**: Extended `BedrockVanillaTexturePathsTest` for block texture fallback paths. No Extension unit test harness present.

## Test Results
- `:paper:test --tests com.geyserextra.paper.pack.BedrockVanillaTexturePathsTest` — SUCCESS
- `:extension:compileJava` — SUCCESS

## README
No README update required; existing BLOCK_PLACER / flat-icon fallback wording remains accurate.
