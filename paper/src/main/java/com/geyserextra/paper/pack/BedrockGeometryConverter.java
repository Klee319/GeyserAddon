package com.geyserextra.paper.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Pure functions that convert Java edition transform / geometry values into
 * the matching Bedrock edition representation.
 *
 * <p>This class is the single source of truth for axis-sign conventions
 * between the two engines. The conventions are ported verbatim from the
 * field-proven <a href="https://github.com/Kas-tle/java2bedrock.sh">java2bedrock</a>
 * converter (the standard Geyser companion tool): Bedrock's attachable frame
 * mirrors Java's X axis, so geometry is X-mirrored ({@code origin.x = 8 - to.x})
 * and display rotations flip sign on X and Y while Z stays as-is.</p>
 *
 * <p><b>Phase 3:</b> display-transform conversion (used by animations).</p>
 * <p><b>Phase 4:</b> {@link #convertElementsToCubes} converts Java
 * {@code elements} into Bedrock entity cubes for full 3D held-item geometry.</p>
 */
public final class BedrockGeometryConverter {

    /**
     * The one Java&rarr;Bedrock rotation sign convention this converter uses,
     * for <b>every</b> rotation it emits: X and Y negate, Z keeps its sign.
     *
     * <p>Ground truth is Blockbench, which is what authors preview against and
     * what every Bedrock pack in the wild is built with. Its Bedrock codec
     * writes exactly this triple when exporting the model it is displaying —
     * cubes, bones, locators and texture meshes all go through the same rule:</p>
     * <pre>
     *   // js/formats/bedrock/bedrock.js, compileCube / compileGroup
     *   template.rotation = cube.rotation.slice();
     *   template.rotation.forEach((br, axis) =&gt; { if (axis != 2) template.rotation[axis] *= -1 });
     * </pre>
     * <p>and its Java codec writes {@code element.rotation.x/y/z} straight from
     * the same internal value with no sign change, so "Blockbench internal" and
     * "Java model JSON" are the same numbers. The X negation is not the
     * geometry mirror ({@code origin.x = 8 - to.x}) — that is applied
     * separately and on top; the triple above is what remains once the mirror
     * is accounted for.</p>
     *
     * <p>Cube rotations used to run on a second, contradictory table
     * ({@code -x, +y, +z}) taken from GeyserMC Rainbow's
     * {@code GeometryMapper.getBedrockRotation}, which carries a
     * "TODO check if these angle transformations are right" on that very line.
     * Rainbow's X and Z agree with Blockbench; only Y does not. That made a
     * Y-axis element rotation come out 2&times;&theta; away from where Java
     * puts it — for the 広辞苑 book, whose covers rotate about Y at a pivot on
     * the spine, a 182&deg; error that swings each cover clear of the pages
     * while leaving its silhouette unchanged, so it reads as "the cover is
     * offset from the book" rather than as a rotation fault. Nothing else in
     * the pack noticed, because X- and Z-axis element rotations convert
     * identically under both tables.</p>
     *
     * <p><b>The table is exact for three-axis rotations too, not just one.</b>
     * That was an open question here for a while, so the argument is recorded
     * rather than left to be re-derived.</p>
     *
     * <p>The single-axis behaviour fixes what a Bedrock triple {@code (a, b, c)}
     * <em>means</em> as a product of primitives. Java's {@code Rx(θ)} must come
     * out as {@code (-θ, 0, 0)} while the mirror leaves it alone
     * ({@code M·Rx·M == Rx}); Java's {@code Ry(θ)} must come out as
     * {@code (0, -θ, 0)} while the mirror gives {@code Ry(-θ)}; Java's
     * {@code Rz(θ)} must come out as {@code (0, 0, +θ)} while the mirror gives
     * {@code Rz(-θ)}. Solve those and the primitives are forced:
     * {@code Bx(a) = Rx(-a)}, {@code By(b) = Ry(b)}, {@code Bz(c) = Rz(-c)}.</p>
     *
     * <p>Compose them in Java's own order — vanilla's multi-axis element
     * rotation is {@code CuboidRotation.EulerXYZRotation}, i.e. {@code Rx·Ry·Rz}
     * — and the required orientation drops out identically:</p>
     * <pre>
     *   Bx(-jx)·By(-jy)·Bz(jz) = Rx(jx)·Ry(-jy)·Rz(-jz) = M·(Rx(jx)·Ry(jy)·Rz(jz))·M
     * </pre>
     * <p>because conjugating by {@code M = diag(-1, 1, 1)} distributes across
     * the product and flips exactly Y and Z. Checked numerically over 20000
     * random triples: worst matrix error 0. So the flip is not an approximation
     * that happens to be right on one axis — it is the general solution,
     * provided Bedrock composes {@code XYZ} like Java.</p>
     *
     * <p><b>Why XYZ and not ZYX.</b> Blockbench's {@code Format.euler_order}
     * defaults to {@code 'ZYX'} and no format overrides it, which reads as
     * evidence for {@code Rz·Ry·Rx} and was believed here briefly. It cannot be
     * right for what Bedrock does with these files. Under {@code ZYX} the flip
     * would be wrong by 76.2° on {@code item/handheld}'s own
     * {@code thirdperson [0, -90, 55]}, 62.8° on the {@code [45, 0, 90]} pose 49
     * models in the pack declare, and 76.2° on {@code [55, 0, ±90]} on another
     * 70 — every tool in the game held at a visibly absurd angle. Those poses
     * render correctly on a real client today under this table, and java2bedrock
     * has shipped the same flip for years. In-game behaviour outranks a default
     * in an editor's format descriptor, which plausibly describes Blockbench's
     * own editing convention rather than the engine's.</p>
     */
    private static final float ROT_X_SIGN = -1f;
    private static final float ROT_Y_SIGN = -1f;
    private static final float ROT_Z_SIGN = +1f;

    private BedrockGeometryConverter() {}

    /**
     * Converts a Java {@code display.*.rotation} array into Bedrock animation
     * bone {@code rotation} degrees. Returns a fresh array — callers can
     * mutate without affecting the input.
     */
    public static float[] convertRotation(float[] javaRotation) {
        if (javaRotation == null || javaRotation.length < 3) {
            return new float[]{0f, 0f, 0f};
        }
        return new float[]{
            ROT_X_SIGN * javaRotation[0],
            ROT_Y_SIGN * javaRotation[1],
            ROT_Z_SIGN * javaRotation[2]
        };
    }

    /**
     * Applies Java's own left-hand rule to a display transform's rotation,
     * <b>before</b> any Java&rarr;Bedrock conversion.
     *
     * <p>Vanilla {@code ItemTransform#apply(boolean leftHand, PoseStack)}
     * negates rotation Y and Z whenever the item renders in the left hand,
     * unconditionally and for whichever transform the slot resolved to. A model
     * that declares {@code thirdperson_lefthand.rotation = [0, 90, -55]}
     * against a right hand of {@code [0, -90, 55]} is therefore asking to be
     * rendered at {@code [0, -90, 55]} in <em>both</em> hands; the declared
     * values are pre-compensation for this negation, not the pose the author
     * wants to see. 52 of the reference pack's 100 display-bearing models
     * declare the slots that way.</p>
     *
     * <p>This was removed once, on the theory that Bedrock's off-hand
     * attachment already accounts for handedness so the negation would
     * double-count. It does not: what actually double-counted was the
     * first-person root mirror, which is why the symptom was first reported as
     * first-person only. Dropping the negation instead turned every off-hand
     * item 90&deg; in <em>third</em> person too, where no mirror had ever been
     * applied. The negation stays; see {@code buildHoldAnimation} for the root
     * that went away in its place.</p>
     *
     * <p>The translation half lives in
     * {@link #applyJavaLeftHandTranslation}; the two are always applied
     * together, because they are two halves of one {@code apply()} call.</p>
     */
    public static float[] applyJavaLeftHandRotation(float[] javaRotation) {
        if (javaRotation == null || javaRotation.length < 3) {
            return new float[]{0f, 0f, 0f};
        }
        return new float[]{javaRotation[0], -javaRotation[1], -javaRotation[2]};
    }

    /**
     * The translation half of {@link #applyJavaLeftHandRotation}: negates the X
     * component of a display transform's translation, <b>before</b> any
     * Java&rarr;Bedrock conversion.
     *
     * <p>java2bedrock reaches the same emitted value by a different route: it
     * writes {@code +translation.x} for the off hand against
     * {@code -translation.x} for the main hand, which is what this negation
     * followed by the single Java&rarr;Bedrock mirror comes out to.</p>
     *
     * <p>Getting this sign wrong is off by {@code 2 * declared.x} toward the
     * main hand — nothing at all for a weapon that declares no X offset, and a
     * body-width for one that declares a large one. That proportionality is the
     * signature to look for: it is what a sign error looks like, not what a
     * wrong bone binding looks like.</p>
     */
    public static float[] applyJavaLeftHandTranslation(float[] javaTranslation) {
        if (javaTranslation == null || javaTranslation.length < 3) {
            return new float[]{0f, 0f, 0f};
        }
        return new float[]{-javaTranslation[0], javaTranslation[1], javaTranslation[2]};
    }

    /**
     * Converts a Java {@code display.*.translation} array into Bedrock
     * animation bone {@code position} (pixel units in both systems).
     *
     * <p>Sign matrix for a Java <b>righthand</b> transform. X is negated; Z
     * negates only in first person:</p>
     * <ul>
     *   <li>third-person: {@code (-x, y, z)}</li>
     *   <li>first-person: {@code (-x, y, -z)}</li>
     * </ul>
     * <p>The input must be the translation Java <em>renders</em>, not the one
     * the model declares: for the off hand, run
     * {@link #applyJavaLeftHandTranslation} first and then pass
     * {@code mirrorX = true} like any other transform. There is no
     * "lefthand-sourced" special case here — which slot the values came from
     * says nothing about whether Java's left-hand negation has been applied to
     * them, because {@code ItemTransforms.Deserializer} substitutes the
     * right-hand object for a missing slot and {@code apply()} negates that
     * too.</p>
     *
     * @param mirrorX {@code true} to negate X (the Java&rarr;Bedrock mirror);
     *                {@code false} only for callers that have already mirrored
     */
    public static float[] convertTranslation(
        float[] javaTranslation, boolean firstPerson, boolean mirrorX
    ) {
        if (javaTranslation == null || javaTranslation.length < 3) {
            return new float[]{0f, 0f, 0f};
        }
        float zSign = firstPerson ? -1f : +1f;
        float xSign = mirrorX ? -1f : +1f;
        return new float[]{
            xSign * javaTranslation[0],
            javaTranslation[1],
            zSign * javaTranslation[2]
        };
    }

    /**
     * Euler composition order used to rebuild the attachable root bone's
     * rotation matrix in {@link #convertTranslationInRootFrame}.
     *
     * <p>{@link #J2B} is not an order at all — it selects java2bedrock's
     * per-axis sign flips ({@link #convertTranslation}). The remaining values
     * name the order in which Bedrock composes a bone's {@code [rx, ry, rz]}.
     * Which one Bedrock actually uses cannot be recovered from this pack: the
     * third-person root is {@code [90, 0, 0]}, and with two of the three angles
     * zero every order collapses to the same matrix. Only the first-person root
     * {@code [90, 60, -40]} distinguishes them, and there is no confirmed-good
     * first-person output to fit against — hence the switch.</p>
     */
    public enum TranslationFrame {
        /** java2bedrock per-axis sign flips. Exact only for an axis-aligned root. */
        J2B,
        ZYX, ZXY, XYZ, YXZ;

        public static TranslationFrame parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return J2B;
            }
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return J2B;
            }
        }
    }

    /**
     * Converts a Java {@code display.*.translation} into the local frame of the
     * attachable root bone, so the offset means in Bedrock what it meant in
     * Java.
     *
     * <p>{@link #convertTranslation}'s per-axis sign flips are only exact when
     * the root bone's rotation is axis-aligned. The third-person root
     * ({@code [90, 0, 0]}) is, so third person is correct today. The
     * first-person root ({@code [90, 60, -40]}) is not: a Java X offset lands
     * partly on Bedrock's X and partly on its Z, with an error proportional to
     * the offset's magnitude. Items whose display translation is near zero
     * never show it; the ones with a large translation are thrown out of
     * frame.</p>
     *
     * <p>The fix is the change of basis the sign flips approximate:
     * {@code p = R_root⁻¹ · M · t}. {@code M} is not guessed — it is recovered
     * exactly from confirmed-correct third-person output (Java
     * {@code [-15.5, 13, 1.5]} → emitted {@code [15.5, 13, 1.5]} under a
     * {@code [90, 0, 0]} root), giving {@code M(x, y, z) = (∓x, -z, y)}.</p>
     *
     * <p><b>{@code M} is hand-specific, not a universal Java&rarr;Bedrock axis
     * map.</b> Factored out it is {@code Rx(90) · mirrorX}, i.e. it already
     * carries the same 90° the held-item root declares, and the {@code R_root⁻¹}
     * above is what cancels it back out in third person. Do not reuse this for a
     * bone whose root is unrotated: the head slot's root is a pure
     * {@code [0, 19.9, 0]} translation, and feeding {@code M} through with an
     * identity root would turn {@code generated}'s head offset
     * {@code [0, 13, 7]} into {@code (0, -7, 13)} — every hat 7 pixels below the
     * head instead of 13 above it. With the root unrotated the whole change of
     * basis degenerates to the plain X mirror, which is exactly what
     * {@code BedrockAttachableWriter.buildHeadAnimation} applies. The two paths
     * agree; they only look different because one of them has a rotated root to
     * undo.</p>
     *
     * <p>Because a rotation matrix's inverse is its transpose and the
     * third-person root leaves only one non-zero angle, this reproduces
     * {@link #convertTranslation} bit-for-bit in third person under
     * <em>every</em> {@link TranslationFrame} — the switch can only ever change
     * first person. {@code BedrockGeometryConverterTest} asserts that.</p>
     *
     * @param rootRotationDeg the root bone's {@code [rx, ry, rz]} as emitted,
     *                        including any off-hand mirroring
     * @param mirrorX         as in {@link #convertTranslation}
     */
    public static float[] convertTranslationInRootFrame(
        float[] javaTranslation, float[] rootRotationDeg,
        boolean mirrorX, TranslationFrame frame
    ) {
        if (javaTranslation == null || javaTranslation.length < 3) {
            return new float[]{0f, 0f, 0f};
        }
        if (frame == null || frame == TranslationFrame.J2B
            || rootRotationDeg == null || rootRotationDeg.length < 3) {
            throw new IllegalArgumentException(
                "convertTranslationInRootFrame requires a non-J2B frame and a root rotation");
        }
        // M · t : Java item axes -> Bedrock arm axes.
        float sx = mirrorX ? -1f : +1f;
        double[] m = {
            sx * javaTranslation[0],
            -javaTranslation[2],
            javaTranslation[1]
        };
        double[][] r = rootMatrix(rootRotationDeg, frame);
        // R⁻¹ = Rᵀ for a rotation matrix, so this is a column-wise dot product.
        double[] p = new double[3];
        for (int i = 0; i < 3; i++) {
            p[i] = r[0][i] * m[0] + r[1][i] * m[1] + r[2][i] * m[2];
        }
        return new float[]{(float) p[0], (float) p[1], (float) p[2]};
    }

    private static double[][] rootMatrix(float[] deg, TranslationFrame frame) {
        double[][] x = axisMatrix(0, deg[0]);
        double[][] y = axisMatrix(1, deg[1]);
        double[][] z = axisMatrix(2, deg[2]);
        return switch (frame) {
            case ZYX -> mul(z, mul(y, x));
            case ZXY -> mul(z, mul(x, y));
            case XYZ -> mul(x, mul(y, z));
            case YXZ -> mul(y, mul(x, z));
            default -> throw new IllegalArgumentException("not a rotation order: " + frame);
        };
    }

    private static double[][] axisMatrix(int axis, double degrees) {
        double a = Math.toRadians(degrees);
        double c = Math.cos(a);
        double s = Math.sin(a);
        return switch (axis) {
            case 0 -> new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}};
            case 1 -> new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
            default -> new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
        };
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return out;
    }

    /**
     * Converts a Java {@code display.*.scale} array into Bedrock animation
     * bone {@code scale}. Scale is dimensionless and uses the same convention
     * in both engines, so this is a defensive copy with bounds normalisation.
     */
    public static float[] convertScale(float[] javaScale) {
        if (javaScale == null || javaScale.length < 3) {
            return new float[]{1f, 1f, 1f};
        }
        return new float[]{javaScale[0], javaScale[1], javaScale[2]};
    }

    // =========================================================================
    // Phase 4: element-cube conversion
    // =========================================================================

    /**
     * Converts a Java item model {@code elements} array into Bedrock entity
     * geometry cubes. Empty input → empty output, so callers can plug the
     * result straight into the {@code bones[0].cubes} array of a Bedrock
     * geometry JSON without further guards.
     *
     * <p>Coordinate mapping (item models, 0..16 range):</p>
     * <ul>
     *   <li>Bedrock cube origin (lower-X / lower-Y / lower-Z corner):
     *       {@code (8 - java.to.x, java.from.y, java.from.z - 8)}.
     *       X is <b>mirrored</b>, not merely shifted — Bedrock's X axis runs
     *       opposite to Java's, so the lower-X corner comes from Java's
     *       {@code to.x}. Z is shifted by {@code -8} so the cube sits in the
     *       same depth slice as Bedrock's standard held-item rendering.</li>
     *   <li>Bedrock cube size: {@code java.to - java.from} per axis; sizes
     *       are clamped to a non-negative minimum so a degenerate cube
     *       doesn't crash the Bedrock renderer.</li>
     *   <li>Element rotation: {@code axis} → matching slot in Bedrock's
     *       three-component rotation, signed by {@link #ROT_X_SIGN} /
     *       {@link #ROT_Y_SIGN} / {@link #ROT_Z_SIGN} — {@code (-x, -y, +z)},
     *       the same convention Blockbench's Bedrock codec applies, and the
     *       mirror partner of the X flip above. Pivot maps like origin
     *       (X mirrored, Z shifted).</li>
     * </ul>
     *
     * <p>UV: this phase ignores per-face UV data. Each cube gets a single
     * {@code uv: [0, 0]} pair so the attachable texture is sampled from its
     * top-left corner; this matches the flat-quad fallback's behaviour for
     * items that lack elements and avoids the per-face UV format which has
     * inconsistent support across Bedrock client versions.</p>
     */
    /**
     * Phase 4 compatibility overload: produces cubes with the simple
     * {@code uv: [0, 0]} cube-level form, ignoring any per-face UV the
     * elements may carry. Used by call sites that don't yet route through
     * the texture-dimension-aware Phase 6 path.
     */
    public static List<Map<String, Object>> convertElementsToCubes(JavaModelGeometry geometry) {
        return convertElementsToCubes(geometry, 16, 16, null);
    }

    /**
     * Phase 6 entry point: produces cubes with per-face UV when the source
     * elements declare any face. UV coordinates from Java's 0..16 abstract
     * grid are scaled to Bedrock pixel space using {@code textureWidth} and
     * {@code textureHeight} (which must match the {@code texture_width} /
     * {@code texture_height} the geometry descriptor declares).
     *
     * <p>Empty input → empty output, so callers can plug the result straight
     * into the {@code bones[0].cubes} array of a Bedrock geometry JSON
     * without further guards.</p>
     *
     * <p>Coordinate mapping (item models, 0..16 range):</p>
     * <ul>
     *   <li>Bedrock cube origin (lower-X / lower-Y / lower-Z corner):
     *       {@code (8 - java.to.x, java.from.y, java.from.z - 8)}.
     *       X is <b>mirrored</b>, not merely shifted — Bedrock's X axis runs
     *       opposite to Java's, so the lower-X corner comes from Java's
     *       {@code to.x}. Z is shifted by {@code -8} so the cube sits in the
     *       same depth slice as Bedrock's standard held-item rendering.</li>
     *   <li>Bedrock cube size: {@code java.to - java.from} per axis;
     *       clamped non-negative.</li>
     *   <li>Element rotation: {@code axis} maps to matching Bedrock cube
     *       rotation slot, signed by {@link #ROT_X_SIGN} / {@link #ROT_Y_SIGN} /
     *       {@link #ROT_Z_SIGN} — {@code (-x, -y, +z)}, the same convention
     *       Blockbench's Bedrock codec applies, and the mirror partner of the
     *       X flip above.</li>
     *   <li>Per-face UV: {@code java.uv = [u1, v1, u2, v2]} (0..16 abstract)
     *       becomes Bedrock {@code uv: [u1·sx, v1·sy], uv_size: [(u2-u1)·sx, (v2-v1)·sy]}
     *       where {@code sx = textureWidth/16} and {@code sy = textureHeight/16}.
     *       Missing UV is derived from the element's from/to coordinates
     *       (Mojang's {@code FaceBakery.defaultFaceUV} "UV lock" rule).</li>
     *   <li>Java per-face texture rotation is forwarded verbatim as Bedrock
     *       {@code uv_rotation} (geometry format 1.21.0+), matching
     *       Rainbow's {@code GeometryMapper}.</li>
     * </ul>
     *
     * <p><b>Known limitations</b> (logged at FINE, not failure):</p>
     * <ul>
     *   <li>Faces referencing different textures via {@code texture}
     *       ({@code #layer0} vs {@code #blade}) are all rendered with the
     *       attachable's single default texture. Multi-texture support
     *       requires Bedrock {@code material_instances} which is a separate
     *       phase. Per-face texture variable references are read but
     *       currently ignored at conversion time.</li>
     * </ul>
     */
    public static List<Map<String, Object>> convertElementsToCubes(
        JavaModelGeometry geometry,
        int textureWidth,
        int textureHeight,
        Logger logger
    ) {
        return convertElementsToCubes(geometry, textureWidth, textureHeight, logger, true);
    }

    /**
     * As {@link #convertElementsToCubes(JavaModelGeometry, int, int, Logger)},
     * but with an explicit switch for per-face rotation output.
     *
     * @param emitUvRotation when {@code false}, Java's face {@code rotation}
     *        is not forwarded as {@code uv_rotation}. 180° falls back to the
     *        legacy point-mirrored UV rect and 90°/270° are dropped, which
     *        keeps every emitted geometry at {@code format_version 1.16.0}
     *        and the pack manifest at {@code min_engine_version 1.16.100}.
     *        Exposed as {@code customItems.attachableGeneration.faceUvRotation}
     *        so an operator whose clients cannot load a 1.21.0 pack can roll
     *        back without a rebuild.
     */
    public static List<Map<String, Object>> convertElementsToCubes(
        JavaModelGeometry geometry,
        int textureWidth,
        int textureHeight,
        Logger logger,
        boolean emitUvRotation
    ) {
        if (geometry == null || !geometry.hasElements()) {
            return List.of();
        }
        int tw = textureWidth > 0 ? textureWidth : 16;
        int th = textureHeight > 0 ? textureHeight : 16;
        List<Map<String, Object>> cubes = new ArrayList<>(geometry.elements().size());
        for (JavaModelGeometry.Element element : geometry.elements()) {
            if (element == null) continue;
            Map<String, Object> cube = convertSingleElement(element, tw, th, logger, emitUvRotation);
            // convertSingleElement returns null when the source element has no
            // usable faces — Mojang treats such elements as invisible, so
            // omitting the cube here is the only way to preserve that visual
            // semantic on the Bedrock side.
            if (cube != null) {
                cubes.add(cube);
            }
        }
        return cubes;
    }

    private static Map<String, Object> convertSingleElement(
        JavaModelGeometry.Element element,
        int textureWidth,
        int textureHeight,
        Logger logger,
        boolean emitUvRotation
    ) {
        float[] from = element.from();
        float[] to = element.to();

        // Java's renderer accepts {@code from > to} on any axis and treats it
        // as a swap, so we normalise to (min, max) per axis here. Without the
        // normalisation, an artist mistake (or a model that intentionally
        // flips an axis) would clamp to a zero-size cube and render as an
        // invisible sliver. {@code Math.min} / {@code Math.max} preserves
        // Bedrock's "origin is the smallest-XYZ corner" invariant.
        float fx = Math.min(from[0], to[0]);
        float fy = Math.min(from[1], to[1]);
        float fz = Math.min(from[2], to[2]);
        float tx = Math.max(from[0], to[0]);
        float ty = Math.max(from[1], to[1]);
        float tz = Math.max(from[2], to[2]);

        // java2bedrock mapping: Bedrock's attachable frame mirrors Java's X
        // axis, so the cube origin (Bedrock's smallest-XYZ corner) takes the
        // mirrored LARGEST Java X corner: origin.x = -to.x + 8. Y is shared;
        // Z shifts by -8 to centre depth on the bone pivot.
        float originX = -tx + 8f;
        float originY = fy;
        float originZ = fz - 8f;

        float sizeX = tx - fx;
        float sizeY = ty - fy;
        float sizeZ = tz - fz;

        Map<String, JavaModelGeometry.Face> faces = element.faces();

        // Phase 6 (Codex-flagged correctness): Mojang requires a non-empty
        // {@code faces} object for a cube to be visible — an element with
        // {@code faces:{}} or no parseable face entries renders as invisible
        // in Java. Mirroring this, we omit the cube entirely (return null)
        // rather than falling back to a cube-level UV that would render all
        // six faces. The caller (convertElementsToCubes) filters nulls out
        // of the final list.
        if (faces == null || faces.isEmpty()) {
            if (logger != null) {
                logger.warning("[BedrockGeometry] element from " + java.util.Arrays.toString(from)
                    + " to " + java.util.Arrays.toString(to)
                    + " has no faces declared — skipping cube so it remains "
                    + "invisible (matches Java semantics). If you intended this "
                    + "cube to be visible, add a `faces` object to the Java model.");
            }
            return null;
        }

        Map<String, Object> faceUvs = buildPerFaceUvMap(
            faces, from, to, textureWidth, textureHeight, logger, emitUvRotation);
        if (faceUvs.isEmpty()) {
            // Every face was zero-area or otherwise unrenderable. Omit the
            // cube rather than promoting to cube-level UV — promoting would
            // make the cube fully visible and contradict the operator's
            // intent of having no usable faces.
            if (logger != null) {
                logger.warning("[BedrockGeometry] element from " + java.util.Arrays.toString(from)
                    + " to " + java.util.Arrays.toString(to)
                    + " produced no renderable Bedrock faces — skipping cube. "
                    + "Common causes: every UV had zero width or height, or "
                    + "the texture mapping was otherwise malformed.");
            }
            return null;
        }

        Map<String, Object> cube = new LinkedHashMap<>();
        cube.put("origin", List.of(originX, originY, originZ));
        cube.put("size", List.of(sizeX, sizeY, sizeZ));
        cube.put("uv", faceUvs);

        JavaModelGeometry.ElementRotation rot = element.rotation();
        if (rot != null) {
            float[] rotation = convertElementRotation(rot);
            float[] pivot = convertElementPivot(rot.origin());
            cube.put("rotation", List.of(rotation[0], rotation[1], rotation[2]));
            cube.put("pivot", List.of(pivot[0], pivot[1], pivot[2]));
        }
        return cube;
    }

    /**
     * Builds the {@code cube.uv} object in Bedrock geometry 1.16.0 per-face
     * form. Each entry has shape {@code {"uv": [u, v], "uv_size": [w, h]}}
     * in pixel space relative to the geometry descriptor's
     * {@code texture_width} / {@code texture_height}.
     *
     * <p>Faces missing from the input map are intentionally omitted from the
     * output — Bedrock interprets a missing face as "do not render this
     * face", which exactly matches Java's behaviour when a face is omitted
     * from the {@code faces} object.</p>
     */
    private static Map<String, Object> buildPerFaceUvMap(
        Map<String, JavaModelGeometry.Face> faces,
        float[] elementFrom,
        float[] elementTo,
        int textureWidth,
        int textureHeight,
        Logger logger,
        boolean emitUvRotation
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        // Iterate in stable order so the rendered JSON (and downstream
        // patchVersion hash) is deterministic.
        for (String faceName : List.of("north", "south", "east", "west", "up", "down")) {
            JavaModelGeometry.Face face = faces.get(faceName);
            if (face == null) {
                continue;
            }
            Map<String, Object> bedrockFace = convertFace(
                face, faceName, elementFrom, elementTo, textureWidth, textureHeight,
                logger, emitUvRotation);
            if (bedrockFace != null) {
                out.put(faceName, bedrockFace);
            }
        }
        return out;
    }

    /**
     * Converts a single Java face into a Bedrock per-face UV entry.
     * Returns {@code null} when the result would be a zero-area UV region
     * (degenerate, would render as a single pixel artefact); callers omit
     * such faces so they simply don't render rather than producing visible
     * speckles.
     */
    private static Map<String, Object> convertFace(
        JavaModelGeometry.Face face,
        String faceName,
        float[] elementFrom,
        float[] elementTo,
        int textureWidth,
        int textureHeight,
        Logger logger,
        boolean emitUvRotation
    ) {
        // Java per-face texture rotation maps 1:1 onto Bedrock's per-face
        // {@code uv_rotation} field, which exists from geometry format
        // 1.21.0 onwards and accepts 90 / 180 / 270 as clockwise increments
        // (Microsoft "minecraft:geometry.v1.21.0" reference). GeyserMC's own
        // Rainbow mapper forwards the Java value unchanged
        // (GeometryMapper#mapCuboidModelElement), so we do the same rather
        // than approximating.
        //
        // Before this, 90°/270° were silently dropped and 180° was emulated
        // with a point-mirrored UV rect. Dropping affected a third of the
        // faces on Blockbench-authored weapon models, whose auto-UV assigns
        // 90°/270° to the east/west side faces.
        //
        // When the operator disables the feature, fall back to the legacy
        // approximation so no geometry needs format 1.21.0.
        int rotation = emitUvRotation ? face.rotation() : 0;
        boolean legacy180 = !emitUvRotation && face.rotation() == 180;

        // Default UV when not specified: Mojang derives it from the element's
        // from/to coordinates per face (FaceBakery.defaultFaceUV — the "UV
        // lock" behaviour; Rainbow invokes the same method via mixin). The
        // previous whole-texture [0,0,16,16] fallback only coincided with
        // Mojang's default for full 16x16x16 cubes.
        float u1, v1, u2, v2;
        if (face.hasUv()) {
            float[] uv = face.uv();
            u1 = uv[0];
            v1 = uv[1];
            u2 = uv[2];
            v2 = uv[3];
        } else {
            float[] def = defaultFaceUv(faceName, elementFrom, elementTo);
            u1 = def[0]; v1 = def[1]; u2 = def[2]; v2 = def[3];
        }

        // Scale Java's 0..16 abstract space to Bedrock pixel space.
        // For a 16x16 texture, scale = 1.0 (identity). For 32x32, scale = 2.0.
        float scaleX = textureWidth / 16f;
        float scaleY = textureHeight / 16f;

        // Up/down faces are point-mirrored (uv anchored at the (u2,v2) corner
        // with negative sizes): the X-mirrored geometry flips how Bedrock
        // orients top/bottom face UVs relative to Java. Both java2bedrock and
        // Rainbow do this, and it is independent of the Java face rotation —
        // that now rides along in {@code uv_rotation} instead of being folded
        // into the rect.
        boolean verticalFace = "up".equals(faceName) || "down".equals(faceName);
        boolean pointMirror = legacy180 ^ verticalFace;

        float bedrockU;
        float bedrockV;
        float bedrockUSize;
        float bedrockVSize;
        if (pointMirror) {
            // Point-symmetric remap: the face's top-left corner samples from
            // what was the texture region's bottom-right corner, and uv_size
            // walks "backwards" via negative values. Bedrock honours negative
            // uv_size as a flip in that axis.
            bedrockU = u2 * scaleX;
            bedrockV = v2 * scaleY;
            bedrockUSize = -(u2 - u1) * scaleX;
            bedrockVSize = -(v2 - v1) * scaleY;
        } else {
            bedrockU = u1 * scaleX;
            bedrockV = v1 * scaleY;
            // uv_size may be negative when u1 > u2 or v1 > v2 — Bedrock
            // interprets that as a flipped texture on that face, matching
            // Java's u1>u2 / v1>v2 flipping semantics. We deliberately do
            // NOT clamp these to positive.
            bedrockUSize = (u2 - u1) * scaleX;
            bedrockVSize = (v2 - v1) * scaleY;
        }

        // Phase 6 (Codex-flagged): zero area on EITHER axis means the face
        // can't physically render — a UV strip of {@code N x 0} or {@code 0 x N}
        // is still degenerate. Catches both 0° and 180° degenerate cases
        // because 180°'s negative form preserves the absolute value.
        if (bedrockUSize == 0f || bedrockVSize == 0f) {
            return null;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uv", List.of(bedrockU, bedrockV));
        out.put("uv_size", List.of(bedrockUSize, bedrockVSize));
        // Bedrock omits the field for the unrotated case; the schema only
        // enumerates 90 / 180 / 270.
        if (rotation != 0) {
            out.put("uv_rotation", rotation);
        }
        return out;
    }

    /**
     * True when any converted cube carries a per-face {@code uv_rotation}.
     * Callers use this to select the geometry {@code format_version}: the
     * field only exists from 1.21.0, so models that don't need it keep
     * emitting 1.16.0 and stay loadable on older Bedrock clients.
     */
    public static boolean requiresUvRotationFormat(List<Map<String, Object>> cubes) {
        if (cubes == null) {
            return false;
        }
        for (Map<String, Object> cube : cubes) {
            if (!(cube.get("uv") instanceof Map<?, ?> faces)) {
                continue;
            }
            for (Object face : faces.values()) {
                if (face instanceof Map<?, ?> f && f.containsKey("uv_rotation")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Axis-aligned bounding box of the converted cubes, as
     * {@code [minX, minY, minZ, maxX, maxY, maxZ]} in Bedrock model units.
     * Returns {@code null} when no cube carries usable origin/size data.
     *
     * <p>Used to size the geometry descriptor's {@code visible_bounds_*}
     * so oversized meshes are not frustum-culled — see
     * {@code BedrockAttachableWriter#visibleBounds}.</p>
     */
    public static float[] computeBounds(List<Map<String, Object>> cubes) {
        if (cubes == null || cubes.isEmpty()) {
            return null;
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        boolean any = false;
        for (Map<String, Object> cube : cubes) {
            float[] origin = readFloat3(cube.get("origin"));
            float[] size = readFloat3(cube.get("size"));
            if (origin == null || size == null) {
                continue;
            }
            any = true;
            minX = Math.min(minX, origin[0]);
            minY = Math.min(minY, origin[1]);
            minZ = Math.min(minZ, origin[2]);
            maxX = Math.max(maxX, origin[0] + size[0]);
            maxY = Math.max(maxY, origin[1] + size[1]);
            maxZ = Math.max(maxZ, origin[2] + size[2]);
        }
        return any ? new float[]{minX, minY, minZ, maxX, maxY, maxZ} : null;
    }

    /**
     * Mojang's position-derived default UV for a face that declares no
     * {@code uv} array (FaceBakery.defaultFaceUV). The element's raw
     * {@code from}/{@code to} coordinates (unnormalised, matching Mojang's
     * reading order) project onto the texture plane of each face:
     * <pre>
     *   down:  [from.x, 16-to.z,  to.x,      16-from.z]
     *   up:    [from.x, from.z,   to.x,      to.z]
     *   north: [16-to.x, 16-to.y, 16-from.x, 16-from.y]
     *   south: [from.x, 16-to.y,  to.x,      16-from.y]
     *   west:  [from.z, 16-to.y,  to.z,      16-from.y]
     *   east:  [16-to.z, 16-to.y, 16-from.z, 16-from.y]
     * </pre>
     */
    private static float[] defaultFaceUv(String faceName, float[] from, float[] to) {
        if (from == null || to == null || from.length < 3 || to.length < 3) {
            return new float[]{0f, 0f, 16f, 16f};
        }
        return switch (faceName) {
            case "down"  -> new float[]{from[0], 16f - to[2], to[0], 16f - from[2]};
            case "up"    -> new float[]{from[0], from[2], to[0], to[2]};
            case "north" -> new float[]{16f - to[0], 16f - to[1], 16f - from[0], 16f - from[1]};
            case "south" -> new float[]{from[0], 16f - to[1], to[0], 16f - from[1]};
            case "west"  -> new float[]{from[2], 16f - to[1], to[2], 16f - from[1]};
            case "east"  -> new float[]{16f - to[2], 16f - to[1], 16f - from[2], 16f - from[1]};
            default      -> new float[]{0f, 0f, 16f, 16f};
        };
    }

    private static float[] convertElementRotation(JavaModelGeometry.ElementRotation rotation) {
        if (rotation.euler() != null) {
            float[] e = rotation.euler();
            // Per-axis sign flipping is exact for ONE non-zero angle and wrong
            // for two or more: it silently assumes Java and Bedrock compose a
            // [rx, ry, rz] triple in the same order, and they need not. The
            // same assumption is what misdirected first-person translations
            // until the change of basis replaced it (see
            // convertTranslationInRootFrame).
            //
            // The error is not subtle. A Blockbench free rotation of
            // {x:-180, y:-89, z:180} — a book cover, flipped and turned edge-on
            // — composes to Ry(-91) under one order and Ry(+91) under another:
            // the cover ends up on the wrong side of the pages, while the page
            // block beside it, authored with a plain single-axis rotation,
            // stays put. "Only the cover moved" is the signature.
            //
            // Folding the triple into its net rotation first removes the
            // ambiguity for the case that actually occurs in the wild: authors
            // reach for free rotation to express a flip (±180 on two axes) plus
            // a turn, and those reduce to a single axis, which every
            // composition order agrees on.
            //
            // Multi-axis triples then fall through to the per-axis flip, which
            // is exact — see convertRotation, where the order question is
            // settled. Folding first is kept anyway: it emits the tidy
            // single-axis triple an author would recognise instead of an
            // equivalent-but-opaque one (広辞苑's cover comes out (0, 91, 0)
            // rather than (180, 89, 180)), and it is the branch every reducible
            // cube in the pack already takes.
            float[] singleAxis = reduceToSingleAxis(e);
            if (singleAxis != null) {
                return singleAxis;
            }
            return convertRotation(e);
        }
        return convertSingleAxisRotation(
            rotation.axis() != null ? rotation.axis() : "y", rotation.angle());
    }

    /**
     * Folds a Java three-axis element rotation into the equivalent single-axis
     * Bedrock rotation, or returns {@code null} when no single axis reproduces
     * it.
     *
     * <p>This is a <b>canonicalisation, not a correctness device</b>. The
     * per-axis flip in {@link #ROT_X_SIGN} is exact for three-axis triples as
     * well, so folding changes the representation and not the orientation:
     * 広辞苑's cover is emitted as {@code (0, 91, 0)} instead of the equivalent
     * {@code (180, 89, 180)}. It reads as the shape the author drew, and it
     * survives a hand edit of one component, which the tangled form does not.
     * (It was originally added believing the flip was only valid on one axis;
     * that turned out to be false — see {@link #ROT_X_SIGN} — but the tidier
     * output is worth keeping on its own.)</p>
     *
     * <p>The triple is composed into a rotation matrix and compared against a
     * rotation about each axis in turn, so the answer is a property of the
     * matrix rather than of how it was built.</p>
     *
     * <p>Angles are searched at 1° resolution and then refined, which covers
     * Blockbench output (it writes two decimals but authors work in whole
     * degrees) without a full matrix decomposition.</p>
     *
     * <p>The target matrix is built {@code Rx·Ry·Rz}, which is vanilla's order
     * — the type Mojang added for multi-axis element rotation in 1.21.11
     * (25w46a) is named {@code CuboidRotation.EulerXYZRotation}. That fixes
     * which of the two candidate answers is the model's real shape: 広辞苑's
     * {@code {-180, -89, 180}} covers fold to {@code Ry(-91)} under this order
     * and to {@code Ry(+91)} under the ones that put Y at an end, and only
     * {@code -91} lands the cover flush against the page block.</p>
     */
    private static float[] reduceToSingleAxis(float[] euler) {
        double[][] target = mul(axisMatrix(0, euler[0]),
            mul(axisMatrix(1, euler[1]), axisMatrix(2, euler[2])));
        for (int axis = 0; axis < 3; axis++) {
            Double angle = matchAxisAngle(target, axis);
            if (angle != null) {
                String name = switch (axis) {
                    case 0 -> "x";
                    case 1 -> "y";
                    default -> "z";
                };
                return convertSingleAxisRotation(name, (float) (double) angle);
            }
        }
        return null;
    }

    /**
     * Returns the angle about {@code axis} whose rotation matrix equals
     * {@code target}, or {@code null} if none does within tolerance.
     */
    private static Double matchAxisAngle(double[][] target, int axis) {
        double best = Double.MAX_VALUE;
        double bestAngle = 0;
        for (int deg = -180; deg <= 180; deg++) {
            double err = matrixDistance(target, axisMatrix(axis, deg));
            if (err < best) {
                best = err;
                bestAngle = deg;
            }
        }
        // Refine to a hundredth of a degree around the coarse winner so
        // Blockbench's two-decimal output is reproduced rather than snapped.
        for (int step = -100; step <= 100; step++) {
            double candidate = bestAngle + step / 100.0;
            double err = matrixDistance(target, axisMatrix(axis, candidate));
            if (err < best) {
                best = err;
                bestAngle = candidate;
            }
        }
        return best < 1e-6 ? bestAngle : null;
    }

    /** Sum of squared element differences between two 3x3 matrices. */
    private static double matrixDistance(double[][] a, double[][] b) {
        double sum = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double d = a[i][j] - b[i][j];
                sum += d * d;
            }
        }
        return sum;
    }

    private static float[] convertSingleAxisRotation(String rawAxis, float angle) {
        String axis = rawAxis.toLowerCase(Locale.ROOT);
        // Same sign convention as every other rotation this converter emits —
        // see ROT_*_SIGN. Cube-local rotations and animation-bone rotations do
        // NOT use different conventions on Bedrock; believing they did is what
        // put the Y sign wrong here.
        return switch (axis) {
            case "x" -> new float[]{ROT_X_SIGN * angle, 0f, 0f};
            case "z" -> new float[]{0f, 0f, ROT_Z_SIGN * angle};
            default  -> new float[]{0f, ROT_Y_SIGN * angle, 0f}; // "y" or unknown axis falls back to Y
        };
    }

    /**
     * Default bone pivot used when a model produces no renderable cubes.
     * Mirrors the historical java2bedrock fixed pivot so flat / degenerate
     * models keep their previous behaviour.
     */
    private static final float[] DEFAULT_PIVOT = {0f, 8f, 0f};

    /**
     * Computes the geometric centre of the axis-aligned bounding box that
     * encloses every converted cube.
     *
     * <p>Retained for tests / tooling. Held-item attachable bones now pivot at
     * Java model-space centre {@code [0, 8, 0]} on both Rainbow and legacy
     * paths — AABB centre put Valhalla flat meshes behind the first-person
     * camera under large base rotations.</p>
     *
     * @param cubes converted Bedrock cubes (each with {@code origin} and
     *              {@code size} length-3 numeric lists); {@code null} / empty
     *              yields {@link #DEFAULT_PIVOT}
     * @return the bounds-centre pivot {@code [x, y, z]}
     */
    public static float[] computeBoundsCentrePivot(List<Map<String, Object>> cubes) {
        if (cubes == null || cubes.isEmpty()) {
            return DEFAULT_PIVOT.clone();
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        boolean any = false;
        for (Map<String, Object> cube : cubes) {
            float[] origin = readFloat3(cube.get("origin"));
            float[] size = readFloat3(cube.get("size"));
            if (origin == null || size == null) {
                continue;
            }
            any = true;
            minX = Math.min(minX, origin[0]);
            minY = Math.min(minY, origin[1]);
            minZ = Math.min(minZ, origin[2]);
            maxX = Math.max(maxX, origin[0] + size[0]);
            maxY = Math.max(maxY, origin[1] + size[1]);
            maxZ = Math.max(maxZ, origin[2] + size[2]);
        }
        if (!any) {
            return DEFAULT_PIVOT.clone();
        }
        return new float[]{
            minX + (maxX - minX) / 2f,
            minY + (maxY - minY) / 2f,
            minZ + (maxZ - minZ) / 2f
        };
    }

    /**
     * Reads a length-3 numeric list (a cube {@code origin} / {@code size})
     * into a float array. Returns {@code null} when the value is missing or
     * not a 3-element numeric list so callers can skip malformed cubes.
     */
    private static float[] readFloat3(Object value) {
        if (!(value instanceof List<?> list) || list.size() < 3) {
            return null;
        }
        if (!(list.get(0) instanceof Number x)
            || !(list.get(1) instanceof Number y)
            || !(list.get(2) instanceof Number z)) {
            return null;
        }
        return new float[]{x.floatValue(), y.floatValue(), z.floatValue()};
    }

    private static float[] convertElementPivot(float[] javaOrigin) {
        if (javaOrigin == null || javaOrigin.length < 3) {
            return new float[]{0f, 0f, 0f};
        }
        // Pivot X mirrors like cube origins (java2bedrock: -origin.x + 8).
        return new float[]{
            -javaOrigin[0] + 8f,
            javaOrigin[1],
            javaOrigin[2] - 8f
        };
    }
}
