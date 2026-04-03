# GeyserExtra Extension ステートマシン図

このドキュメントは、GeyserExtra Extension システム全体の状態遷移をMermaid形式で記述します。

## 1. 全体システムステートマシン - Paper Plugin と Geyser Extension の連携

```mermaid
stateDiagram-v2
    [*] --> サーバー起動

    state サーバー起動 {
        [*] --> PaperPluginロード中
        PaperPluginロード中 --> GeyserExtensionロード中: Paper起動完了
        GeyserExtensionロード中 --> 両コンポーネント初期化完了: Geyser起動完了
    }

    サーバー起動 --> 運用中: 初期化成功

    state 運用中 {
        [*] --> データ同期待機

        state データ同期待機 {
            [*] --> Paper側スキャン実行中
            Paper側スキャン実行中 --> 共有フォルダ書き込み: 新規アイテム/スカル発見
            共有フォルダ書き込み --> Paper側スキャン実行中: 書き込み完了
        }

        state Bedrockプレイヤー接続 {
            [*] --> セッション確立
            セッション確立 --> カスタムデータ適用: プレイヤー参加
            カスタムデータ適用 --> ゲームプレイ中: 適用完了
            ゲームプレイ中 --> セッション終了: プレイヤー退出
        }

        データ同期待機 --> Bedrockプレイヤー接続: Bedrockクライアント接続
        Bedrockプレイヤー接続 --> データ同期待機: プレイヤー切断
    }

    運用中 --> サーバーシャットダウン: 停止コマンド

    state サーバーシャットダウン {
        [*] --> レジストリ保存中
        レジストリ保存中 --> PaperPlugin無効化: 保存完了
        PaperPlugin無効化 --> GeyserExtension無効化: Paper終了
        GeyserExtension無効化 --> [*]: Geyser終了
    }

    サーバーシャットダウン --> [*]
```

## 2. Paper Plugin ライフサイクル - 起動から終了まで

```mermaid
stateDiagram-v2
    [*] --> onEnable呼び出し: プラグインロード

    state onEnable呼び出し {
        [*] --> 設定ロード中
        設定ロード中 --> 有効判定: loadConfiguration()

        state 有効判定 <<choice>>
        有効判定 --> 無効状態: config.enabled = false
        有効判定 --> レジストリ初期化中: config.enabled = true

        state レジストリ初期化中 {
            [*] --> ItemMappingRegistry作成
            ItemMappingRegistry作成 --> SkullRegistry作成
            SkullRegistry作成 --> 既存データロード試行
            既存データロード試行 --> [*]: initializeRegistries()
        }

        レジストリ初期化中 --> スキャナー初期化中: 完了

        state スキャナー初期化中 {
            [*] --> CustomItemScanner作成
            CustomItemScanner作成 --> SkullScanner作成
            SkullScanner作成 --> [*]: initializeScanners()
        }

        スキャナー初期化中 --> リスナー登録中: 完了

        state リスナー登録中 {
            [*] --> ItemListener登録
            ItemListener登録 --> ChunkLoadListener登録: skulls.enabled = true
            ItemListener登録 --> [*]: skulls.enabled = false
            ChunkLoadListener登録 --> [*]: registerListeners()
        }

        リスナー登録中 --> タスクスケジュール中: 完了

        state タスクスケジュール中 {
            [*] --> 自動保存タスク設定判定

            state 自動保存タスク設定判定 <<choice>>
            自動保存タスク設定判定 --> 定期保存タスク登録: autoReload = true
            自動保存タスク設定判定 --> [*]: autoReload = false
            定期保存タスク登録 --> [*]: scheduleTasks()
        }

        タスクスケジュール中 --> 初期スキャン実行: 完了
        初期スキャン実行 --> プラグイン有効完了: performInitialScan()
    }

    無効状態 --> [*]: プラグイン無効のまま終了
    onEnable呼び出し --> 運用中: 初期化成功

    state 運用中 {
        [*] --> イベント待機中

        イベント待機中 --> プレイヤー参加処理: PlayerJoinEvent
        イベント待機中 --> インベントリ開封処理: InventoryOpenEvent
        イベント待機中 --> アイテム保持変更処理: PlayerItemHeldEvent
        イベント待機中 --> チャンクロード処理: ChunkLoadEvent
        イベント待機中 --> 定期保存処理: タイマー発火

        プレイヤー参加処理 --> イベント待機中: スキャン完了
        インベントリ開封処理 --> イベント待機中: スキャン完了
        アイテム保持変更処理 --> イベント待機中: スキャン完了
        チャンクロード処理 --> イベント待機中: スキャン完了
        定期保存処理 --> イベント待機中: 保存完了
    }

    運用中 --> onDisable呼び出し: サーバー停止

    state onDisable呼び出し {
        [*] --> 最終レジストリ保存
        最終レジストリ保存 --> 共有フォルダ書き込み完了: saveRegistriesToSharedFolder()
        共有フォルダ書き込み完了 --> [*]
    }

    onDisable呼び出し --> [*]: プラグイン終了
```

## 3. Geyser Extension ライフサイクル - 起動から終了まで

```mermaid
stateDiagram-v2
    [*] --> GeyserPreInitializeEvent: Extension ロード

    state GeyserPreInitializeEvent {
        [*] --> データフォルダパス取得
        データフォルダパス取得 --> 共有フォルダパス設定
        共有フォルダパス設定 --> packsフォルダパス設定

        state ハンドラー初期化 {
            [*] --> CustomItemsHandler生成
            CustomItemsHandler生成 --> CustomSkullsHandler生成
            CustomSkullsHandler生成 --> SkinHandler生成
            SkinHandler生成 --> ResourcePackHandler生成
            ResourcePackHandler生成 --> [*]
        }

        packsフォルダパス設定 --> ハンドラー初期化
        ハンドラー初期化 --> [*]: onPreInitialize()
    }

    GeyserPreInitializeEvent --> カスタム定義イベント群: 初期化完了

    state カスタム定義イベント群 {
        [*] --> GeyserDefineCustomItemsEvent
        [*] --> GeyserDefineCustomSkullsEvent
        [*] --> GeyserDefineResourcePacksEvent

        state GeyserDefineCustomItemsEvent {
            [*] --> custom_items.json読み込み
            custom_items.json読み込み --> アイテムマッピングパース
            アイテムマッピングパース --> Geyserへアイテム登録
            Geyserへアイテム登録 --> [*]
        }

        state GeyserDefineCustomSkullsEvent {
            [*] --> skulls.json読み込み
            skulls.json読み込み --> スカルエントリパース
            スカルエントリパース --> Geyserへスカル登録
            Geyserへスカル登録 --> [*]
        }

        state GeyserDefineResourcePacksEvent {
            [*] --> packsフォルダスキャン
            packsフォルダスキャン --> リソースパック登録
            リソースパック登録 --> [*]
        }

        GeyserDefineCustomItemsEvent --> 定義完了
        GeyserDefineCustomSkullsEvent --> 定義完了
        GeyserDefineResourcePacksEvent --> 定義完了
        定義完了 --> [*]
    }

    カスタム定義イベント群 --> GeyserPostInitializeEvent: 定義登録完了

    state GeyserPostInitializeEvent {
        [*] --> 初期化完了ログ出力
        初期化完了ログ出力 --> [*]: onPostInitialize()
    }

    GeyserPostInitializeEvent --> 運用中: 完全初期化完了

    state 運用中 {
        [*] --> セッションイベント待機中

        セッションイベント待機中 --> SessionSkinApplyEvent処理: スキン適用要求

        state SessionSkinApplyEvent処理 {
            [*] --> ロギング有効判定

            state ロギング有効判定 <<choice>>
            ロギング有効判定 --> スキンイベントログ出力: loggingEnabled = true
            ロギング有効判定 --> [*]: loggingEnabled = false

            スキンイベントログ出力 --> [*]
        }

        SessionSkinApplyEvent処理 --> セッションイベント待機中: 処理完了
    }

    運用中 --> [*]: Geyser終了
```

## 4. Custom Item スキャンプロセス - アイテム検出から登録まで

```mermaid
stateDiagram-v2
    [*] --> スキャントリガー

    state スキャントリガー {
        [*] --> PlayerJoinEventトリガー: プレイヤー参加
        [*] --> InventoryOpenEventトリガー: インベントリ開封
        [*] --> PlayerItemHeldEventトリガー: アイテム持ち替え
        [*] --> 全プレイヤースキャントリガー: 初期スキャン

        PlayerJoinEventトリガー --> 遅延スケジュール: 20tick後
        InventoryOpenEventトリガー --> 非同期スケジュール: 即時
        PlayerItemHeldEventトリガー --> アイテム単体スキャン: 即時
        全プレイヤースキャントリガー --> 全プレイヤー反復処理: 非同期
    }

    遅延スケジュール --> プレイヤーインベントリスキャン: メインスレッド実行
    非同期スケジュール --> インベントリスキャン: メインスレッド実行
    全プレイヤー反復処理 --> プレイヤーインベントリスキャン: 各プレイヤー

    state プレイヤーインベントリスキャン {
        [*] --> メインインベントリスキャン
        メインインベントリスキャン --> アーマースロットスキャン
        アーマースロットスキャン --> オフハンドスキャン
        オフハンドスキャン --> エンダーチェストスキャン: 全プレイヤースキャン時
        エンダーチェストスキャン --> [*]
        オフハンドスキャン --> [*]: 通常スキャン時
    }

    インベントリスキャン --> アイテムスキャン反復: 各アイテム
    プレイヤーインベントリスキャン --> アイテムスキャン反復: 各アイテム
    アイテム単体スキャン --> アイテムスキャン反復: 単一アイテム

    state アイテムスキャン反復 {
        [*] --> NULL判定

        state NULL判定 <<choice>>
        NULL判定 --> CustomModelData存在確認: itemStack != null && type != AIR
        NULL判定 --> スキップ: null または AIR

        state CustomModelData存在確認 <<choice>>
        CustomModelData存在確認 --> CMD値抽出: hasData(CUSTOM_MODEL_DATA)
        CustomModelData存在確認 --> スキップ: CMDなし

        CMD値抽出 --> floats配列確認

        state floats配列確認 <<choice>>
        floats配列確認 --> プライマリCMD値取得: floats非空
        floats配列確認 --> strings配列確認: floats空

        state strings配列確認 <<choice>>
        strings配列確認 --> スキップ: strings空
        strings配列確認 --> 識別子としてstrings使用: strings非空

        プライマリCMD値取得 --> ベースアイテムID構築
        識別子としてstrings使用 --> ベースアイテムID構築

        ベースアイテムID構築 --> マッピング名生成

        state マッピング名生成 {
            [*] --> strings識別子チェック

            state strings識別子チェック <<choice>>
            strings識別子チェック --> strings値を名前として使用: 有効な識別子パターン
            strings識別子チェック --> 自動名生成: それ以外

            strings値を名前として使用 --> [*]
            自動名生成 --> [*]: custom_{baseItem}_{cmd}
        }

        マッピング名生成 --> レジストリ重複確認

        state レジストリ重複確認 <<choice>>
        レジストリ重複確認 --> 既存マッピング返却: 登録済み
        レジストリ重複確認 --> 新規マッピング作成: 未登録

        state 新規マッピング作成 {
            [*] --> 表示名抽出
            表示名抽出 --> 破壊不可フラグ確認
            破壊不可フラグ確認 --> CustomItemMapping構築
            CustomItemMapping構築 --> レジストリ登録
            レジストリ登録 --> デバッグログ出力: debugMode = true
            レジストリ登録 --> [*]: debugMode = false
            デバッグログ出力 --> [*]
        }

        既存マッピング返却 --> [*]
        新規マッピング作成 --> [*]
        スキップ --> [*]
    }

    アイテムスキャン反復 --> スキャン完了: 全アイテム処理終了
    スキャン完了 --> [*]
```

## 5. Skull スキャンプロセス - スカル検出から登録まで

```mermaid
stateDiagram-v2
    [*] --> スキャントリガー

    state スキャントリガー {
        [*] --> ChunkLoadEventトリガー: チャンクロード
        [*] --> アイテムスカルスキャントリガー: スカルアイテム検出
        [*] --> ブロックスカルスキャントリガー: スカルブロック検出
    }

    state ChunkLoadEventトリガー {
        [*] --> 新規チャンク判定

        state 新規チャンク判定 <<choice>>
        新規チャンク判定 --> スキップ: isNewChunk = true
        新規チャンク判定 --> チャンクキー生成: 既存チャンク

        チャンクキー生成 --> スキャン中セット確認

        state スキャン中セット確認 <<choice>>
        スキャン中セット確認 --> スキップ: 既にスキャン中
        スキャン中セット確認 --> スキャン中セット追加: 未スキャン

        スキャン中セット追加 --> 遅延非同期スケジュール: 5tick後
    }

    遅延非同期スケジュール --> チャンクロード確認: 非同期実行

    state チャンクロード確認 <<choice>>
    チャンクロード確認 --> スキャン中セット削除してスキップ: !chunk.isLoaded()
    チャンクロード確認 --> メインスレッドスケジュール: チャンクロード済み

    メインスレッドスケジュール --> チャンクスキャン実行: メインスレッド

    state チャンクスキャン実行 {
        [*] --> TileEntity取得
        TileEntity取得 --> TileEntity反復処理

        state TileEntity反復処理 {
            [*] --> Skull型判定

            state Skull型判定 <<choice>>
            Skull型判定 --> 次のTileEntity: Skull型でない
            Skull型判定 --> プレイヤーヘッド判定: Skull型

            state プレイヤーヘッド判定 <<choice>>
            プレイヤーヘッド判定 --> 次のTileEntity: PLAYER_HEAD/WALL_HEADでない
            プレイヤーヘッド判定 --> PlayerProfile取得: プレイヤーヘッド

            PlayerProfile取得 --> Profile存在確認

            state Profile存在確認 <<choice>>
            Profile存在確認 --> 次のTileEntity: profile = null
            Profile存在確認 --> テクスチャ抽出処理: profile存在

            次のTileEntity --> [*]: 次へ
        }

        TileEntity反復処理 --> [*]: 全TileEntity処理完了
    }

    アイテムスカルスキャントリガー --> アイテムスカルスキャン
    ブロックスカルスキャントリガー --> ブロックスカルスキャン

    state アイテムスカルスキャン {
        [*] --> PLAYER_HEAD判定

        state PLAYER_HEAD判定 <<choice>>
        PLAYER_HEAD判定 --> 空を返却: type != PLAYER_HEAD
        PLAYER_HEAD判定 --> SkullMeta取得: PLAYER_HEAD

        SkullMeta取得 --> SkullMeta判定

        state SkullMeta判定 <<choice>>
        SkullMeta判定 --> 空を返却: SkullMetaでない
        SkullMeta判定 --> メタからテクスチャ抽出: SkullMeta
    }

    state ブロックスカルスキャン {
        [*] --> スカルマテリアル判定

        state スカルマテリアル判定 <<choice>>
        スカルマテリアル判定 --> 空を返却: スカル材質でない
        スカルマテリアル判定 --> プレイヤーヘッドブロック判定: スカル材質

        state プレイヤーヘッドブロック判定 <<choice>>
        プレイヤーヘッドブロック判定 --> 空を返却: プレイヤーヘッドでない
        プレイヤーヘッドブロック判定 --> BlockState取得: プレイヤーヘッド

        BlockState取得 --> Skull型キャスト判定

        state Skull型キャスト判定 <<choice>>
        Skull型キャスト判定 --> 空を返却: Skullでない
        Skull型キャスト判定 --> ブロックからPlayerProfile取得: Skull型
    }

    メタからテクスチャ抽出 --> テクスチャ抽出処理
    ブロックからPlayerProfile取得 --> テクスチャ抽出処理
    テクスチャ抽出処理 --> テクスチャ抽出処理詳細: profile取得成功

    state テクスチャ抽出処理詳細 {
        [*] --> texturesプロパティ検索
        texturesプロパティ検索 --> プロパティ存在確認

        state プロパティ存在確認 <<choice>>
        プロパティ存在確認 --> 空を返却: texturesプロパティなし
        プロパティ存在確認 --> Base64値取得: texturesプロパティあり

        Base64値取得 --> Base64デコード

        state Base64デコード {
            [*] --> デコード試行
            デコード試行 --> デコード結果判定

            state デコード結果判定 <<choice>>
            デコード結果判定 --> 空を返却: デコード失敗
            デコード結果判定 --> JSONからURL抽出: デコード成功
        }

        JSONからURL抽出 --> 正規表現マッチング

        state 正規表現マッチング <<choice>>
        正規表現マッチング --> 空を返却: URLパターン不一致
        正規表現マッチング --> テクスチャURL取得: URLパターン一致
    }

    テクスチャ抽出処理詳細 --> スカルデータ作成登録: テクスチャURL取得成功

    state スカルデータ作成登録 {
        [*] --> テクスチャハッシュ抽出
        テクスチャハッシュ抽出 --> レジストリ重複確認

        state レジストリ重複確認 <<choice>>
        レジストリ重複確認 --> 既存データ返却: 登録済み
        レジストリ重複確認 --> SkullData作成: 未登録

        SkullData作成 --> レジストリ登録
        レジストリ登録 --> デバッグログ出力: debugMode = true
        レジストリ登録 --> [*]: debugMode = false
        デバッグログ出力 --> [*]
        既存データ返却 --> [*]
    }

    空を返却 --> [*]
    スキップ --> [*]
    スキャン中セット削除してスキップ --> [*]
    スカルデータ作成登録 --> [*]
    チャンクスキャン実行 --> スキャン中セット削除: スキャン完了
    スキャン中セット削除 --> [*]
```

## 6. スキン同期プロセス - Bedrock プレイヤーのスキン適用

```mermaid
stateDiagram-v2
    [*] --> Bedrockプレイヤー接続

    Bedrockプレイヤー接続 --> SessionSkinApplyEvent発火: Geyserセッション確立

    state SessionSkinApplyEvent発火 {
        [*] --> SkinHandler.handleSkinApply呼び出し
    }

    SessionSkinApplyEvent発火 --> SkinHandler処理: イベント受信

    state SkinHandler処理 {
        [*] --> ハンドラー初期化確認

        state ハンドラー初期化確認 <<choice>>
        ハンドラー初期化確認 --> 警告ログ出力: skinHandler = null
        ハンドラー初期化確認 --> ロギング有効確認: skinHandler != null

        state ロギング有効確認 <<choice>>
        ロギング有効確認 --> 早期リターン: loggingEnabled = false
        ロギング有効確認 --> スキンイベント処理: loggingEnabled = true

        state スキンイベント処理 {
            [*] --> イベント情報抽出

            state イベント情報抽出 {
                [*] --> ユーザー名取得
                ユーザー名取得 --> UUID取得
                UUID取得 --> オリジナルスキンURL取得
                オリジナルスキンURL取得 --> スリムモデル判定
                スリムモデル判定 --> [*]
            }

            イベント情報抽出 --> ログ出力処理

            state ログ出力処理 {
                [*] --> URL切り詰め処理
                URL切り詰め処理 --> デバッグログフォーマット
                デバッグログフォーマット --> ログ出力
                ログ出力 --> [*]
            }

            ログ出力処理 --> [*]
        }

        state 例外処理 <<choice>>
        スキンイベント処理 --> 例外処理: 処理中
        例外処理 --> 警告ログ出力: 例外発生
        例外処理 --> 正常完了: 例外なし
    }

    早期リターン --> [*]
    警告ログ出力 --> [*]
    正常完了 --> [*]

    note right of SkinHandler処理
        現在の実装はログ出力のみ。
        将来的な拡張ポイント:
        - カスタムスキン変更 (modifySkin)
        - スキンキャッシュ (cacheSkin)
        - スキンオーバーレイ適用
        - ジオメトリ変更
    end note
```

## 7. 共有フォルダを介したデータフロー

```mermaid
stateDiagram-v2
    [*] --> Paper側処理

    state Paper側処理 {
        [*] --> アイテム/スカル発見

        state アイテム/スカル発見 {
            [*] --> CustomItemScanner実行
            [*] --> SkullScanner実行
            CustomItemScanner実行 --> ItemMappingRegistry更新
            SkullScanner実行 --> SkullRegistry更新
        }

        ItemMappingRegistry更新 --> 共有フォルダ保存トリガー
        SkullRegistry更新 --> 共有フォルダ保存トリガー

        state 共有フォルダ保存トリガー {
            [*] --> 定期タイマー発火: autoReload有効時
            [*] --> サーバー停止時: onDisable
            [*] --> 手動トリガー: 将来実装
        }

        共有フォルダ保存トリガー --> レジストリ保存処理

        state レジストリ保存処理 {
            [*] --> 共有フォルダ存在確認
            共有フォルダ存在確認 --> ディレクトリ作成: 存在しない
            共有フォルダ存在確認 --> custom_items.json書き込み: 存在する
            ディレクトリ作成 --> custom_items.json書き込み

            custom_items.json書き込み --> skulls.json書き込み: customItems.enabled
            custom_items.json書き込み --> skulls.json書き込み判定: customItems.disabled

            state skulls.json書き込み判定 <<choice>>
            skulls.json書き込み判定 --> skulls.json書き込み: skulls.enabled
            skulls.json書き込み判定 --> [*]: skulls.disabled

            skulls.json書き込み --> [*]
        }
    }

    Paper側処理 --> 共有フォルダ: JSON保存

    state 共有フォルダ {
        [*] --> ファイル状態

        state ファイル状態 {
            custom_items.json: カスタムアイテム定義
            skulls.json: スカルテクスチャ定義
        }
    }

    共有フォルダ --> Geyser側処理: 起動時読み込み

    state Geyser側処理 {
        [*] --> ハンドラー初期化時

        state ハンドラー初期化時 {
            [*] --> CustomItemsHandler
            [*] --> CustomSkullsHandler

            state CustomItemsHandler {
                [*] --> custom_items.json存在確認

                state custom_items.json存在確認 <<choice>>
                custom_items.json存在確認 --> サンプルファイル作成: 存在しない
                custom_items.json存在確認 --> JSONパース: 存在する

                JSONパース --> アイテムマッピング構築
                アイテムマッピング構築 --> メモリ保持
            }

            state CustomSkullsHandler {
                [*] --> skulls.json存在確認

                state skulls.json存在確認 <<choice>>
                skulls.json存在確認 --> サンプルファイル作成: 存在しない
                skulls.json存在確認 --> JSONパース: 存在する

                JSONパース --> スカルエントリ構築
                スカルエントリ構築 --> メモリ保持
            }
        }

        ハンドラー初期化時 --> Geyserイベント登録時

        state Geyserイベント登録時 {
            [*] --> GeyserDefineCustomItemsEvent
            [*] --> GeyserDefineCustomSkullsEvent

            GeyserDefineCustomItemsEvent --> カスタムアイテムをGeyserに登録
            GeyserDefineCustomSkullsEvent --> カスタムスカルをGeyserに登録
        }
    }

    Geyser側処理 --> Bedrockプレイヤーに反映: カスタムコンテンツ配信
    Bedrockプレイヤーに反映 --> [*]
```

---

## ステートマシン図の解説

### 1. 全体システムステートマシン
Paper PluginとGeyser Extensionの連携を示しています。サーバー起動時にPaperが先に初期化され、その後Geyserが起動します。運用中はデータ同期とBedrockプレイヤーの接続処理が並行して行われます。

### 2. Paper Plugin ライフサイクル
`onEnable`から`onDisable`までの完全なライフサイクルを示しています。設定ロード、レジストリ初期化、スキャナー初期化、リスナー登録、タスクスケジュールの順序で初期化が行われます。

### 3. Geyser Extension ライフサイクル
`GeyserPreInitializeEvent`から運用開始までの流れを示しています。各ハンドラーの初期化とカスタムコンテンツの定義イベント処理が含まれます。

### 4. Custom Item スキャンプロセス
各種イベント（プレイヤー参加、インベントリ開封、アイテム持ち替え）からCustomModelDataを持つアイテムを検出し、レジストリに登録するまでの詳細な流れを示しています。

### 5. Skull スキャンプロセス
チャンクロードイベントやブロック/アイテムスキャンから、PlayerProfileのテクスチャデータを抽出してレジストリに登録するまでの流れを示しています。

### 6. スキン同期プロセス
Bedrockプレイヤーのスキン適用イベント処理を示しています。現在はログ出力のみですが、将来の拡張ポイントも記載しています。

### 7. 共有フォルダを介したデータフロー
Paper PluginとGeyser Extension間でのデータ同期の仕組みを示しています。Paper側がJSONファイルを生成し、Geyser側が起動時に読み込む一方向のデータフローとなっています。
