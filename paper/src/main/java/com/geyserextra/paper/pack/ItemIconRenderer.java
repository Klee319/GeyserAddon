package com.geyserextra.paper.pack;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Renders a Java item model into a flat inventory icon at pack build time.
 *
 * <p><b>Why this exists:</b> Java draws inventory slots by rendering the 3D
 * model live, with the model's {@code display.gui} transform applied. Bedrock
 * has no such hook — it blits whatever PNG {@code minecraft:icon} points at.
 * For a model whose geometry is built from {@code elements}, that PNG is the
 * texture <em>atlas</em>, so the inventory, dropped items and item frames all
 * show a UV sheet instead of the weapon. Baking the render offline moves
 * Java's runtime step to build time and closes the gap for every 2D surface at
 * once. It does not make those surfaces three-dimensional — Bedrock attachables
 * remain equipment-slot only — but it does make them show the right picture.</p>
 *
 * <p><b>Fidelity:</b> the geometry pipeline is ported from vanilla
 * {@code FaceBakery} / {@code FaceInfo} / {@code BlockElementFace} rather than
 * reconstructed, because corner winding and the per-corner UV assignment are
 * exactly where a hand-rolled rasterizer silently mirrors or rotates textures:</p>
 * <ul>
 *   <li>corner order per face from {@code FaceInfo}'s vertex table,</li>
 *   <li>{@code UVs.getVertexU/V} corner selection with
 *       {@code Quadrant.rotateVertexIndex} = {@code (index + rotation/90) % 4},</li>
 *   <li>vertices taken as {@code select(from, to) / 16}, element rotation applied
 *       about {@code origin / 16},</li>
 *   <li>{@code ItemTransform#apply} order — translate, then rotate, then scale,
 *       composed against the {@code translate(-0.5)} centring the renderer does
 *       afterwards, giving {@code v = T + R·(S·(v01 - 0.5))}.</li>
 * </ul>
 *
 * <p>Projection is orthographic along {@code -Z} with {@code +X} right and
 * {@code +Y} up, matching the GUI pose stack ({@code scale(16, -16, 16)} after
 * centring). Depth resolves with a plain z-buffer: no translucency sorting, so
 * a model that overlaps itself with semi-transparent faces will differ from
 * Java. None of the weapon models do.</p>
 *
 * <p><b>Shading:</b> Java does not blit the raw texel for a 3D item icon — it
 * runs the core-shader two-directional diffuse formula
 * ({@code assets/minecraft/shaders/include/light.glsl}, misode/mcmeta
 * {@code assets} branch):</p>
 * <pre>
 * lightAccum = min(1, (max(0,dot(L0,n)) + max(0,dot(L1,n))) * 0.6 + 0.4)
 * colour.rgb *= lightAccum   // alpha untouched
 * </pre>
 * <p>{@code n} is the face normal computed after the element rotation and
 * {@code display.gui} transform have been applied to the corners (see
 * {@link #faceNormal}), and {@code L0}/{@code L1} are the two light
 * directions Java binds for that space.</p>
 *
 * <p>Which pair depends on the model's resolved {@code gui_light}, exactly as
 * in Java: {@code "side"} (the format default) uses what
 * {@code Lighting.setupFor3DItems()} binds, {@code "front"} uses
 * {@code setupForFlatItems()}'s. That distinction is not academic here — in
 * the reference pack <b>49 of the 57 element-bearing models declare
 * {@code "gui_light": "front"}</b>, and shading those with the 3D rig would
 * render them markedly darker than Java does (a camera-facing surface is
 * {@code 1.0} under the flat rig and {@code 0.513} under the 3D one). See
 * {@link #LIGHT0_3D} and {@link #LIGHT0_FLAT} for the matrices and their
 * citations, and {@link #shadeFactor} for the one adjustment this renderer
 * has to make to Mojang's numbers.</p>
 */
public final class ItemIconRenderer {

    /**
     * Vanilla {@code Lighting.DIFFUSE_LIGHT_0}/{@code DIFFUSE_LIGHT_1}
     * (decompiled {@code com.mojang.blaze3d.platform.Lighting}, 1.21.4-era
     * source, cross-checked against an independent reimplementation that
     * documents the same constants for the same purpose):
     * <ul>
     *   <li>{@code https://github.com/mil1dude/source-code/blob/369de544c3b8115893731d1189bfcd5ae0bdbbb2/src/game/java/com/mojang/blaze3d/platform/Lighting.java}
     *       — {@code DIFFUSE_LIGHT_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize()},
     *       {@code DIFFUSE_LIGHT_1 = new Vector3f(-0.2F, 1.0F, 0.7F).normalize()},
     *       and both {@code setupForFlatItems()}/{@code setupFor3DItems()}
     *       feed this same pair through {@code RenderSystem.setupGuiFlatDiffuseLighting}
     *       / {@code setupGui3DDiffuseLighting} respectively — the flat/3D
     *       split is in which pre-rotation matrix is applied to them (below),
     *       not in the base vectors.</li>
     *   <li>{@code https://github.com/Llama-Collective/renderer/blob/79f15b5f6e9c42a4fc7fe2748d192c1e8ea29ac9/src/render/items/itemLighting.ts}
     *       — independent reimplementation citing "Vanilla (Lighting.java:37)"
     *       for the identical {@code normalize(0.2,1.0,-0.7)} /
     *       {@code normalize(-0.2,1.0,0.7)} pair, corroborating the decompile.</li>
     * </ul>
     * We only need <b>1.21.11</b> confidence for the base vectors and the
     * pre-rotation matrix below; the mil1dude source is tagged 1.21.4, and
     * Mojang has not touched {@code Lighting} between those releases in any
     * changelog or diff surfaced by this research, so the risk is judged low,
     * but it is not a same-version primary source and is flagged as such.
     */
    private static final double[] DIFFUSE_LIGHT_0 = normalize(0.2, 1.0, -0.7);

    /** See {@link #DIFFUSE_LIGHT_0}. */
    private static final double[] DIFFUSE_LIGHT_1 = normalize(-0.2, 1.0, 0.7);

    /**
     * {@code DIFFUSE_LIGHT_0}/{@code DIFFUSE_LIGHT_1} pre-transformed by the
     * exact matrix {@code com.mojang.blaze3d.platform.GlStateManager
     * #setupGui3DDiffuseLighting} builds, ported term-for-term rather than
     * baked to a literal so the derivation stays checkable against the source
     * comment below:
     * <pre>
     * new Matrix4f()
     *     .scaling(1.0F, -1.0F, 1.0F)
     *     .rotateYXZ(1.0821041F, 3.2375858F, 0.0F)
     *     .rotateYXZ((float) (-Math.PI / 8), (float) (Math.PI * 3.0 / 4.0), 0.0F);
     * </pre>
     * from the same source as {@link #DIFFUSE_LIGHT_0}
     * ({@code src/game/java/com/mojang/blaze3d/platform/GlStateManager.java},
     * same commit). {@code rotateYXZ(y, x, z)} is JOML's
     * {@code rotateY(y).rotateX(x).rotateZ(z)} which, per JOML's own
     * {@code Matrix4f.java} source, right-multiplies the existing matrix — so
     * transforming a vector applies the *last* {@code rotateYXZ} call first.
     * Reading the chain right-to-left as applied-to-the-vector order:
     * {@code Rx(3π/4) → Ry(-π/8) → Rx(3.2375858) → Ry(1.0821041) → Scale(1,-1,1)}.
     * {@link #rotateX(double[], double)}/{@link #rotateY(double[], double)}
     * below use the same sign convention as {@link #applyGuiTransform}
     * (verified against JOML {@code Matrix4f.rotationX/rotationY}), so this
     * reuses that convention rather than introducing a second one.
     *
     * <p>The trailing {@code scaling(1, -1, 1)} is kept verbatim; the single
     * compensation this renderer needs is applied on the normal instead, once,
     * in {@link #shadeFactor}.</p>
     */
    private static final double[] LIGHT0_3D = flipY(rotateY(
        rotateX(rotateY(rotateX(DIFFUSE_LIGHT_0, 3.0 * Math.PI / 4.0), -Math.PI / 8.0),
            3.2375858), 1.0821041));

    /** See {@link #LIGHT0_3D}. */
    private static final double[] LIGHT1_3D = flipY(rotateY(
        rotateX(rotateY(rotateX(DIFFUSE_LIGHT_1, 3.0 * Math.PI / 4.0), -Math.PI / 8.0),
            3.2375858), 1.0821041));

    /**
     * The {@code gui_light: "front"} pair — what
     * {@code Lighting.setupForFlatItems()} binds, via
     * {@code GlStateManager#setupGuiFlatDiffuseLighting} from the same source
     * and commit as {@link #LIGHT0_3D}:
     * <pre>
     * new Matrix4f().rotationY((float) (-Math.PI / 8)).rotateX((float) (Math.PI * 3.0 / 4.0));
     * </pre>
     * {@code rotationY} <em>sets</em> the matrix (it is not the accumulating
     * {@code rotateY}), and {@code rotateX} then right-multiplies, so a vector
     * gets {@code Rx(3π/4)} first and {@code Ry(-π/8)} second — the same first
     * two steps as the 3D rig, without the level rotation or the scale that
     * follow them there.
     *
     * <p>Sanity check that this is the right pair for flat items: a
     * camera-facing surface ({@code n = (0, 0, 1)}) comes out at exactly
     * {@code 1.0} here, which is why an inventory sprite looks identical to
     * its source PNG. Under the 3D rig the same surface is {@code 0.513}.</p>
     */
    private static final double[] LIGHT0_FLAT =
        rotateY(rotateX(DIFFUSE_LIGHT_0, 3.0 * Math.PI / 4.0), -Math.PI / 8.0);

    /** See {@link #LIGHT0_FLAT}. */
    private static final double[] LIGHT1_FLAT =
        rotateY(rotateX(DIFFUSE_LIGHT_1, 3.0 * Math.PI / 4.0), -Math.PI / 8.0);

    /**
     * Vanilla's ambient/diffuse split from {@code light.glsl}: RGB is
     * multiplied by {@code min(1, (d0+d1)*LIGHT_POWER + AMBIENT_LIGHT)}, alpha
     * is untouched.
     */
    private static final double LIGHT_POWER = 0.6;
    private static final double AMBIENT_LIGHT = 0.4;

    /**
     * Output edge length for a source texture of 64px or less. Bedrock item
     * icons are not limited to 16x16 — the sprite is scaled to the slot — so
     * rendering larger keeps diagonal blades from turning into staircases at
     * the sizes players actually see.
     */
    public static final int DEFAULT_SIZE = 64;

    /**
     * Ceiling on {@link #sizeFor}. Past this the icon costs more to render and
     * ship than a Bedrock inventory slot can show.
     */
    public static final int MAX_SIZE = 256;

    /**
     * Output edge length for a model textured by {@code sourceEdge}-pixel art.
     *
     * <p>A fixed {@link #DEFAULT_SIZE} silently <em>downscaled</em> every
     * high-resolution item. The reference pack ships 128, 256 and 512px item
     * textures, and rendering those into 64x64 threw the detail away: one
     * 128px cane went from 864 distinct colours and no partial alpha to 337
     * colours and 383 partially transparent pixels, which reads exactly as the
     * reported "size and position are right but the quality is bad" — the
     * silhouette survives a downscale, the artwork does not.
     *
     * <p>Rounding up to a whole multiple of the source keeps texel edges on
     * output-pixel boundaries, so pixel art stays crisp instead of being
     * resampled onto a grid that does not divide it. That is also why 32px art
     * is not left at 64: it is already a clean 2x. The real repair is simply
     * never choosing a size below the source.
     *
     * @param sourceEdge longest edge of the source texture, in pixels
     */
    public static int sizeFor(int sourceEdge) {
        if (sourceEdge <= 0) {
            return DEFAULT_SIZE;
        }
        if (sourceEdge >= MAX_SIZE) {
            return MAX_SIZE;
        }
        if (sourceEdge <= DEFAULT_SIZE) {
            // Whole multiple of the source that reaches DEFAULT_SIZE, so a
            // 16px sprite still renders at 64 (4x) rather than at 16.
            int multiple = Math.max(1, DEFAULT_SIZE / sourceEdge);
            return sourceEdge * multiple;
        }
        return sourceEdge;
    }

    /**
     * Supersampling factor. An axis-aligned model lands on exact sample
     * boundaries and downsamples losslessly; rotated ones get their edges
     * averaged instead of aliased.
     */
    private static final int SUPERSAMPLE = 4;

    /**
     * Minimum fraction of the canvas a render must paint to be accepted as an
     * icon. Below this the projection is assumed degenerate (see
     * {@link #render}).
     */
    private static final double MIN_COVERAGE = 0.02;

    /** Face names in Java's declaration order, paired with their corner table. */
    private static final String[] FACE_NAMES =
        {"down", "up", "north", "south", "west", "east"};

    /**
     * Vanilla {@code FaceInfo} vertex table. Each face lists four corners, each
     * corner naming which extent to take per axis: {@code 0 = min, 1 = max}.
     * Order is {@code {x, y, z}} and matches {@code FaceInfo.<FACE>} exactly.
     */
    private static final int[][][] FACE_CORNERS = {
        // DOWN:  (minX,minY,maxZ) (minX,minY,minZ) (maxX,minY,minZ) (maxX,minY,maxZ)
        {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},
        // UP:    (minX,maxY,minZ) (minX,maxY,maxZ) (maxX,maxY,maxZ) (maxX,maxY,minZ)
        {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
        // NORTH: (maxX,maxY,minZ) (maxX,minY,minZ) (minX,minY,minZ) (minX,maxY,minZ)
        {{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}},
        // SOUTH: (minX,maxY,maxZ) (minX,minY,maxZ) (maxX,minY,maxZ) (maxX,maxY,maxZ)
        {{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}},
        // WEST:  (minX,maxY,minZ) (minX,minY,minZ) (minX,minY,maxZ) (minX,maxY,maxZ)
        {{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}},
        // EAST:  (maxX,maxY,maxZ) (maxX,minY,maxZ) (maxX,minY,minZ) (maxX,maxY,minZ)
        {{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}},
    };

    private ItemIconRenderer() {}

    /**
     * Renders {@code geometry} into an icon, or returns {@code null} when there
     * is nothing to draw (no elements, no resolvable texture, or every face
     * ended up off-canvas). Callers should fall back to the raw sprite on
     * {@code null} — an atlas-looking icon beats a blank slot.
     *
     * @param geometry model elements; {@code null} or empty yields {@code null}
     * @param gui      the model's {@code display.gui} transform; {@code null}
     *                 is treated as identity, which is what Java does for a
     *                 model that omits the slot
     * @param textures resolves a face's texture reference ({@code "#0"}, and
     *                 also the bare {@code "0"} form) to an image; may return
     *                 {@code null}, in which case the fallback is used
     * @param fallbackTexture used for any face whose reference does not resolve
     * @param size     output edge length in pixels
     */
    public static BufferedImage render(
        JavaModelGeometry geometry,
        JavaModelDisplay.Transform gui,
        Function<String, BufferedImage> textures,
        BufferedImage fallbackTexture,
        int size,
        Logger logger
    ) {
        if (geometry == null || !geometry.hasElements() || size <= 0) {
            return null;
        }
        if (fallbackTexture == null && textures == null) {
            return null;
        }

        int ss = size * SUPERSAMPLE;
        int[] colour = new int[ss * ss];
        float[] depth = new float[ss * ss];
        java.util.Arrays.fill(depth, Float.NEGATIVE_INFINITY);

        float[] rot = gui != null ? gui.rotation() : new float[]{0f, 0f, 0f};
        float[] trans = gui != null ? gui.translation() : new float[]{0f, 0f, 0f};
        float[] scale = gui != null ? gui.scale() : new float[]{1f, 1f, 1f};

        boolean drewAnything = false;
        for (JavaModelGeometry.Element element : geometry.elements()) {
            if (element == null) {
                continue;
            }
            Map<String, JavaModelGeometry.Face> faces = element.faces();
            if (faces == null || faces.isEmpty()) {
                continue;
            }
            for (int f = 0; f < FACE_NAMES.length; f++) {
                JavaModelGeometry.Face face = faces.get(FACE_NAMES[f]);
                if (face == null) {
                    continue;
                }
                BufferedImage texture = resolveTexture(face, textures, fallbackTexture);
                if (texture == null) {
                    continue;
                }
                drewAnything |= drawFace(
                    element, face, f, texture, rot, trans, scale,
                    geometry.guiLightFront(), colour, depth, ss);
            }
        }

        if (!drewAnything) {
            if (logger != null) {
                logger.fine("[IconRender] no face produced pixels; falling back to raw sprite");
            }
            return null;
        }

        // "Drew something" is not the same as "drew a usable icon". A model
        // that never gets turned to face the camera — because it declares no
        // display.gui, or one that leaves it edge-on — projects to a sliver.
        // That is faithful to Java, but as an inventory icon it is strictly
        // worse than the atlas it would replace, so treat it as a failure and
        // let the caller keep the old behaviour. Real icons in the reference
        // pack cover 15.5%-28.5% of the canvas, so 2% clears them by ~8x
        // while still catching an edge-on projection.
        int painted = 0;
        for (int argb : colour) {
            if ((argb >>> 24) > 8) {
                painted++;
            }
        }
        if (painted < colour.length * MIN_COVERAGE) {
            if (logger != null) {
                logger.fine("[IconRender] render covered only "
                    + String.format(java.util.Locale.ROOT, "%.2f%%",
                        100.0 * painted / colour.length)
                    + " of the canvas (likely an edge-on projection);"
                    + " falling back to raw sprite");
            }
            return null;
        }
        return downsample(colour, ss, size);
    }

    // -----------------------------------------------------------------
    // geometry
    // -----------------------------------------------------------------

    /** @return true when at least one pixel was written. */
    private static boolean drawFace(
        JavaModelGeometry.Element element,
        JavaModelGeometry.Face face,
        int faceIndex,
        BufferedImage texture,
        float[] guiRotation,
        float[] guiTranslation,
        float[] guiScale,
        boolean frontLit,
        int[] colour,
        float[] depth,
        int ss
    ) {
        float[] from = element.from();
        float[] to = element.to();
        int[][] corners = FACE_CORNERS[faceIndex];

        float[][] pos = new float[4][];
        float[][] uv = new float[4][];
        for (int i = 0; i < 4; i++) {
            // Vanilla: vertex = vertexInfo.select(from, to).div(16)
            float[] v = {
                (corners[i][0] == 0 ? from[0] : to[0]) / 16f,
                (corners[i][1] == 0 ? from[1] : to[1]) / 16f,
                (corners[i][2] == 0 ? from[2] : to[2]) / 16f
            };
            applyElementRotation(v, element.rotation());
            applyGuiTransform(v, guiRotation, guiTranslation, guiScale);
            pos[i] = v;
            uv[i] = cornerUv(face, i, texture);
        }

        // Flat shading: one normal for the whole quad, taken after rotation
        // and the gui transform so a tilted/rotated element shades correctly
        // instead of by its untransformed axis-aligned direction (see class
        // javadoc "Shading" section). The quad is planar (rotation and the
        // single-axis rescale both preserve planarity), so any two edges from
        // the same corner give the same normal as the other triangle would.
        float shade = (float) shadeFactor(faceNormal(pos[0], pos[1], pos[2]), frontLit);

        boolean drew = rasterize(pos[0], pos[1], pos[2], uv[0], uv[1], uv[2],
            texture, shade, colour, depth, ss);
        drew |= rasterize(pos[0], pos[2], pos[3], uv[0], uv[2], uv[3],
            texture, shade, colour, depth, ss);
        return drew;
    }

    /**
     * Outward face normal from three corners in the renderer's screen space
     * ({@code +X} right, {@code +Y} up, {@code +Z} toward the camera — see
     * class javadoc). Winding verified empirically against an unrotated south
     * face (Java's {@code FaceInfo.SOUTH} corner order): with
     * {@code cross(pos1-pos0, pos2-pos0)} an unrotated south face (which sits
     * at {@code z = 0.5} facing the camera) comes out {@code (0,0,1)}, and the
     * unrotated north face (facing away, at {@code z = -0.5}) comes out
     * {@code (0,0,-1)} — both outward, so this is the correct operand order
     * and not its negation.
     */
    private static double[] faceNormal(float[] p0, float[] p1, float[] p2) {
        double ex = p1[0] - p0[0], ey = p1[1] - p0[1], ez = p1[2] - p0[2];
        double fx = p2[0] - p0[0], fy = p2[1] - p0[1], fz = p2[2] - p0[2];
        double nx = ey * fz - ez * fy;
        double ny = ez * fx - ex * fz;
        double nz = ex * fy - ey * fx;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-9) {
            // Degenerate quad (zero-area element, e.g. a from==to plane
            // viewed exactly edge-on so the two edges are parallel). No
            // well-defined normal; fully unlit is the least-wrong answer and
            // matches what an edge-on triangle contributes to the image
            // anyway (near-zero screen area).
            return new double[]{0.0, 0.0, 0.0};
        }
        return new double[]{nx / len, ny / len, nz / len};
    }

    /**
     * Vanilla's two-directional diffuse formula, ported verbatim from
     * {@code minecraft_mix_light_separate} in {@code light.glsl} (see class
     * javadoc): {@code min(1, (max(0,dot(L0,n)) + max(0,dot(L1,n))) * 0.6 + 0.4)}.
     * A degenerate (zero-length) normal dots to zero with both lights and
     * lands on the ambient floor, {@link #AMBIENT_LIGHT}.
     *
     * <p><b>The normal is flipped in Y first.</b> Mojang's light matrices are
     * expressed in the space the GUI pose stack leaves the geometry in, and
     * that stack flips Y; this renderer keeps {@code pos[]} Y-up all the way
     * through {@link #faceNormal} and flips once at the very end, in
     * {@link #toScreenY}. One flip on the normal puts the two back in the same
     * space and lets both matrices above stay verbatim copies of the source.
     * Skipping it inverts the Y term of the dot product, and that is not
     * subtle: it renders every icon lit from underneath — for a vanilla block
     * icon ({@code display.gui} rotation {@code [30, 225, 0]}) the top face
     * drops to the {@link #AMBIENT_LIGHT} floor 0.4 and the hidden underside
     * goes to 1.0, where the top should be the brightest face of the three
     * visible ones (1.0 / 0.65 / 0.4). It shipped that way once, because every
     * normal that is convenient to hand-write in a test has {@code y == 0} and
     * is therefore blind to it.</p>
     *
     * <p>The flip applies to <em>both</em> rigs, even though only the 3D
     * matrix carries a {@code scaling(1, -1, 1)} of its own. That asymmetry is
     * vanilla's: {@code setupGuiFlatDiffuseLighting} genuinely omits the
     * scaling. It is invisible in vanilla because the flat rig is only ever
     * bound for flat quads, whose normal has no Y term either way — but we
     * bind it for the reference pack's 49 front-lit models that do have real
     * geometry, so the flip has to be here rather than folded into
     * {@link #LIGHT0_FLAT}. Reading the two rigs off the one validated case
     * (the block icon above, where the correct answer is
     * {@code dot(LIGHT0_3D, flipY(n))}) fixes the eye-space normal as
     * {@code flipY(n)} for everything drawn in the same pass, flat rig
     * included.</p>
     *
     * <p>Package-visible so the test can pin the arithmetic against hand-fed
     * normals directly. An axis-aligned normal like {@code (1,0,0)} cannot be
     * exercised through {@link #render} at identity {@code display.gui}: a
     * face whose normal has zero Z is by construction edge-on to this
     * renderer's orthographic-along-Z camera, so {@link #rasterize} sees zero
     * screen area and never calls this method for it — there is no rotation
     * that makes such a face visible without also changing its normal away
     * from the axis-aligned value the test wants to pin.</p>
     *
     * @param frontLit the model's resolved {@code gui_light == "front"}
     */
    static double shadeFactor(double[] normal, boolean frontLit) {
        double[] n = flipY(normal);
        double[] light0 = frontLit ? LIGHT0_FLAT : LIGHT0_3D;
        double[] light1 = frontLit ? LIGHT1_FLAT : LIGHT1_3D;
        double d0 = Math.max(0.0, dot(light0, n));
        double d1 = Math.max(0.0, dot(light1, n));
        return Math.min(1.0, (d0 + d1) * LIGHT_POWER + AMBIENT_LIGHT);
    }

    /** {@code scaling(1, -1, 1)}. */
    private static double[] flipY(double[] v) {
        return new double[]{v[0], -v[1], v[2]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] normalize(double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / len, y / len, z / len};
    }

    /**
     * Rotation about X, using the same sign convention as
     * {@link #applyGuiTransform}'s X block ({@code y' = y*cos - z*sin},
     * {@code z' = y*sin + z*cos}), which matches JOML's
     * {@code Matrix4f.rotationX} (verified against JOML source: the resulting
     * matrix has {@code m11=cos, m12=sin, m21=-sin, m22=cos} in JOML's
     * column-major layout, which is exactly this formula).
     */
    private static double[] rotateX(double[] v, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return new double[]{v[0], v[1] * cos - v[2] * sin, v[1] * sin + v[2] * cos};
    }

    /**
     * Rotation about Y, using the same sign convention as
     * {@link #applyGuiTransform}'s Y block ({@code x' = x*cos + z*sin},
     * {@code z' = -x*sin + z*cos}), verified against JOML's
     * {@code Matrix4f.rotationY} the same way as {@link #rotateX}.
     */
    private static double[] rotateY(double[] v, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return new double[]{v[0] * cos + v[2] * sin, v[1], -v[0] * sin + v[2] * cos};
    }

    /**
     * Rotates a vertex about the element's rotation origin, mirroring
     * {@code FaceBakery.rotateVertexBy}. {@code rescale} stretches the two axes
     * perpendicular to the rotation axis by {@code 1/cos(angle)} so a rotated
     * element still meets its neighbours, exactly as Java does.
     */
    private static void applyElementRotation(float[] v, JavaModelGeometry.ElementRotation rotation) {
        if (rotation == null) {
            return;
        }
        float[] origin = rotation.origin();
        float ox = (origin != null ? origin[0] : 8f) / 16f;
        float oy = (origin != null ? origin[1] : 8f) / 16f;
        float oz = (origin != null ? origin[2] : 8f) / 16f;

        if (rotation.euler() != null) {
            applyEulerRotation(v, rotation.euler(), ox, oy, oz);
            return;
        }
        if (rotation.angle() == 0f || rotation.axis() == null) {
            return;
        }

        float x = v[0] - ox;
        float y = v[1] - oy;
        float z = v[2] - oz;

        double rad = Math.toRadians(rotation.angle());
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        // Java's "rescale" flag stretches the two axes perpendicular to the
        // rotation axis by 1/cos(angle), so a rotated element still spans its
        // original footprint (sqrt(2) at the canonical 45 degrees). The guard
        // on cos covers the degenerate 90-degree case, which the model format
        // does not permit but a hand-edited pack could carry.
        float k = 1f;
        if (rotation.rescale() && Math.abs(cos) > 1e-4f) {
            k = 1f / Math.abs(cos);
        }

        switch (rotation.axis().toLowerCase(java.util.Locale.ROOT)) {
            case "x" -> {
                float ny = y * cos - z * sin;
                float nz = y * sin + z * cos;
                y = ny * k;
                z = nz * k;
            }
            case "y" -> {
                float nx = x * cos + z * sin;
                float nz = -x * sin + z * cos;
                x = nx * k;
                z = nz * k;
            }
            default -> {
                float nx = x * cos - y * sin;
                float ny = x * sin + y * cos;
                x = nx * k;
                y = ny * k;
            }
        }

        v[0] = x + ox;
        v[1] = y + oy;
        v[2] = z + oz;
    }

    /**
     * Rotates a vertex by a Blockbench free-rotation {@code {x, y, z}} triple
     * about the element's origin.
     *
     * <p><b>Why this branch has to exist:</b> without it the whole icon is
     * wrong, not slightly wrong. A model like the 広辞苑 book pre-rotates its
     * covers by roughly 90° about Y and then declares a {@code display.gui}
     * that rotates back by roughly -90°, so the two cancel and the book faces
     * the camera. Skipping the element half leaves only the gui half, which
     * turns every flat slab edge-on and renders the icon as a few stripes of
     * page edge. That is exactly the artifact this class was written to
     * eliminate.</p>
     *
     * <p>Composition is {@code Rx·Ry·Rz} (Z applied to the vertex first),
     * matching {@link #applyGuiTransform} and
     * {@link BedrockGeometryConverter#convertElementRotation}. For the
     * ±180°/±180° pairs Blockbench emits on the X and Z axes the two candidate
     * orders produce the same matrix, so this choice is not load-bearing for
     * the models in the pack — but keeping all three sites on one convention
     * means a future free-rotation model cannot disagree between the icon, the
     * attachable and the Bedrock geometry.</p>
     *
     * <p>{@code rescale} is deliberately not applied: it is a vanilla
     * single-axis flag that widens an element so a 45° rotation still meets its
     * neighbours, and Blockbench never sets it alongside a free-rotation
     * triple.</p>
     */
    private static void applyEulerRotation(
        float[] v, float[] euler, float ox, float oy, float oz
    ) {
        if (euler[0] == 0f && euler[1] == 0f && euler[2] == 0f) {
            return;
        }
        float x = v[0] - ox;
        float y = v[1] - oy;
        float z = v[2] - oz;

        double rz = Math.toRadians(euler[2]);
        float cz = (float) Math.cos(rz);
        float sz = (float) Math.sin(rz);
        float x1 = x * cz - y * sz;
        float y1 = x * sz + y * cz;

        double ry = Math.toRadians(euler[1]);
        float cy = (float) Math.cos(ry);
        float sy = (float) Math.sin(ry);
        float x2 = x1 * cy + z * sy;
        float z2 = -x1 * sy + z * cy;

        double rx = Math.toRadians(euler[0]);
        float cx = (float) Math.cos(rx);
        float sx = (float) Math.sin(rx);
        float y3 = y1 * cx - z2 * sx;
        float z3 = y1 * sx + z2 * cx;

        v[0] = x2 + ox;
        v[1] = y3 + oy;
        v[2] = z3 + oz;
    }

    /**
     * Applies {@code display.gui} the way the GUI pose stack composes it:
     * {@code apply()} pushes translate, rotate then scale, and the renderer
     * pushes {@code translate(-0.5)} after that, so the vertex is centred
     * first, then scaled, then rotated, then translated.
     *
     * <p>Translation is in Java's model units here and divided by 16 to reach
     * block space, matching {@code ItemTransform.Deserializer}'s
     * {@code mul(0.0625F)}.</p>
     */
    private static void applyGuiTransform(
        float[] v, float[] rotation, float[] translation, float[] scale
    ) {
        float x = (v[0] - 0.5f) * scale[0];
        float y = (v[1] - 0.5f) * scale[1];
        float z = (v[2] - 0.5f) * scale[2];

        // JOML rotationXYZ builds Rx·Ry·Rz, so apply Z first, then Y, then X.
        double rz = Math.toRadians(rotation[2]);
        float cz = (float) Math.cos(rz);
        float sz = (float) Math.sin(rz);
        float x1 = x * cz - y * sz;
        float y1 = x * sz + y * cz;

        double ry = Math.toRadians(rotation[1]);
        float cy = (float) Math.cos(ry);
        float sy = (float) Math.sin(ry);
        float x2 = x1 * cy + z * sy;
        float z2 = -x1 * sy + z * cy;

        double rx = Math.toRadians(rotation[0]);
        float cx = (float) Math.cos(rx);
        float sx = (float) Math.sin(rx);
        float y3 = y1 * cx - z2 * sx;
        float z3 = y1 * sx + z2 * cx;

        v[0] = x2 + translation[0] / 16f;
        v[1] = y3 + translation[1] / 16f;
        v[2] = z3 + translation[2] / 16f;
    }

    /**
     * Per-corner UV, ported from {@code BlockElementFace.UVs.getVertexU/V}
     * combined with {@code Quadrant.rotateVertexIndex}. Returns normalised
     * texture coordinates (0..1), already scaled to the supplied image.
     */
    private static float[] cornerUv(
        JavaModelGeometry.Face face, int index, BufferedImage texture
    ) {
        float[] raw = face.hasUv() ? face.uv() : new float[]{0f, 0f, 16f, 16f};
        int shift = ((face.rotation() / 90) % 4 + 4) % 4;
        int i = (index + shift) % 4;
        // getVertexU: min for corners 0 and 1, max otherwise.
        float u = (i == 0 || i == 1) ? raw[0] : raw[2];
        // getVertexV: min for corners 0 and 3, max otherwise.
        float v = (i == 0 || i == 3) ? raw[1] : raw[3];
        return new float[]{
            u / 16f * texture.getWidth(),
            v / 16f * spriteHeight(texture)
        };
    }

    /**
     * Height of the sampled region — the whole image.
     *
     * <p>An earlier version guessed at animated sprite strips here (treating
     * any image whose height is an exact multiple of its width as an N-frame
     * filmstrip and sampling only the first frame). That guess was dropped:
     * it keys off dimensions alone with no {@code .mcmeta} to confirm, so it
     * would silently crop a legitimately tall non-animated atlas in half, and
     * it disagreed with {@link BedrockGeometryConverter}, which scales UVs by
     * the full PNG height. Animated item textures are a known limitation of
     * both, and having them wrong the same way in both places is better than
     * having the icon and the 3D model sample differently.</p>
     */
    private static int spriteHeight(BufferedImage texture) {
        return texture.getHeight();
    }

    private static BufferedImage resolveTexture(
        JavaModelGeometry.Face face,
        Function<String, BufferedImage> textures,
        BufferedImage fallback
    ) {
        if (textures != null) {
            String ref = face.texture();
            if (ref != null && !ref.isBlank()) {
                BufferedImage resolved = textures.apply(ref);
                if (resolved == null && ref.startsWith("#")) {
                    resolved = textures.apply(ref.substring(1));
                }
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return fallback;
    }

    // -----------------------------------------------------------------
    // rasterisation
    // -----------------------------------------------------------------

    /**
     * Fills one triangle with a z-buffer test, interpolating UV linearly.
     * Linear screen-space interpolation is exact here because the projection is
     * orthographic — there is no perspective divide to correct for.
     *
     * @return true when at least one pixel passed the depth test
     */
    private static boolean rasterize(
        float[] a, float[] b, float[] c,
        float[] uvA, float[] uvB, float[] uvC,
        BufferedImage texture,
        float shade,
        int[] colour, float[] depth, int ss
    ) {
        float ax = toScreenX(a[0], ss), ay = toScreenY(a[1], ss);
        float bx = toScreenX(b[0], ss), by = toScreenY(b[1], ss);
        float cx = toScreenX(c[0], ss), cy = toScreenY(c[1], ss);

        float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (Math.abs(area) < 1e-9f) {
            return false;
        }

        int minX = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
        int maxX = Math.min(ss - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
        int maxY = Math.min(ss - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
        if (minX > maxX || minY > maxY) {
            return false;
        }

        boolean drew = false;
        for (int py = minY; py <= maxY; py++) {
            float sy = py + 0.5f;
            for (int px = minX; px <= maxX; px++) {
                float sx = px + 0.5f;
                float w0 = ((bx - sx) * (cy - sy) - (by - sy) * (cx - sx)) / area;
                float w1 = ((cx - sx) * (ay - sy) - (cy - sy) * (ax - sx)) / area;
                float w2 = 1f - w0 - w1;
                if (w0 < 0f || w1 < 0f || w2 < 0f) {
                    continue;
                }
                float z = w0 * a[2] + w1 * b[2] + w2 * c[2];
                int idx = py * ss + px;
                if (z <= depth[idx]) {
                    continue;
                }
                float u = w0 * uvA[0] + w1 * uvB[0] + w2 * uvC[0];
                float v = w0 * uvA[1] + w1 * uvB[1] + w2 * uvC[1];
                int argb = sample(texture, u, v);
                if ((argb >>> 24) == 0) {
                    // Fully transparent texels must not claim the depth slot,
                    // or a cut-out face in front hides the solid one behind it.
                    continue;
                }
                depth[idx] = z;
                colour[idx] = shaded(argb, shade);
                drew = true;
            }
        }
        return drew;
    }

    /** GUI space: +X right, origin centred, one block spanning the canvas. */
    private static float toScreenX(float x, int ss) {
        return ss / 2f + x * ss;
    }

    /** GUI space: +Y up, so screen Y is flipped (pose stack scale(1, -1, 1)). */
    private static float toScreenY(float y, int ss) {
        return ss / 2f - y * ss;
    }

    /** Nearest-neighbour sampling — Minecraft never filters item textures. */
    private static int sample(BufferedImage texture, float u, float v) {
        int x = clamp((int) Math.floor(u), 0, texture.getWidth() - 1);
        int y = clamp((int) Math.floor(v), 0, spriteHeight(texture) - 1);
        return texture.getRGB(x, y);
    }

    /**
     * Multiplies the RGB channels of a straight (non-premultiplied) ARGB
     * texel by the face's shading factor, leaving alpha untouched — matching
     * {@code minecraft_mix_light_separate}'s {@code color.rgb * lightAccum}
     * (alpha passes through unmodified). Applying this before the value
     * enters {@code colour[]} means {@link #downsample}'s premultiplication
     * sees already-shaded colour, so it does not need to know about shading
     * at all.
     */
    private static int shaded(int argb, float shade) {
        int a = argb >>> 24;
        int r = clamp(Math.round(((argb >> 16) & 0xFF) * shade), 0, 255);
        int g = clamp(Math.round(((argb >> 8) & 0xFF) * shade), 0, 255);
        int b = clamp(Math.round((argb & 0xFF) * shade), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    /**
     * Box-filters the supersampled buffer down to the output size, averaging in
     * premultiplied space so transparent texels do not drag colour into the
     * edges.
     */
    private static BufferedImage downsample(int[] colour, int ss, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int block = ss / size;
        int samples = block * block;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                long a = 0, r = 0, g = 0, b = 0;
                for (int dy = 0; dy < block; dy++) {
                    int row = (y * block + dy) * ss + x * block;
                    for (int dx = 0; dx < block; dx++) {
                        int argb = colour[row + dx];
                        int alpha = argb >>> 24;
                        a += alpha;
                        r += ((argb >> 16) & 0xFF) * alpha;
                        g += ((argb >> 8) & 0xFF) * alpha;
                        b += (argb & 0xFF) * alpha;
                    }
                }
                int outA = (int) (a / samples);
                int outArgb;
                if (a == 0) {
                    outArgb = 0;
                } else {
                    outArgb = (outA << 24)
                        | (clamp((int) (r / a), 0, 255) << 16)
                        | (clamp((int) (g / a), 0, 255) << 8)
                        | clamp((int) (b / a), 0, 255);
                }
                out.setRGB(x, y, outArgb);
            }
        }
        return out;
    }

    /** Convenience overload using {@link #DEFAULT_SIZE}. */
    public static BufferedImage render(
        JavaModelGeometry geometry,
        JavaModelDisplay.Transform gui,
        BufferedImage texture,
        Logger logger
    ) {
        return render(geometry, gui, null, texture, DEFAULT_SIZE, logger);
    }

    /** Face names this renderer understands, in Java's order. */
    public static List<String> faceNames() {
        return List.of(FACE_NAMES);
    }
}
