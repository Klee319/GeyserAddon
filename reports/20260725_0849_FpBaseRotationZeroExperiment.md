# Task

一人称で大斧が右端クリップする主因が「位置」か「角度」かを切り分けるため、`firstPersonBasePose.rotation` を一時的に `[0,0,0]` にする可逆 config 実験を配置＋再起動で実施した。

# Files Changed

- `D:\game\minecraft\PaperServer\TrinityForge\plugins\Geyser-Spigot\extensions\geyserextra\config.json`（bak: `bak_rot0_20260725_084846`）
- リポジトリの Java コードは未変更

# Details

## 実験条件

- rotation: `[90,60,-40]` → `[0,0,0]`
- position / scale / `debugZeroFirstPersonTranslation` は据え置き
- Paper 再起動で `geyserextra_auto.zip` 再生成

## パック検証

- `custom_golden_sword_32`: root.rot `[0,0,0]` pos `[-3,10,-10.4]`
- `custom_golden_sword_20`: root.rot `[0,0,0]` pos `[4,10,-2]`

## 判定ガイド（ユーザー確認待ち）

| 結果 | 読み |
|------|------|
| 向き／右端クリップが大きく変わる | 角度が主因 → base rot を段階調整 |
| ZIP は 0 なのに画面不変 | クライアント古いパック |
| 向きは変わるが tip のみ残る | 角度＋位置の併用 |

戻し値: `rotation: [90, 60, -40]`

## 結果（ユーザー）

`[0,0,0]` は的外れ（頭の右上に刃が少し見える程度）。**base 回転は効くがゼロは不採用**。

## 復元（2026-07-25 08:54）

- config を `[90, 60, -40]` に戻し再起動
- pack 検証: axe/MS とも root.rot `[90,60,-40]`（axe pos は oversize bias 維持）
