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

`texture_size` in these files is Blockbench metadata and must be **ignored**: the UVs are in the
ordinary 0..16 model space, exactly like vanilla. Proof by the skull: its north face declares
`[6,4,8,8]`, which multiplied by the real 64×32 sheet lands precisely on the head's back face
(pixels 24..32 × 8..16). Scaling by `texture_size` instead lands on a near-uniform patch — that
mistake once baked every skull as a plain white or black cube, and the pot's mostly-uniform
terracotta sheet hid it from the first visual check.

## Source and licence

Taken from [BlueMap](https://github.com/BlueMap-Minecraft/BlueMap),
`core/src/main/resourceExtensions/assets/minecraft/models/entity/`, which solves the same problem
for its web map. BlueMap is MIT-licensed; the models are credited in-file to **TyBraniff**, made
with Blockbench.

Copied on 2026-08-24 from `master`. Textures are *not* vendored — they are pulled from the
Minecraft Java assets mirror at build time like every other texture.
