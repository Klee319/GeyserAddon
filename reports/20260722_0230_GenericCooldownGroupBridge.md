# Change Report

## Task
Generic custom cooldown-group bridging: mirror any plugin `NamespacedKey` cooldown (not only Material) to attributed custom item `geyserextra:<mapping>` categories for Bedrock USE_COOLDOWN overlays.

## Files Changed
- `paper/src/main/java/com/geyserextra/paper/listener/CooldownMappingSelector.java`
- `paper/src/main/java/com/geyserextra/paper/listener/CooldownBridgeListener.java`
- `paper/src/test/java/com/geyserextra/paper/listener/CooldownBridgeListenerTest.java`

## Details
- **CooldownMappingSelector**: Added `normalize` / `parseGroupKey` using `NamespacedKey.fromString` and bare-key → `minecraft:` fallback. Custom (non-`minecraft:`) cooldown groups accept the first candidate in recent → main → off order without `baseItem` equality; vanilla `minecraft:` groups still require normalized base match.
- **CooldownBridgeListener**: Added `EntityDamageByEntityEvent` (LOWEST) to capture main-hand mapping for melee cooldowns; expanded class Javadoc for material and plugin groups.
- **Tests**: Replaced unrelated-plugin rejection test with custom-group attribution cases, plus bare `golden_sword` normalization test.

## Verification
- `:paper:test --tests CooldownBridgeListenerTest` blocked by pre-existing `:paper:compileJava` error in `AutoBedrockPackBuilder.java:1098` (unrelated to this change).
