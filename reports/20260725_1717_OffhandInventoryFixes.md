# Offhand Inventory Fixes

## Task
Implement Bedrock off-hand inventory click handling for Creative mode and NOTHING actions, and persist `allow_offhand: true` in item mapping JSON.

## Files Changed
- `paper/src/main/java/com/geyserextra/paper/listener/OffhandSwapListener.java`
- `core/src/main/java/com/geyserextra/core/registry/ItemMappingRegistry.java`
- `core/src/test/java/com/geyserextra/core/registry/ItemMappingRegistryItemModelTest.java`

## Details

### OffhandSwapListener
- Added `isOffhandSlotClick(InventoryClickEvent)` supporting `CRAFTING` (raw slot 45) and `CREATIVE` (player slot 40 or raw slot 45).
- Extracted `applyPickupAll`, `applySwapWithCursor`, and `applyPlacement` helpers to deduplicate cancel/schedule logic.
- Default/NOTHING branch now forces pick-up, swap, or place-all when Bedrock would otherwise no-op.
- Added FINE diagnostic logging for Bedrock off-hand clicks.
- Updated class/method Javadoc for Creative + NOTHING forced placement.
- Sneak-drop and `/offhand` paths unchanged.

### ItemMappingRegistry
- `convertMappingToJson` now always writes `"allow_offhand": true` near the `register` field so Bedrock clients accept off-hand for custom-mapped weapons/tools.

### Tests
- Extended `ItemMappingRegistryItemModelTest` to assert saved JSON contains `"allow_offhand": true`.
- No new listener test class (no existing OffhandSwapListener test pattern).

## Build
- `:core:test` (ItemMappingRegistryItemModelTest) — PASS
- `:paper:compileJava` — PASS
