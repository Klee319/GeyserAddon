# Java版リソースパック → 統合版 変換ガイド

GeyserExtra を使用した場合の、Java版リソースパックを統合版(Bedrock)プレイヤー向けに対応させる手順です。

---

## 概要

### GeyserExtra が自動化する部分

| 項目 | 自動化 | 説明 |
|------|:------:|------|
| CustomModelData 検出 | ✅ | サーバー上のアイテムをスキャン |
| アイテム名取得 | ✅ | 表示名を自動取得 |
| ベースアイテム判定 | ✅ | minecraft:diamond_sword 等 |
| Geyser への登録 | ✅ | GeyserDefineCustomItemsEvent |
| レシピブック対応 | ✅ | creative_category 自動設定 |
| custom_items.json 生成 | ✅ | 共有フォルダに自動出力 |

### 手動で必要な作業

| 項目 | 必要な作業 |
|------|-----------|
| テクスチャ画像 | Java の PNG を Bedrock 形式で配置 |
| 3D モデル | Java の JSON を Bedrock 形式に変換 |
| アニメーション | Bedrock 形式で再作成 |

---

## 必要なファイル構成

### Java版リソースパック (入力)

```
java_resourcepack/
├── pack.mcmeta
└── assets/
    └── minecraft/
        ├── models/
        │   └── item/
        │       └── diamond_sword.json      # CustomModelData オーバーライド
        └── textures/
            └── item/
                ├── diamond_sword.png       # バニラ
                └── custom/
                    └── my_sword.png        # カスタムテクスチャ
```

### Bedrock版リソースパック (出力)

```
bedrock_resourcepack/
├── manifest.json
├── textures/
│   ├── item_texture.json                   # テクスチャ定義 (必須)
│   └── items/
│       └── my_sword.png                    # カスタムテクスチャ
└── items/
    └── my_sword.json                       # アイテム定義 (3Dモデル時のみ)
```

---

## 手順

### Step 1: GeyserExtra のセットアップ

1. **Paper Plugin** と **Geyser Extension** をインストール
2. サーバーを起動し、カスタムアイテムを持ったプレイヤーがログイン
3. `plugins/Geyser-Spigot/extensions/geyserextra/shared/custom_items.json` が生成される

```json
// 自動生成される custom_items.json の例
{
  "items": {
    "minecraft:diamond_sword": [
      {
        "name": "custom_diamond_sword_1",
        "custom_model_data": 1,
        "display_name": "Custom Sword",
        "icon": "custom_diamond_sword_1",
        "creative_category": 3
      }
    ]
  }
}
```

### Step 2: Bedrock リソースパックの作成

#### 2.1 manifest.json の作成

```json
{
  "format_version": 2,
  "header": {
    "name": "My Custom Items Pack",
    "description": "Custom items for Bedrock",
    "uuid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
    "version": [1, 0, 0],
    "min_engine_version": [1, 21, 0]
  },
  "modules": [
    {
      "type": "resources",
      "uuid": "yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy",
      "version": [1, 0, 0]
    }
  ]
}
```

> **Note**: UUID は https://www.uuidgenerator.net/ で生成

#### 2.2 テクスチャの配置

Java版のテクスチャを Bedrock 形式で配置:

```
bedrock_resourcepack/
└── textures/
    └── items/
        └── custom_diamond_sword_1.png   # ← Java の my_sword.png をリネーム
```

**ファイル名のルール:**
- `custom_items.json` の `icon` フィールドと一致させる
- 例: `"icon": "custom_diamond_sword_1"` → `custom_diamond_sword_1.png`

#### 2.3 item_texture.json の作成

```json
{
  "resource_pack_name": "My Custom Items Pack",
  "texture_name": "atlas.items",
  "texture_data": {
    "custom_diamond_sword_1": {
      "textures": "textures/items/custom_diamond_sword_1"
    }
  }
}
```

**複数アイテムの場合:**

```json
{
  "resource_pack_name": "My Custom Items Pack",
  "texture_name": "atlas.items",
  "texture_data": {
    "custom_diamond_sword_1": {
      "textures": "textures/items/custom_diamond_sword_1"
    },
    "custom_diamond_sword_2": {
      "textures": "textures/items/custom_diamond_sword_2"
    },
    "custom_pickaxe_1": {
      "textures": "textures/items/custom_pickaxe_1"
    }
  }
}
```

### Step 3: リソースパックの配置

作成したリソースパックを Geyser に登録:

```
plugins/Geyser-Spigot/packs/
└── my_custom_items.mcpack    # ZIP にして拡張子を .mcpack に変更
```

### Step 4: Geyser 設定の確認

`plugins/Geyser-Spigot/config.yml`:

```yaml
# カスタムコンテンツを有効化
add-non-bedrock-items: true

# リソースパックを強制
force-resource-packs: true
```

### Step 5: 動作確認

1. サーバーを再起動
2. Bedrock クライアントで接続
3. リソースパックのダウンロードを承認
4. カスタムアイテムのテクスチャが表示されることを確認

---

## 3D モデルの変換 (オプション)

Java版の 3D モデルを Bedrock に変換する場合:

### Java版モデル (models/item/diamond_sword.json)

```json
{
  "parent": "item/handheld",
  "textures": {
    "layer0": "item/custom/my_sword"
  },
  "overrides": [
    {"predicate": {"custom_model_data": 1}, "model": "item/custom/my_sword_3d"}
  ]
}
```

### 変換ツール

| ツール | 説明 |
|--------|------|
| [Blockbench](https://blockbench.net/) | Java ↔ Bedrock 相互変換対応 |
| [bridge.](https://bridge-core.app/) | Bedrock アドオン開発 IDE |

### Blockbench での変換手順

1. Java モデル (.json) を開く
2. `File` → `Convert Project` → `Bedrock Entity`
3. Bedrock 形式でエクスポート
4. `items/` フォルダに配置

---

## トラブルシューティング

### テクスチャが表示されない

1. **ファイル名の確認**: `icon` フィールドと PNG ファイル名が一致しているか
2. **item_texture.json の確認**: テクスチャパスが正しいか
3. **リソースパック形式**: .mcpack (ZIP) 形式になっているか
4. **Geyser 設定**: `add-non-bedrock-items: true` になっているか

### レシピブックに表示されない

1. **creative_category の確認**: 1-5 の値が設定されているか
2. **Geyser バージョン**: 2.2.0 以降を使用しているか

### アイテムが登録されない

1. **custom_items.json の確認**: 共有フォルダに生成されているか
2. **サーバー再起動**: Extension は起動時にのみ読み込む
3. **ログの確認**: `Registered X custom items with Geyser.` が出力されているか

---

## クイックリファレンス

### 最小構成 (テクスチャのみ)

```
my_pack/
├── manifest.json
└── textures/
    ├── item_texture.json
    └── items/
        └── my_item.png
```

### 必要なファイル数

| カスタムアイテム数 | 必要なファイル |
|-------------------|---------------|
| 1個 | manifest.json + item_texture.json + 1 PNG |
| 10個 | manifest.json + item_texture.json + 10 PNG |
| 100個 | manifest.json + item_texture.json + 100 PNG |

### 作業時間の目安

| 作業 | 時間 |
|------|------|
| GeyserExtra セットアップ | 5分 |
| manifest.json 作成 | 2分 |
| item_texture.json 作成 | アイテム数 × 1分 |
| テクスチャ配置 | アイテム数 × 30秒 |
| 3D モデル変換 | モデル × 10-30分 |

---

## 関連リンク

- [Geyser Custom Items Wiki](https://geysermc.org/wiki/geyser/custom-items/)
- [Bedrock Wiki - Items](https://wiki.bedrock.dev/items/items-intro.html)
- [Blockbench](https://blockbench.net/)
- [UUID Generator](https://www.uuidgenerator.net/)
