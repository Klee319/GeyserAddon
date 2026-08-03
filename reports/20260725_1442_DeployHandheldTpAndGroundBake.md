# Task

バニラ handheld CMD 剣の三人称修正＋パス1 A（ground 優先アイコン bake）を TrinityForge へデプロイ。

# Files Changed

（コード変更は前回レポート `20260725_1439_VanillaHandheldTpAndGroundIconBake.md`）
本作業は配置のみ。

# Deploy

- Build: `:paper:build -x test` → `geyserExtra-1.0.0-SNAPSHOT.jar` (634759 bytes, 14:40:42)
- Backup: `plugins/geyserExtra-1.0.0-SNAPSHOT.jar.bak_20260725_144115`
- Copy → RCON `stop` → `start.bat`
- Auto pack 再生成: `geyserextra_auto.zip` mtime 14:41:57 → pending promote 完了
- Extension JAR は今回未変更（paper のみ）

# Verification

- サーバ `Done` 確認
- `custom_netherite_sword_112` thirdperson: rot Y=90 / Z=55、trans `(0,4,0.5)`、scale `0.85`、root `(90,0,0)/(0,13,-3)` → **バニラ handheld 注入どおり**
- handheld 署名（TP y=90）アニメ: 8 件（skillicon 系 + netherite_sword_112 等）
- Valhalla 系（例: `custom_copper_sword_4`）は従来どおり独自 display（回帰なし）
- `_gui.png` bake 件数: 108

# README

前回同期済み。追加更新なし。
