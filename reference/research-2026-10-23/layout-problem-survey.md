# 布局问题的抽象，以及学界和工业界的同类问题与算法（2026-10-23）

起因：生长式布局每一步都对新机器做一次全局的摆放（模拟退火）和布线（协商拥挤），越建越慢、越建越乱，放不下就整步放弃。用户的看法："作为人类，我会先建着，有问题的改一改就好，实在空间不够就想办法扩大点空间，从来没有问题。"这份调研只回答三个问题：我们的问题抽象出来是什么；学界和工业界有没有同样的问题、用什么算法；有没有能大幅缩小搜索空间的启发式。不涉及实现。

## 0. 结论先说

1. **这是几类已知的 NP 难问题叠在一起**：不等面积设施布局 + 不可分流的多商品流布线 + 多期（动态）布局 + 资源自举的建造调度。单拿哪一类出来，工业界都不追求全局最优解。
2. **成熟的做法在各个领域惊人地一致**：芯片物理设计、工厂布局、化工/船舶管道、工厂类游戏的高手玩家，都靠同样四件事：
   - **规整的骨架**：行、跨、通道、管廊、总线、街区。它把二维的布线降成近似一维的问题。
   - **复用的模块**：标准单元、宏块/IP、标准化工艺模块、蓝图。
   - **先粗后细的分层**：块布局 → 详细布局；全局布线 → 详细布线。
   - **增量修改加局部修补**：芯片里的 ECO、计划修补、最小冲突、大邻域搜索。只动出问题的一小片，不重解全局。
3. 我们现在每一步都做全局式搜索，却没有骨架、不用模块，失败了也不做局部修补而是整步放弃。这正好和成熟做法相反。用户描述的人类做法——先建着、有问题局部改、不够就扩——在学术上有名有姓（ECO、计划修补、最小冲突、LNS），也是工业界的主流。
4. **精确求解只能到很小规模**。Factorio 蓝图的约束模型，约 100 格就要几分钟，再大就解不动；作者自己的建议是"每种中间品一个可平铺的蓝图，再模块化地拼"。芯片上的强化学习（AlphaChip）在公开复现里也没有胜过模拟退火和商业工具。

## 1. 把我们的问题抽象出来

**给定**
- 一张网格地图（150×150，可建 13,378 格）：墙、深水不可建；矿、液体地面、喷口只在特定格子上。
- 一个目标生产系统（终局）：机器种类和台数（164 台机器、68 个矿点、约 40 种单件），以及机器之间的物料流（每秒多少）。
- 一个建造者：速度固定，只能花核心里的材料；材料来自已经建好的生产，所以是自举的。

**要决定**
1. **摆放**：每个东西放在哪、朝哪。尺寸 1×1 到 5×5；有的要贴着附属件（加热器整面贴、导热器）；有的只能在矿、液体地面、喷口上（锚定）；有的格子要留空（钻头的光束、单位出口）。
2. **布线**：每条物料线走哪些格。线占格、有容量（导管每秒约 15 件）、有方向；跨越只能靠桥（4 格）；不同物料的线不能以会混货的方式相接；机器向所有相邻的接受者轮流出货。
3. **供电**：电力节点连成一张网，激光射程有限，是一棵斯坦纳树。
4. **时间**：按什么顺序建。资源随建成的生产增长，可以拆（返还一半）。

**目标**：最快把终局建成，且每台机器满效率。

**它是哪些已知问题的组合**

| 部分 | 对应的已知问题 | 复杂度 |
|---|---|---|
| 摆放 | 不等面积设施布局（UA-FLP），带进出口位置、锚定位置、禁建区 | NP 难；精确方法只能解小规模（[Anjos & Vieira 2017 综述](https://www.gerad.ca/fr/papers/G-2016-05)） |
| 布线 | 多条线争同一片空间 = 不可分流的多商品流 | NP 难（[管道布线综述](https://www.math.vu.nl/~mei/articles/2023/survey.pdf) §4.2） |
| 随时间生长、可拆改 | 动态（多期）设施布局 DFLP，带重排成本 | NP 难（[综述 1](https://cjme.springeropen.com/articles/10.1007/s10033-017-0073-9)，[综述 2](https://laccei.org/LACCEI2020-VirtualEdition/full_papers/FP505.pdf)） |
| 建造顺序 | 资源受限、资源自举的调度（RTS 建造顺序，BOSS） | 见 `docs/COMMAND.md` |
| 供电 | 斯坦纳树（带射程的几何版本） | NP 难，有好的近似（我们用 Takahashi–Matsuyama） |

**难点在耦合。** 一条线放下就成了下一条线的障碍，n 条线就有 n! 种先后顺序（管道综述原话：每根管道都是一个动态障碍）。机器放哪又决定线能不能走通。

## 2. 各领域怎么做

### 2.1 芯片物理设计（EDA）：规模最大、最成熟的工业实践

- **流程是分层的**：先做平面规划（大宏块的位置），再做摆放（全局摆放 → 合法化 → 详细摆放），再布线（粗网格上的全局布线 → 细到每条轨道的详细布线），最后用 ECO 做增量修改（[CACM 综述](https://cacm.acm.org/?p=52254)，[Cong & Xie 2006](https://cecs.uci.edu/~papers/aspdac06/pdf/p188_2C-1.pdf)）。
- **标准单元加行式布局**：所有逻辑单元做成同样高度的标准单元，排成行，线只走行与行之间的通道。这样二维布线就降成每个通道里的"伪一维"问题：通道布线没有竖向约束时，左边算法给出最优解（[课程笔记](https://www.seas.upenn.edu/~ese5350/spring2009/handouts/assign6.pdf)，[左边算法](https://sites.lafayette.edu/cadapps/?p=40)）。代价是面积：通道可以占到芯片面积的 70%（[PSU 笔记](https://web.cecs.pdx.edu/~greenwd/std_cell.htm)）。
- **宏块/IP 复用**：大块电路（存储器、处理器核）事先设计、验证好，整块摆放。这就是"蓝图"。
- **摆放算法**：
  - 模拟退火：TimberWolf、FPGA 的 VPR。耗时和单元数成正比，可以按区域并行（[UBC 论文](https://open.library.ubc.ca/collections/24/items/1.0072367)）。
  - 最小割递归二分：Breuer 1977 提出，分而治之，天然容易布通（[Caldwell, Kahng, Markov](https://vlsicad.ucsd.edu/Publications/Conferences/81/c81.pdf)）。
  - 解析式：ePlace、DREAMPlace，可在 GPU 上算，用于百万单元。
  - 平面规划的表示法：序列对、B*-树、切割树。把"二维坐标"压成"排列 / 树"，搜索空间小得多（[表示法比较](https://mathweb.ucsd.edu/~ronspubs/03_03_floorplan.pdf)）。
- **布线**：
  - PathFinder（协商拥挤，FPGA 的标准）先让各条线共享资源，再逐轮提高共享代价。已知的病灶是"顺序依赖、不收敛"（[EPFL](https://memento.epfl.ch/event/towards-scalable-and-holistic-fpga-routing-2)）。
  - 改进办法：每条线先并行生成几棵候选路径树，再用整数规划或模拟退火从中挑一个组合。
  - 全局布线综述的意见：要简单有效就用"拆线重布"；速度要紧就用分层方法（[多线全局布线综述](https://experts.umn.edu/en/publications/a-survey-on-multi-net-global-routing-for-integrated-circuits/)）。
- **增量修改（ECO）**：
  - 目标是"最小扰动"：在原方案上做最少的改动，必要时才重摆一大片（[ECO-system，Markov 等](https://web.eecs.umich.edu/~imarkov/pubs/conf/aspdac07-eco.pdf)）。
  - 合法化：扩散式只在必要处推开单元（[UT Austin](https://www.cerc.utexas.edu/utda/publications/tcad2007DiffusionInLegalization.pdf)）；网络流式让总位移和最大位移最小（[Brenner](https://past.date-conference.com/date15/files/proceedings/2012/pdffiles/11.7_2.pdf)）。
  - 工业界看重这一点的原因：新方案离旧方案越远，引入的新问题越多，流程越难收敛。
- **强化学习**：Google 的 AlphaChip 在 UCSD 的公开复现里没有胜过更强的模拟退火和商业摆放工具，而且耗费资源多得多（[Cheng, Kahng 等 2023](https://arxiv.org/abs/2302.11014v3)）。

### 2.2 工厂和车间布局（工业工程）

- **Muther 的系统布局规划（SLP，1961）** 是工业界最常用的方法（[Muther 公司的指南](https://richardmuther.com/wp-content/uploads/2014/06/RMA%201111%20Procedure%20for%20Making%20Relationship%20Diagram.pdf)，[GT 课程](https://www2.isye.gatech.edu/~mgoetsch/cali/Spiral/Spiral%20HTML%20Help/SystematicLayoutPlanningorSLP.htm)）。四步：
  1. 关系表：哪两个部门要挨近、哪两个不能挨。
  2. 关系图加面积，得到空间关系图。
  3. 块布局：把面积块推到一起。
  4. 详细布局：每台设备用模板放进去。

  本质就是"先粗后细"。
- **数学优化**：综述（[Anjos & Vieira 2017](https://www.gerad.ca/fr/papers/G-2016-05)）把问题分成行布局、不等面积布局、多层布局三类。大多 NP 难，混合整数和锥优化只对中小规模给出最优解或紧的界。
- **灵活跨（FBS）**：部门放进若干条平行的"跨"，跨宽可变，问题就降成"部门的排列 + 每跨放几个"（[综述](https://rcs.cic.ipn.mx/2021_150_3/The%20Unequal-Area%20Facility%20Layout%20Problem_%20A%20Review.pdf)，[ACO + FBS](https://datalearner.com/academic/journal-papers/0957-4174/volumes-and-issues/152/paper-detail/81376)）。和芯片的"行"是同一个思路：用规整结构压缩搜索空间。
- **动态布局（DFLP）**：多期需求变化，每期的物料搬运成本和期间的重排成本此消彼长。两种对策：
  - 动态：每期一个最优布局，付重排成本。
  - 鲁棒：一个布局通吃各期，不重排。

  还有按区域划分的版本（zone-based DFLP），部门尺寸也可以是决策变量（[HKU 报告](https://www.dase.hku.hk/events/the-zone-based-dynamic-facility-layout-problem-date-21-july-2017)）。
- **工业软件和生成式设计**：
  - JGC（工程公司）的 Auto Plot PATHFINDER 用遗传算法加专家规则，把布局探索写成"带面积约束的背包"，在管道成本和占地之间权衡（[AIChE](https://aiche.org/conferences/videos/conference-presentations/auto-plot-pathfinder-can-multi-purpose-optimization-generate-plant-layout-comparable-veteran)）。
  - 其他工作多是遗传算法加人在环（[OFFIS](https://offis.de/offis/publikation/optimizing-factory-layouts-with-supervised-genetic-algorithm.html)，[Fraunhofer 综述](https://publica.fraunhofer.de/entities/publication/34548b0c-4d51-4280-9ccc-584e38590712)）。
- **扩建（棕地）的经验**（[来源汇总](https://ifactoryapp.com/industries/manufacturing-plant/greenfield-factory-layout-design-planning-framework)，[强化学习工厂布局框架](https://link.springer.com/chapter/10.1007/978-3-031-35779-4_8)）：
  - 按当前设备严丝合缝设计的布局，第一次扩产就要大改。所以要预留扩展空间、按增量扩产。
  - 难搬的东西放在主流程外。
  - 改动要排好先后，减少停产。

### 2.3 化工厂与船舶的管道布线

这一行的处境和我们最像（[Blokland 等 2023 综述](https://www.math.vu.nl/~mei/articles/2023/survey.pdf)）：
- 船舶设计中管道布线占工程工时的 50% 以上，化工厂设计中占生产成本的 25%。实践中仍主要由工程师在 CAD 里**一根一根手工布**："正在布的一根挡住了下一根，可能意味着设计的一大部分要改。"
- 综述把障碍归为三类：分支（一根管连三个以上的点）、资源竞争（多根管抢同一片空间）、空间维度（选项太多）。资源竞争等价于不可分流多商品流，NP 难；n 根管有 n! 种顺序。
- **四种常用手段**：
  - **布线顺序**：大管先布、短管先布、密集区先布、预计干涉少的先布。按路径长度和距离排序能把可布通率提高 70% 以上（Jansen 等 2020）。
  - **回溯重布**：拆掉挡路的管道换个顺序重布（Zhu & Latombe 1991）；或者先不管碰撞各自布，再按碰撞次数逐根重布（Ikehira & Kimura 2009）。
  - **候选集合**：每根管先找几条好路线，再挑最好的组合。
  - **宏管道**：可以成组的管道当一束平行走，也就是管廊、总线。
- **分层**：先定中间点（阀门、管廊的位置）再在点之间细布；有人专门优化管廊的位置，让管道都经过它（Roh 等 2007）。
- **模块化化工厂**：德国化工界的路线（[DECHEMA](https://dechema.de:443/dechema_media/modularplants.pdf)、[VDI 2776 / MTP](https://doi.org/10.3390/pr11092733)、[Fraunhofer](https://www.shapid.fraunhofer.de/en/demonstrators/Efficient-Building-Blocks/modular-processes.html)）是标准化的"工艺设备组件"（PEA）加标准接口，像积木一样拼装、可重新排列组合；试点报告工程和调试时间最多省 50%（[ABB](https://www.abb.com/gb/en/company/innovation/news/modularizing-engineering)）。缺点是标准化还不成熟。

### 2.4 工厂类游戏

- **Factorio 人类高手的两大范式**：
  - **主总线**（[官方 wiki](https://wiki.factorio.com/Tutorial:Main_bus)）：最常用的中间品各占一组平行传送带，生产块从总线上分一条出去，产品回到总线。扩展就是往总线上加一种物品；要在物品组之间、生产块之间预留空隙，方便以后插入。缺点：总线越来越宽，要的传送带多。
  - **城市街区**：固定尺寸的网格，格与格之间是铁路（或宽带），扩展就是在旁边再加一块（[讨论](https://steamcommunity.com/app/427520/discussions/0/3489752656780289498)；这一条只有社区讨论，没有权威来源）。
  - wiki 说主总线是用来对付"意大利面工厂"的；玩家讨论里也常见从乱建过渡到总线，再到街区（街区要先有火车）。
- **学术工作**：
  - [Towards Automatic Design of Factorio Blueprints](https://arxiv.org/html/2310.01505v1)（St Andrews，2023）用 Essence Prime + Savile Row 写约束模型。一个统一模型不可行，分成三阶段：配方和机器数 → 装箱 → 布局布线，后一阶段可以否决前一阶段（类似 Benders）。约 100 格的蓝图要几分钟，再大"运行时间急剧增长，很快不可解"；作者明确说整厂不可能这样优化，建议"每种中间品一个蓝图"拼出模块化工厂，并且可平铺的蓝图比最优但难扩展的蓝图更好。
  - [Factorio Learning Environment](https://arxiv.org/abs/2503.09617)（NeurIPS 2025）：大模型的空间推理仍然很弱，复杂的自动化（电路板产线）做不成。
  - 社区项目用 SMT 摆放加多智能体寻路布传送带（[列表](https://science.ecosyste.ms/projects/38421)），还有 [Factorio-SAT](https://github-redirect.dependabot.com/R-O-C-K-E-T/Factorio-SAT)（只做分流器）。
- **Mindustry 自带的 PrebuildAI**（见 `reference/meta-report.md`）：照人画好的基地逐块建，先建挨着已建部分的，缺材料就让核心机去挖。它只决定顺序，不做设计；游戏作者自己标注了"实验性，别用"。

### 2.5 RTS 和程序生成

- **BWEB**（星际争霸机器人的建筑摆放库）：把预制的建筑"块"按地图区域铺下去，并保证不把单位困住；墙用组合试探加 BFS 检查路径。见 `reference/meta-report.md` 和 [Locutus 的说明](https://www.satirist.org/ai/starcraft/blog/archives/518-new-bot-Locutus.html)。
- **Čertický 2013**：用答案集编程（约束式）做墙封（[arXiv](https://arxiv.org/pdf/1306.4460)）。
- **Barriga 等 2014**：用遗传算法加模拟攻防优化防御建筑的摆放；太重，不适合在线用。
- **GDMC**（Minecraft 聚落生成比赛）：考的是生成的布局能不能适应从没见过的地形（[GDMC](https://cog2025.inesc-id.pt/gdmc/)）。
- **Wave Function Collapse**：用局部图样做约束传播的生成器，很快，但只处理同尺寸的格子（[解释](https://boristhebrave.com/2020/04/13/wave-function-collapse-explained)）。

### 2.6 搜索和规划理论里的对应概念

- **宏操作和抽象层次**：Korf（1987，[Artificial Intelligence 33(1)](https://datalearner.com/academic/journal-papers/0004-3702/volumes-and-issues/91/paper-detail/69157)）分析了子目标、宏操作、抽象对搜索量的影响：抽象层次可以把指数级的规划降到线性量级。后来也有人指出，抽象并非总能加速（[Armano 等](https://iris.unica.it/handle/11584/107262)）。
- **HTN 规划**：只按领域专家写好的"方法"分解任务，规划器不考虑其他分解（[Erol, Hendler, Nau](https://www.cs.umd.edu/~nau/papers/erol1993toward.pdf)，[SHOP2](https://www.emergentmind.com/papers/1106.4869)）。蓝图就是"方法"。
- **最小冲突修补**：Minton 等 1992（[Artificial Intelligence 58](https://ntrs.nasa.gov/citations/19930006097)）。从一个有冲突的完整方案出发，每一步改一个变量，改成冲突最少的值；在 n 皇后上比回溯快几个数量级，一百万皇后也能很快解。这就是"先建着，有问题改一改"的算法形式。
- **大邻域搜索（LNS / ALNS）**：每轮"破坏"方案的一部分（比如一片区域、一组相关的东西），再用好的方法"修复"。破坏和修复的算子按历史表现自适应地选。是工业界车辆路径、调度里最常用的局部搜索框架（[讲义](https://imada.sdu.dk/u/marco/DM841/slides/dm841-hr-alns.pdf)）。
- **计划修补 vs 重新规划**：Fox、Gerevini、Long、Serina（ICAPS 2006，[Strathclyde](https://strathprints.strath.ac.uk/2776/)）比较了"扔掉重规划"和"在原计划上修补"：修补得到的新计划离原计划更近（稳定），而且更快。
- **分层寻路 HPA\***：Botea、Müller、Schaeffer（2004，[论文](https://webdocs.cs.ualberta.ca/%7emmueller/ps/hpastar.pdf)）。把网格切成簇，预先算好簇内穿越代价，先在簇的层面搜。比高度优化的 A\* 快最多 10 倍，路径只差 1%，地图变化时也好用。
- **多智能体寻路 CBS**：先各自规划，发现冲突再分支加约束，比全局 A\* 少看很多状态（[Sharon 等](https://ojs.aaai.org/index.php/AAAI/article/view/8140)）。有人把它用在多根分支管道的布线上（[Tampere 论文](https://urn.fi/URN:NBN:fi:tuni-202005315848)）。

## 3. 能大幅缩小搜索空间的启发式（按收益大致排序）

| # | 启发式 | 怎么缩小搜索 | 出处 | 代价 |
|---|---|---|---|---|
| 1 | **规整骨架**：行、跨、通道、管廊、总线、街区 | 二维布线变成每条通道/主干上的近似一维分配；摆放变成"放进哪个格位" | 标准单元行和通道布线；FBS；Factorio 主总线和城市街区；化工管廊 | 面积（通道可占 70%）；骨架本身要设计 |
| 2 | **模块/蓝图**：宏块、IP、PEA、HTN 方法、宏操作 | 几十个方块变成一个对象，内部预先验证；搜索从"格子组合"降到"块的位置和朝向" | 芯片 IP；模块化化工厂；Korf；Factorio 蓝图论文的建议 | 模块之间的连接仍要布；模块不一定最省地 |
| 3 | **先粗后细的分层** | 先在粗粒度上定区域、通道、中间点，再在每一片里细做；每层问题都小 | EDA 流程；SLP；HPA\*；管道的中间点/管廊方法 | 粗层的估计不准时，细层可能做不出来，需要回退 |
| 4 | **增量加局部修补**：ECO 最小扰动、LNS、最小冲突、拆线重布、计划修补 | 每次只重做出问题的一小片；其余不动 | EDA 的 ECO；Minton；LNS；Fox 等；管道回溯重布 | 可能陷在局部；隔一段时间要做一次较大的重排 |
| 5 | **难的先放**：锚定的、最大的、最受限的、最长的线先放 | 剪掉"先放容易的、最后把难的堵死"的分支 | 管道"大管先布、密集区先布"；装箱的"先放大件" | 只是排序，不保证可行 |
| 6 | **预留空间**：白空间、扩展余量、总线留缝 | 让以后的修改大多能就地完成，不必大动 | 工厂扩建经验；Factorio 总线留缝；芯片摆放的白空间分配 | 面积 |
| 7 | **候选集合再选组合**：每条线几条候选路线，再一起挑 | 把连续的布线空间换成有限选项的组合问题 | 管道"子集路线"；EPFL 的并行候选树加整数规划 | 候选要够好 |

## 4. 对我们的启示（供讨论，本次不实施）

- **人类的做法和工业界的成熟流程是同一套**：模块 + 规整骨架 + 增量修补 + 不够就扩张。没有哪个领域每一步都全局地重摆、重布整个工厂。
- **我们现在的做法和它相反**：每一步对新加的东西做全局模拟退火和协商布线；没有骨架，所以线越来越乱（第 20 分钟时 45 台机器用了 1371 节线）；不用模块；一处失败就整步放弃或剔除，而不是局部修补；空间不够也没有"扩张"这个动作。
- **值得讨论的方向**（只是选项，不是方案）：
  1. **骨架**：核心周围的主干线（像主总线），或固定尺寸的街区；新东西优先挂到骨架上。
  2. **模块**：用现成的 47 个自制模块 + 社区模块作为摆放单位；矿点仍就地放，出口接骨架。
  3. **增量修补**：放新东西时先就近放、直连；出冲突就只拆附近一小片重做（LNS 或拆线重布），失败不再整步放弃。
  4. **扩张**：近处放不下，就开辟更远的空地（新的街区），或拆掉早期的过渡设施腾地方。这两种都应作为候选动作交给规划器去比较，而不是写成规则。
