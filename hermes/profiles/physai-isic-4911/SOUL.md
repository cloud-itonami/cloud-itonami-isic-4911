# physai-isic-4911 — 都市間旅客鉄道業（ISIC 4911）のロボットの physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isic-4911`、ISIC Rev.5 4911 都市間旅客鉄道業）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README の Robotics premise: ロボット（軌道検査・車両保守・信号系の試験）が物理作業を行い、actor が提案し独立した Rail Safety Governor が止める。
その物理的な仕事を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:inspection-trolley-stop` | transport | 軌道検査トロリーが 400 m の検査区間を 5 m/s で走り、支障物で停止する（レールの粘着＝乾燥〜落葉で制動減速度が変わる） | 停止距離 | 20 m（estimate） |
| `:brake-block-swap-on-bogie` | manipulator | 検修ピットからアームが新しい制輪子を台車のブレーキヘッドへ持ち上げる | 肩関節ピークトルク | 100 N·m（estimate） |

測定の入口: `kbb -M:dev:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:dev:physai-test`（`test-physai/railops/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する。
この repo 自身の `test/` の `.cljk` も同じ runner で走る: 35 tests / 162 assertions）。

## 測って分かったこと・限界（成長の第一候補）

1. **検査トロリーの停止**: 5 m/s からの停止距離は制動 0.3 m/s² で 41.7 m、0.5 で 25.0 m（ともに範囲外）、0.8 で 15.6 m、1.2 で 10.4 m、2.0 で 6.25 m。
   限界 20 m を守るのに要る減速度は **0.625 m/s²**。落葉などで粘着が落ちた区間では、検知距離を延ばすか速度を下げる必要がある。
   この停止距離は制動開始からの距離で、検知から制動までの空走距離を含まない（含めれば境界はさらに厳しい）。転倒余裕は 2.0 m/s² でも 0.83 で効いていない。
2. **制輪子の交換**: ピットから上向きに持ち上げるので肘の負荷も大きい。肩トルクは 3 kg で 59.2 N·m、8 kg で 93.5 N·m、11 kg で 114.1 N·m（範囲外）、14 kg で 134.7 N·m（肘 72.1 N·m）。
   限界 100 N·m に達する質量は **8.94 kg**。重い鋳鉄制輪子はこのアームでは扱えない。
3. **estimate のままの値**: 停止距離 20 m（トロリーの検知距離。障害物検知装置の仕様で置き換える）、レール状態ごとの減速度（鉄道の粘着係数の文献値で置き換える）、
   肩トルク上限 100 N·m（協働ロボットの仕様書で置き換える）、制輪子の質量（車両メーカーの部品仕様で置き換える）、トロリーの質量・駆動力、アームの寸法・質量。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種のロボットがする別の物理的な仕事を 1 case 足す（例: 勾配区間の検査トロリー、レール鋼の引張試験、ブレーキディスクの温度上昇）。
   `:kind` は :transport / :manipulator / :material / :thermal / :tank-drain / :pipe-flow。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isic-4911 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:dev:physai-test → kbb -M:dev:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isic-4911 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
