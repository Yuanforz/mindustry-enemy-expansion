# 模块库 / Module library

`modules.tsv` 是蓝图实验室（`./test.sh modlab`，`-Dlab.deploy=true`）对每个模块的实测：每个输入、输出端口的位置、方向和速度，耗电，造价，各机器的运转率。

这些设计来自社区（来源网址见 `source` 列）。它们的许可没有写明或各不相同，所以 `.msch` 文件只在本地开发时使用，不进仓库，也不打包进模组（见 `.gitignore`）。要在本地重建：把 `reference/research-2026-10-18/schematics-and-sectors.md` 里列的来源下载到 `reference/schematics/`，再运行实验室和 `tools/pick-modules.py write <表>`。

正式发布用的模块换成了我们自己设计、同样经过实验室验证的版本：见 `blueprints/own/`（`./test.sh owngen write` 重建，说明在 `docs/PLAN.md` 第 23 节）。
