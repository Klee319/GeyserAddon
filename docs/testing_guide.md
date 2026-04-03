# GeyserExtra テストガイド

ローカル環境での機能テスト手順を説明します。

---

## 1. 前提条件

### 必要なソフトウェア
| ソフトウェア | バージョン | 入手先 |
|-------------|-----------|--------|
| Java | 21以上 | [Adoptium](https://adoptium.net/) |
| Minecraft Bedrock | Windows 10/11版 | Microsoft Store |
| Gradle | (Wrapper使用) | プロジェクト同梱 |

---

## 2. ビルド手順

### 2.1 Gradle インストール（未インストールの場合）

#### 方法1: SDKMAN (推奨 - Git Bash/WSL)
```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install gradle 8.5
```

#### 方法2: Chocolatey (PowerShell 管理者権限)
```powershell
choco install gradle
```

#### 方法3: 手動インストール
1. [Gradle 8.5 ダウンロード](https://gradle.org/releases/) から ZIP をダウンロード
2. 展開して `bin` フォルダを PATH に追加

### 2.2 Gradle Wrapper 生成
```bash
cd C:\Users\T-319\Documents\Program\ClaudeCodeDev\products\minecraft\geyserExtraα
gradle wrapper --gradle-version=8.5
```

### 2.3 プロジェクトビルド
```bash
# Windows
.\gradlew.bat shadowJar

# または PowerShell/Git Bash
./gradlew shadowJar
```

### 2.4 ビルド成果物
ビルド後、以下のJARファイルが生成されます：

| ファイル | 配置先 |
|---------|--------|
| `paper/build/libs/paper-1.0.0-SNAPSHOT.jar` | Paper plugins フォルダ |
| `extension/build/libs/extension-1.0.0-SNAPSHOT.jar` | Geyser extensions フォルダ |

---

## 3. ローカルサーバー構築

### 3.1 ディレクトリ構成
```
test-server/
├── paper-1.21.11.jar           # Paper サーバー
├── plugins/
│   ├── GeyserExtra-paper.jar  # ビルドしたPaper Plugin
│   ├── Geyser-Spigot.jar      # Geyser
│   ├── Floodgate-Spigot.jar   # Floodgate
│   └── ProtocolLib.jar        # ProtocolLib (オプション)
├── plugins/Geyser-Spigot/
│   └── extensions/
│       └── GeyserExtra.jar    # ビルドしたGeyser Extension
└── eula.txt
```

### 3.2 必要なプラグインのダウンロード

| プラグイン | URL |
|-----------|-----|
| Paper 1.21.11 | https://papermc.io/downloads/paper |
| Geyser-Spigot | https://geysermc.org/download |
| Floodgate-Spigot | https://geysermc.org/download |
| ProtocolLib | https://www.spigotmc.org/resources/protocollib.1997/ |

### 3.3 プラグイン配置

1. **Paper Plugin** を配置：
   ```
   paper/build/libs/paper-1.0.0-SNAPSHOT.jar
   → test-server/plugins/GeyserExtra-paper.jar
   ```

2. **Geyser Extension** を配置：
   ```
   extension/build/libs/extension-1.0.0-SNAPSHOT.jar
   → test-server/plugins/Geyser-Spigot/extensions/GeyserExtra.jar
   ```

### 3.4 サーバー起動
```bash
cd test-server
java -Xmx2G -jar paper-1.21.11.jar
```

初回起動時：
1. `eula.txt` の `eula=false` を `eula=true` に変更
2. 再起動

---

## 4. Bedrock クライアント接続

### 4.1 Geyser 設定確認

`plugins/Geyser-Spigot/config.yml`:
```yaml
bedrock:
  address: 0.0.0.0
  port: 19132

java:
  address: 127.0.0.1
  port: 25565
```

### 4.2 Windows 10/11 Bedrock からの接続

#### 方法1: LAN ゲーム（推奨）
1. Geyser がローカルネットワーク上で自動的にブロードキャストします
2. Bedrock で「遊ぶ」→「フレンド」タブを確認
3. 「LANゲーム」セクションにサーバーが表示されます

#### 方法2: サーバーを追加
1. Bedrock で「遊ぶ」→「サーバー」タブ
2. 「サーバーを追加」をクリック
3. 情報を入力：
   - サーバー名: `Local Test`
   - サーバーアドレス: `127.0.0.1`
   - ポート: `19132`

> **注意**: ループバック制限がある場合、以下を管理者権限で実行：
> ```powershell
> CheckNetIsolation LoopbackExempt -a -n="Microsoft.MinecraftUWP_8wekyb3d8bbwe"
> ```

---

## 5. 機能別テスト手順

### 5.1 カスタムアイテム自動マッピング

#### テスト準備
1. CustomModelData 付きアイテムを作成するコマンド：
   ```
   /give @s diamond_sword{CustomModelData:1}
   ```
   (Paper 1.21.4+ の場合、Data Component API 形式)

2. または ItemsAdder / Oraxen プラグインをインストール

#### 検証ポイント
- [ ] Paper 側のログに `Found X custom items` が出力される
- [ ] `plugins/Geyser-Spigot/extensions/geyserextra/shared/custom_items.json` が生成される
- [ ] Bedrock でアイテムを受け取った際に正しく表示される（テクスチャ未設定の場合はデフォルト表示）

#### ログ確認コマンド
```
/geyserextra debug
```

---

### 5.2 カスタムスカル自動マッピング

#### テスト準備
1. カスタムスカルをワールドに配置：
   - [Minecraft Heads](https://minecraft-heads.com/) からスカルコマンドを取得
   - 例: `/give @s player_head{SkullOwner:{...}}`

2. チャンクをロードさせる

#### 検証ポイント
- [ ] `skulls.json` にテクスチャハッシュが記録される
- [ ] サーバー再起動後も Bedrock でスカルが正しく表示される

---

### 5.3 スキン同期 (Bedrock → Java)

#### テスト準備
1. Java クライアントでログイン
2. Bedrock クライアントでログイン（別アカウント）

#### 検証ポイント
- [ ] Bedrock プレイヤーのスキンが Java プレイヤーに表示される
- [ ] ProtocolLib がない場合は機能が無効になる
- [ ] デバッグログに `Skin upload completed` が出力される

> **注意**: Mineskin API にアップロードするため、環境変数 `MINESKIN_API_KEY` の設定を推奨

---

### 5.4 エンチャント表示

#### テスト準備
1. Sweeping Edge 付きアイテムを作成：
   ```
   /enchant @s sweeping_edge 3
   ```

2. Unsafe レベル（上限超え）アイテム：
   ```
   /give @s diamond_sword{Enchantments:[{id:"minecraft:sharpness",lvl:10}]}
   ```

#### 検証ポイント
- [ ] Bedrock プレイヤーがアイテムを持つと Lore にエンチャント情報が追加される
- [ ] Sweeping Edge は Java 固有であることが表示される
- [ ] Unsafe レベルが強調表示される

---

### 5.5 透明光る額縁

#### テスト準備
1. 光る額縁を配置：
   ```
   /give @s glow_item_frame 16
   ```

2. 額縁にアイテムを設置

#### 検証ポイント
- [ ] リソースパックがクライアントに送信される
- [ ] Bedrock で光る額縁の枠が透明になる

#### リソースパック確認
`plugins/Geyser-Spigot/extensions/geyserextra/packs/` に `invisible_glow_frames` フォルダが存在すること

---

## 6. トラブルシューティング

### 6.1 ビルドエラー

**問題**: `Could not resolve io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`

**解決策**: `gradle.properties` のバージョンを利用可能なものに変更：
```properties
paperVersion=1.21.4-R0.1-SNAPSHOT
```

---

### 6.2 Extension がロードされない

**確認ポイント**:
1. `extension.yml` が JAR 内に存在するか
2. `plugins/Geyser-Spigot/extensions/` に配置されているか
3. Geyser のログに `Loaded extension: GeyserExtra` が出るか

---

### 6.3 Bedrock から接続できない

**チェックリスト**:
1. ファイアウォールでポート 19132 (UDP) が許可されているか
2. ループバック制限が解除されているか（上記コマンド参照）
3. Geyser の設定でアドレスが `0.0.0.0` になっているか

---

### 6.4 custom_items.json が生成されない

**確認ポイント**:
1. `config.json` で `customItems.enabled: true` になっているか
2. CustomModelData 付きアイテムがインベントリ/ワールドに存在するか
3. 共有フォルダのパスが正しいか: `plugins/Geyser-Spigot/extensions/geyserextra/shared/`

---

## 7. デバッグモード

### 7.1 有効化

Paper Plugin の `config.json`:
```json
{
  "general": {
    "debugMode": true
  }
}
```

### 7.2 ログ確認

デバッグモードでは以下の情報が出力されます：
- スキャンされたアイテム数
- 検出された CustomModelData 値
- スキンアップロードの状態
- エンチャント処理の詳細

---

## 8. テストチェックリスト

| 機能 | テスト項目 | 状態 |
|------|-----------|------|
| ビルド | `./gradlew shadowJar` が成功する | ☐ |
| Paper Plugin | サーバー起動時にロードされる | ☐ |
| Geyser Extension | Geyser 起動時にロードされる | ☐ |
| カスタムアイテム | custom_items.json が生成される | ☐ |
| カスタムアイテム | Bedrock でアイテムが表示される | ☐ |
| カスタムスカル | skulls.json が生成される | ☐ |
| カスタムスカル | Bedrock でスカルが表示される | ☐ |
| スキン同期 | Bedrock スキンが Java に表示される | ☐ |
| エンチャント | Lore にエンチャント情報が追加される | ☐ |
| 透明額縁 | リソースパックが適用される | ☐ |

---

## 参考リンク

- [Geyser Wiki](https://geysermc.org/wiki/)
- [Paper Downloads](https://papermc.io/downloads)
- [Minecraft Heads](https://minecraft-heads.com/)
- [Mineskin API](https://mineskin.org/)
