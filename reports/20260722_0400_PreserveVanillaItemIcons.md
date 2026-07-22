# Task

カスタムテクスチャ未設定のアイテムが missing-texture 表示になったり、ブロックの面テクスチャをアイコンとして表示したりする回帰を修正した。

# Files Changed

- `paper/src/main/java/com/geyserextra/paper/pack/AutoBedrockPackBuilder.java`
- `paper/src/main/java/com/geyserextra/paper/pack/BedrockVanillaTexturePaths.java`
- `paper/src/test/java/com/geyserextra/paper/pack/BedrockVanillaTexturePathsTest.java`
- `extension/src/main/java/com/geyserextra/extension/handler/CustomItemsHandler.java`
- `README.md`
- `reports/20260722_0235_FixFpCtTransparency.md`
- `log/BugAndFix/20260722_0202_CustomItemFpCtTransparent.md`

# Details

## Root Cause

前回、カスタム PNG がない block ベースの custom item に `textures/blocks/<bedrockBlockId>` を割り当てた。しかし、このパスは通常の3Dブロックアイコンではなく terrain atlas の一面であり、存在しないパスは missing-texture 表示になる。また、平面バニラアイコンを解決できない通常アイテムでも、Extension が独自 icon key を強制していた。

## Fix

- block ベースには terrain texture alias を生成しない。
- Paper が現在の auto pack に実際に格納したカスタム icon key を `block_icon_bases.json` の `customIcons` として Extension へ共有する。
- Extension は、明示 icon または現在の pack に存在する authored icon があれば custom definition を登録する。
- カスタム PNG がなくても安全な平面バニラ texture path がある通常アイテムは、従来の alias 方式を維持する。
- block ベース、dye array 等の安全な平面 icon を解決できないアイテムは custom definition を登録せず、Geyser の元の vanilla base-item mapping に委ねる。

# Risks

カスタム定義を登録しない fallback アイテムは、mapping 固有の Bedrock cooldown category を持たない。その代わり、アイコン・手持ち表示・ドロップ表示はバニラアイテムと同じになる。カスタム PNG が追加されれば次回フル再起動時に custom definition 登録へ戻る。

# Verification

- `gradlew.bat clean build --console=plain`: BUILD SUCCESSFUL
- Paper/Extension の全テスト成功
- README を新しい fallback 仕様へ同期
- TrinityForge へ Paper / Extension JAR を再配置し、ビルド成果物との SHA-256 一致を確認
- 旧 JAR バックアップ: `*.bak_20260722_040013`
