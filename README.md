# 敌方扩张 Enemy Expansion

Mindustry（v160.7，艾利克尔 Erekir）的 Java 模组：让敌人按玩家的规则经营一座真正的基地，把 PvE 打出接近 PvP 的感觉。

*A Mindustry (v160.7) Java mod for Erekir: the enemy runs a real base under the same rules as the player — real stock, real builders, no free resources — so PvE plays more like PvP.*

## 它做什么

- **资源真实**：敌方核心里是真实库存，上限和玩家一样由核心决定；建筑只能花核心里真正有的东西，不凭空生成。
- **建造真实**：每个核心一台建造机，飞到跟前才能施工，建造速度与玩家相同；放置只走游戏自己的合法性检查（禁建半径等规则对双方一样）。
- **科技不越级**：战役中只用玩家当时已研究的方块和单位。
- **会经营**：开矿（钻机按经典铺法摆放）、发电、铺管道（不混线，被截断会改道）、按配方比例建工厂、建兵营、修炮塔阵地，从自己的领地出发挨着已有建筑一步步推进。
- **会打仗**：单位分头操控；快速单位走位躲子弹、先切最孤立炮塔的供弹链；攻下要点后驻守。
- **不剧透**：正常游玩时不显示敌方的进度和提示。
- 只改敌方行为，不添加内容；兼容地图脚本和世界处理器。所有行为都能在设置里调整。

## 安装

需要 Java 17 和 Mindustry v160.7 的 `Mindustry.jar`（本仓库不含游戏本体，可从 [Anuken/Mindustry 的 Releases](https://github.com/Anuken/Mindustry/releases) 获取）。

```sh
./build.sh path/to/Mindustry.jar     # 默认找 ../Mindustry.jar
# 产物 build/EnemyExpansion.jar，放进游戏数据目录的 mods/ 里
```

## 目录

| 路径 | 内容 |
|---|---|
| `src/expansion/` | 模组本体。`Brain` 决策（世界模型 + 任务队列），`Planner`/`Fitter`/`Lines`/`Power` 选址、摆放、布线、供电，`FighterAI` 单位操控，`Forecast`/`Rates` 按实测速率预测 |
| `blueprints/` | 蓝图库（文字格式），与选址分开 |
| `docs/PLAN.md` | 设计文档：硬性规则、架构、每轮迭代的发现和踩过的坑 |
| `reference/` | 从 v160.7 源码整理的机制说明、艾利克尔数据导出、打法调研 |
| `test/` | 无窗口测试：真实游戏逻辑在后台跑，能以远超实时的速度模拟 |
| `tools/` | 内容导出、蓝图转文字、可视化观战 |

## 无窗口模拟（适合服务器 / 强化学习）

测试框架直接加载 `Mindustry.jar` 的服务器端逻辑，不开窗口、不需要显卡，只依赖 Java 17、`Mindustry.jar` 和 POSIX shell（目前只在 macOS 上跑过，Linux 服务器上应当同样可用）。最常用的是让 AI 站在玩家一方打艾利克尔主线：

```sh
./test.sh batch <名字> <局数> <每关分钟> [难度] [起始关卡] [连打几关]
# 例：4 局并行，每关最多 60 分钟，困难，从第 2 关开始只打 1 关
JOBS=4 ./test.sh batch try 4 60 hard aegis 1
# 每局的日志在 build/logs/<名字>-<n>.log，结果只记 WON / LOST / 超时
```

其他入口见 `test.sh`（`look` 读存档看局势、`wild` 用游戏的地图生成器造新图、`campaign` 单局、`sim` 指定地图）。`test/hand.sh` 可以关掉模组、用文字指令手动玩一局。

战役测试默认自动解锁当前关卡能解锁的全部科技，评分只有三种：胜利、失败、超时。

## 现状（2.5，开发中）

第 2 关（Aegis）基本稳定获胜；第 3 关之后（Lake、Intersect、Atlas、Basin）多为超时或失败，进攻和远距离资源点还在迭代。详细记录在 `docs/PLAN.md`。

## 许可

GPL-3.0，与 Mindustry 本体相同。`reference/schematics/`（从网上收集的他人蓝图，仅供查阅）不在仓库里。
