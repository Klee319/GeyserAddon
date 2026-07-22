# Task

Geyserが起動時にキャッシュしたリソースパックを、遅延スキャンやプレイヤー参加処理が稼働中に置換して全カスタムアイテムが透明になる問題を防止する。

# Files Changed

- `paper/src/main/java/com/geyserextra/paper/GeyserExtraPaper.java`
- `paper/src/main/java/com/geyserextra/paper/listener/ItemListener.java`
- `paper/src/main/java/com/geyserextra/paper/pack/AutoPackBuildGuard.java`
- `paper/src/main/java/com/geyserextra/paper/scanner/RecipeScanner.java`
- `paper/src/main/java/com/geyserextra/paper/scanner/WorldSkullScanner.java`
- `paper/src/main/resources/plugin.yml`
- `paper/src/test/java/com/geyserextra/paper/PluginLoadOrderTest.java`
- `paper/src/test/java/com/geyserextra/paper/pack/AutoPackBuildGuardTest.java`
- `README.md`
- `reports/20260721_2249_PreventRuntimePackReplacement.md`

# Details

- GeyserExtraの同期即時スキャン中だけ開く明示的な自動パック生成ウィンドウを追加した。
- 同期起動ウィンドウ終了後は `custom_items.json` の更新を継続する一方、`geyserextra_auto.zip` を置換しない。
- GeyserExtra自身の同期起動フェーズを明示的に管理し、即時スキャンが例外終了した場合も `finally` でウィンドウを閉じる。
- `loadbefore` が有効な通常起動ではGeyser未起動時だけ生成ウィンドウを開き、Geyser稼働中のGeyserExtra単体reloadではウィンドウを開かない。
- `plugin.yml` でGeyser-Spigotを `softdepend` と `loadbefore` の両方に指定していた矛盾を解消し、GeyserExtraが先に同期パック生成を完了する順序へ統一した。
- joinスキャン後はZIPを変更せず、アイテム・スカルのレジストリJSONだけを非同期保存する。保存処理を共通ロックで直列化し、定期保存・停止保存との同一ファイル競合を防止した。
- 生成ウィンドウの通常終了・例外終了と、Geyserに対するロード順を検証する回帰テストをRED-GREENの順で追加した。
- READMEに、遅延検出した新規アイテムは次回サーバー再起動で反映されること、およびPDC警告の素材数は現在の衝突数であってAPI上限ではないことを追記した。
