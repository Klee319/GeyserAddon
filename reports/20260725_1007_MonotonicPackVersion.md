# Task

Bedrock が姿勢変更を拾わない原因（同一 UUID で manifest patch が下がると更新無視）を修正し、単調増加 patch ＋ UUID バンプ＋ pending 昇格時の sidecar 移動を入れた。

# Files Changed

- `paper/.../AutoBedrockPackBuilder.java` — monotonic patch / sidecar / BOM 耐性 / active 高水位
- `extension/.../GeyserExtraExtension.java` — promote 時に `.pack_version` もリネーム
- `paper/.../AutoBedrockPackBuilderPatchVersionTest.java`
- `README.md`

# Details

## 原因

- 内容ハッシュ由来の patch が `26001 → 22454` と低下
- MS 公式: 同一 UUID で version が同じか低いと無視
- さらに Paper は `pending.zip` に書き、Extension 起動時に昇格。後からできた pending が未昇格のまま残るレースあり

## 対策

1. `max(contentHash, lastPatch+1)`（内容不変時は last 維持）
2. sidecar `*.pack_version` に lastPatch + contentHash
3. pack UUID を一度バンプ（キャッシュ強制破棄）
4. promote で pending の `.pack_version` も active 名へ

## 検証（ライブ）

- active `geyserextra_auto.zip`: uuid `…5e70`, version **1.0.30163**
- FP pos は `[7,5,-2]` / 斧 `[0,2.2,-10.4]` のまま（今回の目的は配信修正）
