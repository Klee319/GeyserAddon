# Entity-block models (vendored)

Minecraft draws a handful of blocks from code instead of from a model JSON: decorated pots,
conduits, chests, banners, shulker boxes and every head/skull. Their `models/item/<id>.json`
carries a particle texture and nothing else, so there is no geometry to read and
`BedrockBlockIconGenerator` would fall back to the cube approximation for all of them — which is
what shipped a cube-shaped decorated pot for the source-jar items.

These files fill that gap. They are ordinary Java model JSON (`elements` + `textures`), so
`JavaAssetSource` reads them with the same code path it uses for Mojang's own models: when the
remote asset is missing, the bundled copy at the same relative path is used instead. If Mojang ever
ships real models for these blocks, the remote copy wins and these become dead weight.

`texture_size` is honoured here and nowhere in vanilla: these models are authored in Blockbench
against the entity textures, whose UV space is the texture's own pixel size (32×32 for the pot,
64×32 for a skull) rather than the 16×16 vanilla models assume.

## Source and licence

Taken from [BlueMap](https://github.com/BlueMap-Minecraft/BlueMap),
`core/src/main/resourceExtensions/assets/minecraft/models/entity/`, which solves the same problem
for its web map. BlueMap is MIT-licensed; the models are credited in-file to **TyBraniff**, made
with Blockbench.

Copied on 2026-08-24 from `master`. Textures are *not* vendored — they are pulled from the
Minecraft Java assets mirror at build time like every other texture.
