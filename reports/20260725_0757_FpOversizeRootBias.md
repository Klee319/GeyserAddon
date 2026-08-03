# Task

モーニングスター位置は維持したまま、一人称で画面右端にクリップしていた大斧クラス武器を、Java `display.scale` 超過分に比例する汎用 FP root 補正で左・前へ寄せ、JAR 配置＋再起動で auto pack を再生成した。

# Files Changed

- `paper/src/main/java/com/geyserextra/paper/pack/BedrockAttachableWriter.java`
- `paper/src/test/java/com/geyserextra/paper/pack/BedrockAttachableWriterTest.java`
- `README.md`
- `log/BugAndFix/20260724_1709_ItemHoldPoseWrong.md`

# Details

## 方針（承認済み: 選択肢 2）

1. アイテム ID 分岐なし（scale 閾値のみ）
2. MS（scale 0.68）は base pose 据え置き
3. JAR 配置＋サーバ再起動で pack 再生成

## 実装

- `excess = max(0, max(displayScale) - 1.0)`
- FP root: X − `excess*10`, Z − `excess*12`（百分の一丸め）
- 大斧 1.7 → `[-3, 10, -10.4]` / MS 0.68 → `[4, 10, -2]`

## 配置・検証

- Paper / Extension shadowJar を TrinityForge へ差し替え（bak `20260725_075452`）
- サーバ起動後 `geyserextra_auto.zip` 更新（07:56:36）
- pack 実測:
  - `custom_golden_sword_32`: root `[-3,10,-10.4]` scale 2.0 / x scale 1.7
  - `custom_golden_sword_20`: root `[4,10,-2]` scale 2.0 / x scale 0.68

# README

一人称 oversize 補正の自動ルールを追記済み。
