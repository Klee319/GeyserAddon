# GeyserExtra

Geyser環境でBedrock Edition（統合版）プレイヤーにJava Editionに近い体験を提供するプラグイン＆エクステンション。

## 概要

GeyserExtraは**Paper Plugin**と**Geyser Extension**のハイブリッド構成で動作します。

- **Paper Plugin**: サーバーサイド処理（アイテムスキャン、レシピ同期、エンチャント表示、金床保護、アンビルシミュレーション、DiscordSRV連携）
- **Geyser Extension**: Bedrock固有処理（カスタムアイテム登録、スカル登録、リソースパック配信）

---

## 機能一覧

### 1. カスタムアイテム自動マッピング

CustomModelDataを持つアイテムを自動検出し、Bedrockプレイヤーに正しいテクスチャを表示。

**対応条件:**
- PersistentDataContainerにアイテムIDが設定されていること
- または、CustomModelData.strings()に識別子が設定されていること

### 2. カスタムスカル自動マッピング

プレイヤーヘッドのカスタムテクスチャを自動検出し、Bedrockプレイヤーに表示。

### 3. エンチャント表示 + 耐久値表示（Bedrock向けlore注入）

Bedrockクライアントが表示できないエンチャント情報と耐久値を、ProtocolLibパケット改変でアイテムのlore（説明文）に日本語で注入。

**対象:**
- **カスタムエンチャント**: Paper Registry APIなどで追加された非バニラエンチャント（Bedrockでは表示不可）
- **オーバーエンチャント**: バニラ上限を超えるレベルのエンチャント（Bedrockではレベルが切り詰められる）
- **耐久値**: 残り/最大を色分け表示（緑: 50%以上、黄: 20-50%、赤: 20%以下）

**表示例:**
```
鋭さ X
耐久力 V
カスタムエンチャント名 III
耐久値: 1234/1561
```

**特徴:**
- ProtocolLibパケットレベルで改変するため、サーバー側のアイテムデータは変更されない
- Javaプレイヤーには影響なし（Bedrockプレイヤーのみ）
- バニラ風の日本語表記（呪いは赤色表示）

### 4. 金床オーバーエンチャント保護

バニラ上限を超えるエンチャント（オーバーエンチャント）がBedrockクライアントによってダウングレードされるのを防止。

**動作:**
1. Bedrockプレイヤーがアンビルを使用した際、サーバー側で正しい結果を計算・キャッシュ
2. 結果スロットクリック時にキャッシュされた正しい結果を適用
3. XP消費・アイテム配布も正しく処理

### 5. アンビルシミュレーション（チェストUI偽装）

BedrockプレイヤーのアンビルUIをチェストUI(3x9)に偽装し、Bedrockクライアントのエンチャント検証をバイパス。これにより、カスタムエンチャント本のアンビル適用がBedrockプレイヤーでも可能になります。

**動作モード（`enchantment.anvilSimulationMode`）:**

| モード | 通常時 | スニーク時 |
|--------|--------|-----------|
| `NOT_SNEAKING` (デフォルト) | チェストUI | バニラアンビル |
| `ALWAYS` | チェストUI | チェストUI |
| `SNEAKING` | バニラアンビル | チェストUI |
| `DISABLED` | バニラアンビル | バニラアンビル |

**注意:**
- チェストUIモードではアイテムの名前変更はできません（テキスト入力不可のため）
- 名前変更が必要な場合はスニークしてアンビルを開いてください（`NOT_SNEAKING`モード時）
- サーバー内部では本物のアンビルが動作するため、エンチャント合成ロジックは変更されません

### 6. クラフトレシピ同期

プラグインが追加したクラフトレシピの結果プレビューをBedrockプレイヤーに正しく表示。

### 7. リソースパック配信

#### 光る額縁の透明化パック

`resourcePacks.invisibleGlowFramesEnabled: true` で有効化すると、**光る額縁（Glow Item Frame）のみ**がBedrockプレイヤーに透明表示されます。通常の額縁は影響を受けません。建築での額縁を使った装飾に最適です。

### 8. Bedrockコマンド

Bedrock Edition固有の制限を補うコマンドを提供。

| コマンド | 説明 |
|---|---|
| `/offhand` | オフハンドにアイテムを移動 |
| `/tooltip` | 手に持ったアイテムの詳細情報をフォーム表示 |
| `/menu` | Bedrockプレイヤー向け統合メニュー |
| `/advancements` | 進捗をフォームUIで閲覧 |
| `/stats` | 統計情報をフォームUIで閲覧 |
| `/gamerules` | ゲームルール一覧をフォームUIで閲覧 |

### 9. DiscordSRV連携

DiscordSRVがインストールされている場合、BedrockプレイヤーのDiscordメッセージに**実際のスキンのアバター**を表示。DiscordSRVが未インストールの場合は自動的に無視されます。

**動作:**
- Bedrockプレイヤーのチャットメッセージをインターセプト
- GameProfileからスキンテクスチャURLを抽出
- Crafthead経由でヘッドレンダー画像に変換し、Webhook avatarとして使用

### 10. エリトラ飛行ワークアラウンド

Bedrock Editionのグライディング挙動の差異を補正。

---

## インストール

### 必要環境

- Paper 1.21.x 以上
- Geyser-Spigot
- Floodgate
- ProtocolLib

### オプション依存

- DiscordSRV（Discord連携機能に必要）

### インストール手順

1. `GeyserExtra.jar` を `plugins/` フォルダに配置
2. `extension.jar` を `plugins/Geyser-Spigot/extensions/geyserextra.jar` として配置
3. サーバーを起動
4. 生成された `config.json` を必要に応じて編集
5. サーバーを再起動

---

## 他プラグインとの関係

### Geyser-Anvil-Fix

| 機能 | GeyserExtra | Geyser-Anvil-Fix |
|------|-------------|------------------|
| エンチャント表示（lore） | 対応 | 非対応 |
| 耐久値表示 | 対応 | 非対応 |
| オーバーエンチャント保護 | 対応 | 対応 |
| カスタムエンチャント本のアンビル適用 | 対応（チェストUI偽装） | 対応 |

GeyserExtraはGeyser-Anvil-Fixの上位互換として位置づけられます。

### Geyser Recipe Fixer

| 機能 | GeyserExtra | Geyser Recipe Fixer |
|------|-------------|---------------------|
| カスタムアンビルレシピ | 対応 | 対応 |
| カスタム鍛冶台レシピ | 対応 | 対応 |
| カスタムエンチャント適用 | 対応（チェストUI偽装） | 対応 |
| エンチャント表示（lore） | 対応 | 非対応 |
| リソースパック配信 | 対応 | 非対応 |

GeyserExtraはGeyser Recipe Fixerの上位互換として位置づけられます。

---

## カスタムアイテム連携ガイド

### プラグイン開発者向け: 自動マッピング対応方法

GeyserExtraがカスタムアイテムを自動認識するには、**PersistentDataContainer**にアイテムIDを設定する必要があります。

#### 必須: アイテムID設定

```java
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

public ItemStack createCustomItem() {
    ItemStack item = new ItemStack(Material.DIAMOND);
    ItemMeta meta = item.getItemMeta();

    // CustomModelData設定
    CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
    cmd.setFloats(List.of(1001.0f));
    meta.setCustomModelDataComponent(cmd);

    // アイテムID設定（GeyserExtra自動認識用）
    meta.getPersistentDataContainer().set(
        new NamespacedKey(plugin, "item_id"),
        PersistentDataType.STRING,
        "compressed_diamond"
    );

    item.setItemMeta(meta);
    return item;
}
```

#### 認識されるPDCキー名

| キーパターン | 例 |
|---|---|
| `item_id` | `myplugin:item_id` |
| `itemid` | `myplugin:itemid` |
| `id` | `myplugin:id` |
| `identifier` | `myplugin:identifier` |
| `name` | `myplugin:name` |
| `item_name` | `myplugin:item_name` |
| `custom_item` | `myplugin:custom_item` |
| `custom_id` | `myplugin:custom_id` |
| `type` | `myplugin:type` |
| `item_type` | `myplugin:item_type` |

#### アイテムID命名規則

| ルール | 良い例 | 悪い例 |
|---|---|---|
| 小文字のみ | `compressed_diamond` | `Compressed_Diamond` |
| スペースなし | `fire_sword` | `fire sword` |
| 英数字とアンダースコア | `item_v2` | `item-v2` |
| 64文字以内 | `my_item` | (長すぎる名前) |

---

## 設定ファイル

`plugins/Geyser-Spigot/extensions/geyserextra/config.json`:

```json
{
  "general": {
    "enabled": true,
    "debugMode": false,
    "workerThreads": 2
  },
  "customItems": {
    "enabled": true,
    "mappingsFile": "custom_items.json",
    "autoReload": false,
    "reloadIntervalSeconds": 60,
    "bedrockPacksPath": ""
  },
  "skulls": {
    "enabled": true,
    "dataFile": "skulls.json",
    "cacheTextures": true,
    "maxCachedTextures": 1000,
    "textureResolution": 64
  },
  "enchantment": {
    "enabled": true,
    "showCustomEnchantments": true,
    "showOverEnchantments": true,
    "overEnchantmentProtectionEnabled": true,
    "overEnchantmentLevelUpEnabled": false,
    "anvilSimulationEnabled": true,
    "anvilSimulationMode": "NOT_SNEAKING"
  },
  "resourcePacks": {
    "invisibleGlowFramesEnabled": true
  }
}
```

### 設定項目

| 項目 | 説明 | デフォルト |
|---|---|---|
| `general.enabled` | プラグイン全体の有効/無効 | `true` |
| `general.debugMode` | デバッグログ出力 | `false` |
| `general.workerThreads` | バックグラウンドワーカースレッド数 | `2` |
| `customItems.enabled` | カスタムアイテム機能 | `true` |
| `customItems.bedrockPacksPath` | BEテクスチャフィルタ用パスク（空で無効） | `""` |
| `skulls.enabled` | カスタムスカル機能 | `true` |
| `enchantment.enabled` | エンチャント表示・保護機能全体 | `true` |
| `enchantment.showCustomEnchantments` | カスタムエンチャントのlore表示 | `true` |
| `enchantment.showOverEnchantments` | オーバーエンチャントのlore表示 | `true` |
| `enchantment.overEnchantmentProtectionEnabled` | アンビルでのオーバーエンチャント保護 | `true` |
| `enchantment.overEnchantmentLevelUpEnabled` | 同レベル合成時のレベルアップ許可 | `false` |
| `enchantment.anvilSimulationEnabled` | アンビルUI偽装（チェストUI化） | `true` |
| `enchantment.anvilSimulationMode` | 偽装モード（ALWAYS/NOT_SNEAKING/SNEAKING/DISABLED） | `NOT_SNEAKING` |
| `resourcePacks.invisibleGlowFramesEnabled` | 光る額縁透明化リソパ配信 | `true` |

---

## トラブルシューティング

### カスタムアイテムが認識されない

**警告メッセージが表示される場合:**
```
=== Custom Item Mapping Warning ===
Item detected without PersistentDataContainer identifier:
  Base Item: minecraft:diamond
  CustomModelData: 1005
```

**解決方法:**
1. プラグインでPersistentDataContainerにアイテムIDを設定
2. または、`custom_items.json`を手動編集

### エンチャントがloreに表示されない

**確認項目:**
1. `enchantment.enabled` が `true` になっているか
2. ProtocolLibが正しくインストールされているか
3. Floodgateが正しくインストールされているか
4. 対象エンチャントがカスタムまたはオーバーエンチャントであるか（バニラの通常レベルは対象外）

### アンビルで名前変更ができない

チェストUI偽装モードではテキスト入力ができないため、名前変更はできません。
スニークしながらアンビルを開くと、バニラのアンビルUIが使用できます（デフォルトの`NOT_SNEAKING`モード時）。

### 光る額縁が透明にならない

**確認項目:**
1. `resourcePacks.invisibleGlowFramesEnabled` が `true` になっているか
2. サーバー再起動後に接続しているか（リソパ登録はサーバー起動時）
3. Bedrockクライアントでリソースパックを受け入れているか
4. 通常の額縁は透明化の対象外です（光る額縁のみ）

### DiscordSRVのアバターが変わらない

**確認項目:**
1. DiscordSRVがWebhookモードを使用しているか
2. GeyserExtraのログに `[DiscordSRV] Avatar hook registered` が出力されているか
3. Bedrockプレイヤーのスキンが正しく読み込まれているか（Geyser Global API依存）

---

## 生成ファイル

| ファイル | 場所 | 説明 |
|---|---|---|
| `custom_items.json` | `extensions/geyserextra/` | 検出されたカスタムアイテム |
| `skulls.json` | `extensions/geyserextra/` | 検出されたカスタムスカル |
| `config.json` | `extensions/geyserextra/` | 設定ファイル |

---

## 対応プラグイン

以下のプラグインとの連携をサポート（PDC設定が必要）:

- ItemsAdder
- Oraxen
- 自作プラグイン（上記命名規則に従う場合）

---

## ライセンス

MIT License

---

## 貢献

バグ報告や機能リクエストはIssueでお願いします。
