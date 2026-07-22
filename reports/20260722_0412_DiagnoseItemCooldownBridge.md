# Task

Bedrock カスタム武器で、エンダーパールと同種のアイテム使用クールダウン表示が反映されない経路を診断するため、Cooldown Bridge の境界ログを追加した。

# Files Changed

- `paper/src/main/java/com/geyserextra/paper/listener/CooldownBridgeListener.java`
- `log/BugAndFix/20260722_0202_CustomItemFpCtTransparent.md`

# Details

TrinityForge の `CombatListener` bytecode を確認し、武器CTは攻撃速度ではなく `Player#setCooldown(ItemStack, ticks)` で設定されていることを確認した。TrinityForge の攻撃listenerは `HIGH`、GeyserExtraの使用アイテム記録は `LOWEST` のため、記録順は正しい。

原因箇所を確定するため、カスタムアイテムに関係する cooldown event について以下を INFO で出力する。

- Bedrock プレイヤー判定
- source group と ticks
- recent / main / off mapping
- selector の選択結果
- synthetic `geyserextra:*` group へのミラー
- nested synthetic event の再発火

ログ接頭辞: `[CooldownBridge:diagnostic]`

# Verification

- `gradlew.bat clean build --console=plain`: BUILD SUCCESSFUL（22 tasks）
- TrinityForgeへPaper/Extension JARを再配置しSHA-256一致を確認
- 旧JARバックアップ: `*.bak_20260722_041231`
