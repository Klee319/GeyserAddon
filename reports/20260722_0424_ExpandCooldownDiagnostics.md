# Task

CT 診断ログが出力されない原因を切り分けるため、cooldown bridge の入口診断を拡張。

# Files Changed

- `paper/src/main/java/com/geyserextra/paper/listener/CooldownBridgeListener.java`
- `reports/20260722_0424_ExpandCooldownDiagnostics.md`

# Details

- `PlayerItemGroupCooldownEvent` のログを mapping 検出有無に関係なく出力するよう変更。
- Bedrock プレイヤーの左クリック、腕振り、近接ダメージ入口で scanner の認識結果を出力。
- 空振り時にも直近 mapping を記録できるよう `PlayerAnimationEvent` を追加。
- `:paper:build` の全テスト成功を確認。
- TrinityForge の Paper プラグイン JAR をバックアップ後に差し替え、SHA-256 一致を確認。
- README の仕様記述との矛盾はなく、更新不要と判断。
