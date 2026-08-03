# Task

`20260725_1534_FpOffhandInvLoreFixes` を TrinityForge へデプロイ。

# Deploy

- JAR: `geyserExtra-1.0.0-SNAPSHOT.jar` (638859 bytes, 15:38:30)
- Backup: `...bak_20260725_153858`
- RCON stop → `start.bat`
- Pack mtime: 15:39:33

# Verification

| 項目 | 結果 |
|------|------|
| flat FP `[4,10,4]/1.5` (`custom_netherite_sword_112`) | OK（旧 `[7,5,-2]` / Valhalla pose なし） |
| 3D TP off X = main X (`custom_golden_sword_32` → `15.5`) | OK |
| `_gui.png` bake | 0 件（生 PNG 108） |
| `[GE]` strip | コード同梱（実機: Creative→Java 受け渡しで確認） |
