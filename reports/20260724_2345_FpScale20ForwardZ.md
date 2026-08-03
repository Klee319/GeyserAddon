# Task

Java 比でサイズ約1.5倍・大斧をもっと前へ、というフィードバックに対し FP base を更新して**配置のみ**（サーバ未起動）。

# Changes

- `firstPersonBasePose.scale`: `1.3 → 2.0`（全体 ≈1.5×。Java 印象の 2/3〜1/2 を埋める）
- `firstPersonBasePose.position`: `[4,10,2] → [4,10,-2]`（Z 下げ＝前。過去実験と整合。display 平行移動は大斧を悪化させるため使わない）
- コード既定 / README / live config / JAR 配置
- **サーバ再起動なし**（ユーザー側で起動時にパック再生成）

# 根拠・汎用性

- サイズ不足は全アイテム共通の root scale で対処（アイテム別なし）
- 大斧の「先だけ」は前方向不足。MS 位置は良いが、display 平行移動の復帰は EXP-C で大斧を悪化させたため、**共通 base の Z** のみ前寄せ
- 旧 root 1.5×display 合成の失敗とは別（今回は translation 0 維持 + root 2.0）
