# GeyserExtra

Geyser環境でBedrock Edition（統合版）プレイヤーにJava Editionに近い体験を提供するプラグイン＆エクステンション。
リポジトリ：https://github.com/Klee319/GeyserAddon.git
## 概要

GeyserExtraは**Paper Plugin**と**Geyser Extension**のハイブリッド構成で動作します。

- **Paper Plugin**: サーバーサイド処理（アイテムスキャン、レシピ同期、エンチャント表示、金床保護、アンビルシミュレーション、DiscordSRV連携）
- **Geyser Extension**: Bedrock固有処理（カスタムアイテム登録、スカル登録、リソースパック配信）

---

## 機能一覧

### 1. カスタムアイテム自動マッピング

CustomModelDataを持つアイテムを自動検出し、Bedrockプレイヤーから新規アイテムとして識別できるよう登録します。クラフトリザルトのプレビュー、レシピブック、操作・効果が JE と同等に動作します。

**Bedrock 側のテクスチャ:**
- 既定では**ベースアイテムのバニラテクスチャ**で表示されます
  （例: `minecraft:diamond_sword` ベースなら Bedrock 上はダイヤ剣の見た目）
- 専用 BE リソースパックの作成は**不要**です。GeyserExtra が起動時に最小パック (`packs/geyserextra_auto.zip`) を自動生成し、`item_texture.json` の各エントリをベースアイテムのテクスチャパスへ向けて配信します
- 専用テクスチャを表示したい場合は、自動パックのエントリを上書きする BE リソースパックを別途配置してください

**マッピング名の決定（優先順）:**
1. PersistentDataContainer の `item_id` 等のキー（複数の標準名に対応）
2. CustomModelData の `strings()` に設定された識別子
3. 自動生成名 `custom_<base>_<CMD>`（PDC・strings 共に未設定の場合）

**ログ出力:**
PDC無しで自動生成名を使ったアイテムは、スキャンバースト終息後に1行のINFOサマリ（例: `[CustomItems] Auto-registered 50 item(s) without PDC identifier`）として出力。詳細表示は `customItems.pdcWarning` で `FULL` / `COMPACT` / `DISABLED` を選択可能。

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

GeyserExtraはCustomModelData付きのアイテムをすべて自動登録します。PDCを設定しなくても登録自体は成立し、Bedrock 上ではベースアイテムのテクスチャで表示されます。**PDC設定は推奨**で、設定しておくとマッピング名がプラグイン由来の安定識別子になり、`custom_items.json` を編集して個別の表示名・専用 BE テクスチャを割り当てやすくなります。

#### 推奨: アイテムID設定

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
    "pdcWarning": "COMPACT"
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
| `customItems.pdcWarning` | PDC無しアイテムのログ詳細度 (`FULL`/`COMPACT`/`DISABLED`) | `COMPACT` |
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

### PDC無しアイテムが大量に検出される / ログを抑制したい

**INFOサマリが表示される場合（PDC無しでも自動登録は成功しています）:**
```
[CustomItems] Auto-registered 50 item(s) without PDC identifier
(BE clients render them as the base item via the auto-generated pack).
```

これは「PDC識別子無しのCMDアイテムを50件、ベース見た目で自動登録した」という通知で、機能不全ではありません。

**抑制方法:**
- `customItems.pdcWarning` を `DISABLED` にすると一切ログ出力しません（登録自体は継続）
- `FULL` にすると 1 アイテムごとに INFO 行 + サマリを出力します
- 個別アイテムの登録を無効化したい場合は `custom_items.json` の該当エントリで `"register": false` を指定

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
