# Task

モーニングスター（scale 2.0 時が最適）と大斧（scale 1.5＋少し下）を両立する汎用変換規則を実装し、JAR 配置＋再起動で auto pack を再生成した。

# Files Changed

- `paper/src/main/java/com/geyserextra/paper/pack/BedrockAttachableWriter.java`
- `paper/src/test/java/com/geyserextra/paper/pack/BedrockAttachableWriterTest.java`
- `core/src/main/java/com/geyserextra/core/config/GeyserExtraConfig.java`
- `README.md`
- live `config.json`（scale `1.5 → 2.0`、`debugZero=true`）

# Details

## 規則（アイテム ID なし）

`s = max(display.scale)`, `excess = max(0, s - 1.0)`

1. FP translation: 既定ゼロ（`debugZeroFirstPersonTranslation` 既定 `true`）
2. root.scale: `baseScale - 0.5 * clamp(excess / 0.7, 0, 1)`（base=2.0 → MS 2.0 / 大斧 1.5）
3. root.pos: `excess * (10 left, 4 down, 12 forward)`

## パック検証（09:27:58）

| item | root.scale | root.pos |
|------|------------|----------|
| MS `sword_20` | **2.0** | `[4, 10, -2]` |
| 大斧 `sword_32` | **1.5** | `[-3, 7.2, -10.4]` |

# README

display.scale 適応規則と debugZero 既定 true を同期済み。
