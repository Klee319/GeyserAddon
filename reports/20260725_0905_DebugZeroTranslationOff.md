# Task

MS 浮きの主因切り分け: 単一変数で `debugZeroFirstPersonTranslation` を `false` にし、Java FP translation を復帰させて因果を確認する（案 A）。

# Files Changed

- `...\geyserextra\config.json`（bak: `bak_zerotrans_off_20260725_090458`）
- リポジトリ Java コードは未変更

# Details

## 操作

- `debugZeroFirstPersonTranslation`: `true` → `false`
- base pose / oversize bias / scale は据え置き
- 再起動で pack 再生成

## パック検証

| item | x.position（復帰後） |
|------|----------------------|
| MS `sword_20` | `[-1.13, 3.2, 2.12]` |
| 大斧 `sword_32` | `[0.0, 4.0, 9.0]` |

## 判定ガイド（ユーザー確認待ち）

| 結果 | 読み |
|------|------|
| MS の浮きが消える／減る | translation 無効化が浮きの主因 → 次は scale 条件付き復帰（案 B） |
| 大斧がまた右端／画面外 | 大斧は translation を抑えたい（案 B の根拠） |
| 両方悪化／変化なし | 別要因（scale 2.0 等）を疑う |

## 結果（ユーザー）

**両方悪化**: MS は上へ／大斧は消失。  
→ Java FP translation の全面復帰は不採用。  
→ MS 浮きの主因は「translation ゼロ」単独ではない（scale 2.0 × base pose 側が有力）。

## 復元（2026-07-25 09:08）

`debugZeroFirstPersonTranslation: true` に戻し再起動。pack: 両武器 `x.pos=[0,0,0]`。
