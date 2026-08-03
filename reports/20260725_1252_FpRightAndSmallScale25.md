# Task

根元を右へ、小物 scale を大きく、大斧 1.5 は維持。

# Files Changed

- `BedrockAttachableWriter.java` — oversize root scale を固定目標 1.5 へ lerp（base 上昇でも大斧サイズ維持）
- `BedrockAttachableWriterTest.java` — 期待値更新
- `README.md`
- `.../geyserextra/config.json` — position `[20,18,-2]`, scale `2.5`（bak: `bak_scale25_*`）
- Paper/Extension JAR 配置（bak: `20260725_125150`）

# Details

- MS FP: pos `[20,18,-2]` scale **2.5** / rot `[90,60,-28]`
- 大斧: scale **1.5** 維持 / pos `[13,15.2,-10.4]`
- manifest `1.0.31025`
