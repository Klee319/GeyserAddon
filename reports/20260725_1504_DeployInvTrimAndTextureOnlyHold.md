# Task

INV trim/fit + テクスチャ専用剣のバニラ FP 分離を TrinityForge へデプロイ。

# Deploy

- Build: `geyserExtra-1.0.0-SNAPSHOT.jar` (637432 bytes, 15:02:53)
- Backup: `plugins/geyserExtra-1.0.0-SNAPSHOT.jar.bak_20260725_150349`
- RCON stop → `start.bat`
- Pack: `geyserextra_auto.zip` mtime 15:04:26

# Verification

`custom_netherite_sword_112` firstperson:
- root: default `[90,60,-40]` / `[7,5,-2]` / `2.0`（Valhalla `[25,7,10]/3.2` なし）
- x translation 維持: `(-1.13, 3.2, -1.13)`（zero-translation 非適用）
- `_gui.png` count: 92

# Next

Bedrock でリソースパック再取得後、infinity_sword / 深罪の終幕（IRON_CHAIN CMD 68）の INV・持ち方を実機確認。
