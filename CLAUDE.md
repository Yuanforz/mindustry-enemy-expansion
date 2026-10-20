# CLAUDE.md

Mindustry 模组 "enemy expansion"。这个分支 `econ/fastest-build-order` 做 **Operations**：基于优化的阵营 AI（经济规划、布局执行、防御、控兵、指挥）。**Brain** 指另一个分支的规则式 AI，只作对照基线。

开始工作前读 `docs/HANDOFF.md`（目标、四条线、现状、下一步、命令），需要细节再看 `docs/PLAN.md`。

规则：
- 用中文简短汇报，先说结果。
- 通用代码，不写针对某张图的特化代码。少开子代理。
- 公平：所有建造由核心机在建造半径内完成，建造速度与玩家相同，不能瞬间建成。
- 只提交、推送到 `econ/fastest-build-order`；不合并 main，不开 PR；第三方蓝图不提交。
- 长测试从类文件快照跑（`test/matches.sh` 会自动做）；并行局数不超过物理核数减二。
- 环境：`sh tools/server-setup.sh`（不需要 root，缺 JDK 和游戏 jar 会自己下载）。
