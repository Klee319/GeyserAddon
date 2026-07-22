# Task

Bedrock版の3Dカスタム武器における一人称pivotずれと、同一ベースMaterial間で同期するインベントリCTチャージゲージを修正した。併せて、Geyser公開APIでは利用できない `BLOCK_PLACER` の登録試行と誤った更新案内を削除した。

# Files Changed

- `core/build.gradle.kts`
- `core/src/main/java/com/geyserextra/core/util/CustomItemCooldownGroups.java`
- `core/src/test/java/com/geyserextra/core/util/CustomItemCooldownGroupsTest.java`
- `extension/src/main/java/com/geyserextra/extension/handler/CustomItemsHandler.java`
- `paper/src/main/java/com/geyserextra/paper/GeyserExtraPaper.java`
- `paper/src/main/java/com/geyserextra/paper/listener/CooldownBridgeListener.java`
- `paper/src/main/java/com/geyserextra/paper/listener/CooldownMappingSelector.java`
- `paper/src/main/java/com/geyserextra/paper/pack/BedrockAttachableWriter.java`
- `paper/src/main/java/com/geyserextra/paper/pack/BedrockGeometryConverter.java`
- `paper/src/test/java/com/geyserextra/paper/listener/CooldownBridgeListenerTest.java`
- `paper/src/test/java/com/geyserextra/paper/pack/BedrockAttachableWriterTest.java`
- `README.md`

# Details

## 一人称3Dモデル

Rainbow由来の一人称animation値は正しかったが、モデルを保持するboneのpivotが全モデル共通の `[0,8,0]` だった。変換後cube boundsの中心を算出し、3Dモデル専用leaf boneへ設定した。Golden Great-Axe相当モデルでは、bounds `min[-4.5,0,-4.5]` / `max[8,0.5,8]` からpivot `[1.75,0.25,1.75]` を得る。三人称は既存のroot→x→y→z chainを維持し、専用leaf boneが親変換を継承するため既存姿勢を変更しない。

## CTチャージゲージ

従来は全マッピングが `minecraft:<base material>` をBedrock cooldown categoryとして共有していた。各マッピングへ `geyserextra:<mapping name>` を割り当て、`PlayerItemGroupCooldownEvent` で発生した素材CTをBedrockプレイヤーの使用アイテム固有groupへミラーする。サーバー側の元CTは変更しない。

両手に同一素材の異なるCMDアイテムを持つ場合は、`PlayerInteractEvent` で記録した実際の使用アイテムを一度だけ優先する。素材と関連付けられない独自plugin groupは誤表示防止のため推測でミラーしない。

## BLOCK_PLACER

Geyser v2公開APIの `BLOCK_PLACER` はnon-vanilla item専用であり、バニラ派生definitionへの登録は現行Geyserでも意図的に拒否される。登録試行と「Geyser更新で復旧する」という警告を削除し、生成済み平面アイコンへ常時フォールバックする。

## Verification

- 一人称pivotテストを実装前に実行し、固定pivotを検出して2件RED。
- cooldown group utilityテストを実装前に実行し、未実装でRED。
- cooldown bridgeの選択テストを実装前に実行し、未実装でRED。
- 同一素材を両手に持つケースと無関係な独自groupのテストを追加し、旧選択順で2件RED。
- 各実装後に対象テストをGREEN化。
- `gradlew.bat clean build --console=plain`: 成功。
- 最終 `test :paper:build :extension:build`: 成功（47 tests、失敗0）。
- 独立レビューでHIGH/MEDIUM残存なし（ACCEPT）。
- Paper/Extension JARをTrinityForgeへ配置し、ビルド成果物とのSHA-256一致を確認。
