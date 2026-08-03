# Task

ユーザー要望どおり FP display scale を Java 値に戻し、斧が後ろに寄った原因の平行移動 ×0.25 は 0 に戻して配置した。

# Files Changed

- `paper/.../BedrockAttachableWriter.java`
- `core/.../GeyserExtraConfig.java`（javadoc）
- `paper/.../BedrockAttachableWriterTest.java`
- `README.md`
- `log/BugAndFix/20260724_1709_ItemHoldPoseWrong.md`

# Details

## 条件（実験フラグ true）

- FP translation = `[0,0,0]`（×0.25 は斧を再び画面外へ寄せたため撤回）
- FP scale = Java のまま（大斧 `1.7` / モーニングスター `0.68`）

## パック検証

- greataxe: pos `0` scale `1.7`
- morningstar: pos `0` scale `0.68`

## 補足

モーニングスターの「小さめ」は Java 側 display scale が元々 `0.68` のため。Java に合わせるとこの大きさになる。
