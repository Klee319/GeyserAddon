# Task

姿勢変更が Bedrock に届かない件について、Geyser が Define 時点のパックを送り続ける問題を直し、Paper/Extension JAR を TrinityForge へ配置した。

# Files Changed

- `extension/.../GeyserExtraExtension.java` — `SessionLoadResourcePacksEvent` で pending 昇格 → 旧 UUID unregister → ディスクから再 register
- `paper/.../AutoBedrockPackBuilder.java` — pack UUID `…5e70` → `…5e71`（module `…678a` → `…678b`）
- `README.md` — セッション時再登録の説明を追加

# Details

## 原因

- サーバー上の ZIP / config は更新済みでも、Geyser が `GeyserDefineResourcePacksEvent` 時に載せた内容を送り続ける
- `/geyser reload` だけではクライアント表示が変わらないことがあった

## 対策

1. 接続ごとの `SessionLoadResourcePacksEvent` でディスクから再バインド
2. UUID バンプでクライアント側キャッシュを強制破棄

## デプロイ

- bak: `20260725_101626`
- `plugins/geyserExtra-1.0.0-SNAPSHOT.jar`（SHA-256 一致）
- `plugins/Geyser-Spigot/extensions/extension-1.0.0-SNAPSHOT.jar`（SHA-256 一致）

## 次の操作（未実施・配置のみ）

- サーバー再起動、または Geyser 再読込後に Bedrock を完全終了して再接続
- 再接続後、active pack の UUID が `…5e71` になっていることを確認
