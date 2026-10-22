# 交接：Operations（`econ/fastest-build-order` 分支），2026-10-20

接手的人（或 AI）先读这一份，再读 `docs/PLAN.md` 的 §26–28 和 `records/2026-10-20/user-messages.md`（用户的全部原话，按时间）。整段对话（用户的话、Claude 的回复、中途的上下文摘要）在 `records/2026-10-20/chat.md`；这几天所有测试日志在 `records/2026-10-20/build-logs.tar.xz`（`tar -xJf` 解开就是原来的 `build/...`），各批的结果表在 `records/2026-10-20/summaries/`。

## 1. 这是什么

Mindustry 模组 "enemy expansion"。这个分支做 **Operations**：一套基于优化的阵营 AI——经济规划（最快造出目标单位、此后满效率）、布局与执行（核心机一块块去建）、防御规划、控兵层、指挥层。

**Brain** 指另一个分支（main / `brain/planned-opening`）的规则式 AI。它只作参考和对照基线，不合并进来；用户认为 Operations 的潜力大得多。

## 2. 用户要什么（四条线）

用户的真实目的：既能当敌方 AI（玩家开局有基地时给足挑战），又能在随机地图上像 PvP 一样对打，又能像玩家一样打通正常关卡，还能看两个 AI 对弈。

- **线 1：Operations 作为玩家打原版困难战役**（对手是游戏自己的敌人，不带 Brain）。基线是 Brain 站玩家一方；用户说旧 Brain 这些图都能过，预期 Operations 很快超过它。要研究压力曲线怎么定、出兵和建造的权重怎么调。
- **线 2：公平规则下 Operations 对 Brain。** 先对纯规则 Brain；轻松赢了就换成调用我们规划器开局的 Brain（`-Dmatch.brainopening=true`）；那个也赢了，线 2 暂停，等用户亲自来打。
- **线 3：沙盒爬科技。** 地图上矿种齐全、没有敌人、从正常的降落物资起步（直接给物品只用于快速测试），一直爬到魔灵（malign）、天谴（scathe）、泰坦（titan）、劫难（afflict）建好能开火，以及三种 T5（conquer、collaris、disrupt）；全部要能供上电。
- **线 4：敌方 AI 带现成基地**——先不管。看两个 AI 对弈也放到以后。

用户的路线图（PLAN §27 有详细版）：
1. 升级计划：钻头增产（臭氧、氢气）、高价值的加速等离子钻；缺铍图上用地面矿；相织布加热造碳化物（省地）；各种发电。
2. 高科技布局以蓝图为主，可以试设计算法。
3. 执行器：频繁被打时别盲目修补导致死锁（参考 Brain 分支的修补）；核心机能修建筑；核心机的其他用法——挖矿、搬箱子或管道吸引炮塔火力、搬臭氧罐到敌方炮塔旁炸掉、搬容器加装卸器给前线临时炮塔供弹、搬水箱给钻头和炮塔加速、临时生产。质量驱动器配构筑器/解构器，或配装载器/卸载器加容器。
4. 防御：每种炮塔的性价比和用途；弹药随局面升级（撕裂：铍→钨；升华：臭氧→氰；石墨少的图上扩散用硅）。
5. 控兵：尽量聪明。
6. 指挥层：非常重要。
7. 视野：Operations 可以看全图，不管迷雾；指挥层直接读敌方基地，不用侦察。但"玩家队伍不能在没探索过的地面建造"这条游戏规则照旧。

## 3. 长期规则（用户定的）

- 通用代码，不写针对某张图、某种情况的特化代码。
- 少开子代理（额度烧得快）。能自己做的自己做。
- **公平规则**：所有建造都由核心机完成，像玩家一样飞到建造半径内才能开工，建造速度与玩家相同（不能瞬间建成）。`Match` 开局打印 "building:" 一行可以核对；`GameData.place`、`allGround`、`even()` 里的瞬间摆放只是测试夹具。
- 只提交、推送到 `econ/fastest-build-order`。不合并到 main，不开 PR。
- 第三方蓝图不提交（许可证）：`reference/schematics/`、`blueprints/modules/*.msch` 都在 `.gitignore` 里。
- 用中文简短汇报，先说结果。
- 长时间跑的测试从类文件快照跑（`test/matches.sh` 自己会复制到 `build/match/snap-$TAG`），这样边跑边改代码不会让测试崩。
- 杀进程用 `ps | grep | awk | xargs kill`，别用 `pkill -f`（会把自己的 shell 杀掉）。
- 并行的局数不超过物理核数（留两个核）：Brain 每帧的思考上限按墙钟算，机器一忙它就变弱。

## 4. 代码在哪

- `src/expansion/econ/`：经济与执行。
  - `Recipes`、`Catalog`：从游戏读配方和造价。`Sites`、`Field`：地图上能放什么、产量多少。`Chains`：每单位产量的成本（Bellman-Ford）。`Planner`、`Timeline`：配平和建造顺序。
  - `Plan`：整条流水线，入口是 `make(...)`；`gate`：按"离现有产出几步"分层尝试，取能做完的、第一个单位最早的；`next(...)`：爬科技时的下一步。
  - 布局：`Grid`、`Packing`（钻头的最大权独立集）、`Cells`（模拟退火放工厂）、`Router`（协商拥塞布线）、`Wiring`（光束节点上的 Steiner 树，以及补电的 `mend`）、`Layout`。
  - 执行：`Hand`（核心机控制器：建造顺序、存钱、挖矿、补修、搁置）、`Watch`（损坏与补修）。
  - `Defence`：对接防御规划。
- `src/expansion/defense/`：防御规划器（按最坏一波定规模、炮塔位、墙、供弹）；`Tech` 是每个关卡的炮塔科技表。
- `src/expansion/army/`：控兵层，叠在游戏自带的 RtsAI 之上。
  - `Threat`、`Allot`：兰彻斯特估算。`Push`：集结后成波进攻、本地守卫、只走得到的区域、先打炮塔/工厂/核心。`Reach`：单位能不能走到（含会不会淹死在深水和熔渣里）。`Bait`、`Dodger`、`Muster`（自动选兵种和工厂数）、`Creep`（炮塔推进，目前关着）。
- `src/expansion/` 根目录：Brain 和模组本身（另一分支带来的，作对照）。`Opening.java`：Brain 调用我们规划器的开局。
- `test/src/xtest/Match.java`：主测试脚手架，一整局 Operations 对 Brain 或原版敌人，见第 7 节。
- 其他测试：`EconRun`、`EconLab`（经济基准）、`Campaign`（Brain 分支的战役测试）、`BrainDuel`、`ResourceScan`（随机图的资源普查）、`DefenseRun`、`TurretLab`。
- `blueprints/`：Brain 的蓝图 `*.txt`；我们自己的模块 `own/`、炮塔位 `posts/`、兵工厂 `plants/`。
- `docs/PLAN.md`：设计与实验的全记录（§26 对战，§27 路线图，§28 迁移与现状，§9 踩过的坑）。

## 5. 现状（2026-10-20）

- **建造公平性**：已核对。我方 1.00×（队伍和规则的倍率都是 1，核心机自身 1.2，和玩家开同一台核心机一样）；核心机只在建造半径内开工（`Hand`）。
- **线 2**（batch `l2a`：纯规则 Brain、最强设置、even 模式、25 分钟、20 张图）：**胜 7、负 9、平 4**，还没到"轻松超过"。
  - 胜：onset、aegis、marsh、crossroads、origin、stronghold、gen:2。负：atlas、basin、caldera、intersect、lake、gen:3–6。平：split、peaks、siege、gen:1。
  - gen:3–6 都是 6–8 分钟被 3–5 台 stell 拆核心，我方 0 兵、0 炮塔：第一阶段的计划太大（24–32 台机器），铍一直紧，第一台 stell 到 7 分钟还没出；Brain 5 分钟就有兵。
  - caldera、basin：执行器为一步缺硅的工序死等（硅挖不了，硅炉排在后面）。已加"缺口 60 秒不缩小就先放一边、做下一步"（`Hand.putOff`），**还没在对局里验证**。
  - basin：对方当时没兵，防御规划就放 0 座炮塔。应该按对方的产能（兵营、工厂、资源）预测，而不是看当下兵力。
- **线 1**：基线还没成立。
  - 之前的战役模式科技表里没有炮塔，已改成 `xtest.Campaign` 的研究方式。
  - 改完后 Brain 在 onset 仍然 2:15 被 8 台 stell 拆核心。Brain 分支自己的测试里拿下过 onset，那边开着迷雾。新加了 `-Dmatch.fog=true`，未验证。
  - Operations 的战役批次还没跑。
- **线 3**（`climb-40d`：gen:40、`allground`、90 分钟）：爬到钨、氧化物；钍那一步的电解槽一直存不够钨，钍始终是 0。所以碳化物、氰、泰坦（要 400 钍）、三种 T5 都够不到。劫难要浪涌合金，"floor ore 上没有空位"。
- **经济基准**（first7）：7 张图都在模型时间的 90% 以内拿到目标单位（93%–119%）。onset 的计划比参考版本大（27 台机器，模型 4:06；参考是 15 台、3:36），原因未查。
- **速度**：Match 里游戏帧约是实时的 80 倍，规划占大头（每阶段约 20 秒）。一局只用一个线程；服务器的多核用来并行跑批，显卡没用。

## 6. 下一步（按这个顺序）

1. **搭环境、验证**：`sh tools/server-setup.sh`。
2. **验证 `Hand.putOff`**：`TAG=v1 JOBS=3 sh test/matches.sh 25 even caldera-erekir basin intersect`。看日志里的 "put off"，看第一个单位是不是出来了。
3. **线 2 的两个通用改进**，然后重跑 l2a 的 20 张图，与 7/9/4 对比：
   - 最低防御按对方产能预测：对方能造兵（有工厂，或有资源和时间造工厂），在它第一批兵能到之前放一两座撕裂。也就是压力曲线：对方兵力和产能随时间的估计，对比我方防御和兵力的容量。
   - 开局阶段的计划先小后大：第一个兵尽早出来，再扩。查清为什么 gen:3–6 第一台 stell 要 7 分钟以上。
   - 线 2 轻松赢了（比如胜率 ≥ 80%、不输）：修好 `Opening` 在 even 模式下的 "no way to make stell"，换成 `-Dmatch.brainopening=true` 再打。也赢了就暂停线 2，向用户汇报，等用户手动对打。
4. **线 1**：
   - 先跑 Brain 基线：`TAG=l1brain-fog OPTS="-Dmatch.us=brain -Dmatch.fog=true" sh test/matches.sh 60 campaign onset aegis lake intersect atlas split basin`。用户说 Brain 能过这些图；对不上就找 Match 和 `xtest.Campaign` 的差别。
   - 再跑 Operations：`OPTS="-Dmatch.fog=true"`，同一组图。迷雾下核心机只能在探索过的地面建造，看 Operations 能不能照常铺开。
   - 然后按压力曲线调出兵和建造的权重。
5. **线 3**：钍那一步为什么没钨（电解槽存钱卡住、钨钻头产量不够，还是被别的阶段吃掉了），之后是碳化物、氰、相织布、浪涌合金，还有"floor ore 上没有空位"。命令见第 7 节。
6. 路线图里的其余项（升级、弹药升级、容器、修补、指挥层搬进模组、异步规划），见 PLAN §27。

每做完一块：在 PLAN.md 里记一笔，提交并推送，用中文向用户简短汇报。

## 7. 怎么跑

- 构建：`./test.sh build`。
- 单局：`./test.sh match <图|gen:种子> <分钟> <sector|even|sandbox|campaign>`；日志在标准输出，错误在 `build/match-err.log`。
- 批量：`JOBS=n TAG=名字 STAGES=... OPTS="..." sh test/matches.sh <分钟> <模式> [图...]`。日志在 `build/match/$TAG-<图>-<模式>.log`，结果表在 `build/match/summary-$TAG-<模式>.txt`，最后一行是 TOTAL。
- 模式：
  - `even`：每方一个核心、物资相同，地图上其余全清掉。
  - `sector`：Brain 带地图给的基地，我方从降落核心起步。
  - `campaign`：原版敌人、困难，按战役判胜负；`-Dmatch.us=brain` 让 Brain 站我方当基线。
  - `sandbox`：没有敌人。
  - `gen:N`：游戏生成器按种子造的图，每方一个核心。
- 常用选项：
  - 我方的阶段：`-Dmatch.stages=...`，默认 `stell:2|merui:2|elude:2|avert:1,defend,locus:1|...`，也可以写 `climb:<目标>`、`defend`；`-Dmatch.auto=true` 由 `Muster` 自己选。
  - 对手的设置：`-Dmatch.plain=true`（默认设置的 Brain）、`-Dmatch.brainopening=true`、`-Dmatch.brainarmy=true`、`-Dmatch.grace=`。
  - 规则与地图：`-Dmatch.difficulty=`、`-Dmatch.fog=true`、`-Dmatch.allground=true`（缺的矿种在核心旁补一块）、`-Dmatch.cap=true`。
  - 记录：`-Dmatch.save=<目录>`（每阶段存档）、`-Dmatch.prof=`。
  - 模组设置钉死：`-Dxp.pin.<设置名>=值`。
- 线 3 的爬科技：
  ```
  java -XX:+UseSerialGC -Dmatch.allground=true "-Dmatch.stages=stell:2,climb:conquer,climb:collaris,climb:disrupt,climb:malign,climb:scathe,climb:titan,climb:afflict" -cp ../Mindustry.jar:build/classes:build/test-classes xtest.Match build/test-data gen:40 90 sandbox
  ```
  长时间跑时先把 `build/classes` 和 `build/test-classes` 复制一份，从副本跑。
- 经济基准：`./test.sh econbench <并行数> first7|all <种子数>`，结果在 `build/bench/summary.txt`。
- 随机图资源普查：`java -cp ../Mindustry.jar:build/classes:build/test-classes xtest.ResourceScan build/test-data 1 200`。

## 8. 任务表（会话里的）

已完成：
- 机制核对，配方读取，地块与产量，抽象经济模拟，规划器与建造顺序，与旧 AI 的比较，执行器，核心机控制器，模型与游戏的差距测量。
- L2 共享网格，钻头排布（MWIS），工厂摆放（退火），布线（协商拥塞），供电（Steiner 树），布局编排与静态检查。
- 建造与监视，在 aegis、atlas 上端到端跑通；模型校准；多图基准。
- 炮塔推进，防御规划接入实战，开局防御，反骚扰，兵力与产能。

进行中：
- 蓝图实验台与模块库。
- 整局对战。

未做：
- 模块、载荷、装配厂作为配方进模型；布局时模块作为带端口的刚性单元。
- T3–T5 兵种链（钍、氮、氰）。
- 炮塔、电厂、浪涌传送带、质量驱动器。

## 9. 线三：布局生成器 bp/ 与生长式布局（2026-10-22）

`bp/src/bp/` 是独立于模组的规划与布局生成器（用 OR-Tools，跑在服务器上），线三"最终形态"由它完成：官方 Erekir 生成器随机图（只补了约定的缺失矿：`Terraform`；钍按生成器方式补一片墙上钍矿：`bp.Terraform vein`），从落地物资（铍、石墨各 300）起步，核心机按玩家速度建造，目标是每种可建方块满效率运转、每种炮塔弹药各一、电力富余 10 万。

- 终局计划：`bp.Master plan`（`Final.onWorld` + `Balance`，SCIP），写成 `.plan` 文件。
- **生长式布局（现行架构）**：`bp.Grow` 一次走一步——
  1. 滚动排程（时间分段 MILP，段长翻倍覆盖整个建造期；只有第一段取整数，其余是连续松弛）：部件分"类"，终局要求每类多少个；可选的自举部件（任意产出物品/液体/电力的机器、每种地面配方最近的若干矿点）只有能让终局更早完成时才会被选；需要热的机器可以换任意一种加热器；可选部件能拆除（按规则返还一半）；同一块地/光束通道同时只能一个占用者。目标：完工时间 + 剩余建造量的时间积分。
  2. 只对第一段的增量布局：已承诺的东西作为固定体，新线从已有机器的空闲侧接出；放不下或连不上的部件剔除后重排。一步通过与否只看这一步自己的问题（新机器、新线），旧的问题不挡新步。
     - 流量按游戏的轮流分发建模（`Building.dump`：机器轮流往每个肯收的邻居放）：机器若有通往核心的线，核心线拿到的不少于它的任何其他线，剩下的全进核心；已有核心线的机器不再加第二条（`Gen.nets`，有核心时用 SCIP）。
     - 已建成的线路在路由器里是普通线路（`Router.Standing`：运什么、送给谁、现运量、容量）：新线可以并入同物同去处的旧线；只通往核心的物品线可以混运任何物品；碰到去别处的线算漏，桥接到别的桥一律算漏（会占住那一侧入口）。
     - 账本记每个物体要保持畅通的格子（钻头光束、区域），后面的步骤不会在上面放实心块；id 永不重复；被保留的线所连的机器不会退回规划。
  3. 按游戏规则校验（`Check`，含每个方块能否在原地建造）。
  4. 写账本（`ledger.txt`：每个已承诺的物体与线路、线路运什么、各机器已分配的流量）和建造单（`orders.txt`）。
- 执行：`xtest.Climb`（游戏里，核心机建造/挖矿/拆除），建造单做完就写状态、调一次 `bp.Grow`（子进程，期间游戏暂停）。
  ```
  java -cp "../Mindustry.jar:build/classes:build/test-classes:$(cat build/bp-classpath)" xtest.Climb build/test-data build/bp-maps/gen-40-th.msav build/bp-maps/gen-40.plan build/bp-out/grow-40 <分钟> 12 90 60
  ```
- 弃用：`bp.Master layout`（一次性组合整张终局图：177 个体放置 640 s、布线 1090 s 仍有 387 格冲突，不收敛）。`Modular` 的模块仍可用于小问题。
- 回归：`sh bp/regress.sh`（整块模式和模块模式各几个测试题）。
