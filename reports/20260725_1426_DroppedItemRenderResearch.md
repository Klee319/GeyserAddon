# Task

ドロップ時モデル／アニメ適用の事前調査（方針 3 → その後 1 → 2）。

# Findings（上限）

## Bedrock / Geyser の制約

- **attachable 3D は装備スロット（手・頭等）専用**。inventory / item frame / **dropped item は 2D スプライト前提**（GeyserMC 公式見解と一致）。
- したがって「手持ちと同じ 3D attachable を地面にそのまま載せる」は**ネイティブには不可**。
- 擬似 3D は **別エンティティ代理**などの重い経路が必要（成功保証なし）。

## 本リポジトリ現状

| 項目 | 状態 |
|------|------|
| ドロップ専用コード | なし（見た目＝カスタムアイテムの `item_texture` 2D） |
| `display.gui` | `GuiIconTransformer` で bake → INV＝ドロップ共通アイコン |
| `display.ground` | パースのみ・**完全未使用** |
| 手持ち 3D | `BedrockAttachableWriter`（完了領域） |
| ItemEntity 追跡 | 未実装（近いのは OffhandSwap の `PlayerDropItemEvent` のみ） |

# 推奨ロードマップ

## 1. 2D 改善（次に実施候補）

- `display.ground`（なければ `gui`）を使ったドロップ向けスプライト改善
- 既存 `GuiIconTransformer` / `AutoBedrockPackBuilder` 拡張が自然
- **注意**: Geyser の icon キーは通常1つ → INV とドロップで見た目を分ける場合は制約確認が必要。まず「gui bake 品質向上／ground 焼き込みで共通アイコン改善」が安全

## 2. 擬似 3D（その後・実験）

- `ItemSpawnEvent` 等でカスタムドロップを検知
- Bedrock 向けに `ItemDisplay` / ArmorStand 等のビジュアル代理＋拾い同期
- 元 Item の可視制御・デスポーン同期が難所
- Geyser の display entity 対応状況を実装前に再確認

# Decision needed

ユーザー承認後に 1 の具体設計（INV 共有か分離か）へ進む。
