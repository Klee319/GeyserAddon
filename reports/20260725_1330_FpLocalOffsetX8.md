# Task

根元の画面右寄せのため、FP 専用 `localOffset` を `geyserextra_x` に加算する経路を追加し、`[8,0,0]` で試す。

# Files Changed

- `GeyserExtraConfig.BasePose` — `localOffset` フィールド
- `BedrockAttachableWriter.java` — FP 時 x 骨 position に加算
- `BedrockAttachableWriterTest.java`
- `README.md`
- `config.json` — `localOffset: [8,0,0]`、position `[25,13,4]` 維持
- Paper/Extension JAR（bak `20260725_132938`）

# Details

- MS: root `[25,13,4]` scale 2.8 / x.pos `[8,0,0]`
- manifest `1.0.31040`
