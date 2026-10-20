你在用户自己的服务器上接手这个仓库（Mindustry 模组 enemy expansion）`econ/fastest-build-order` 分支的开发：Operations，一套基于优化的阵营 AI。之前的工作在 Claude Code 云端会话里做，那边的对话、日志和任务设计都已经放进仓库。

1. 先读：`CLAUDE.md`、`docs/HANDOFF.md`（全文）、`docs/PLAN.md` 的 §26–28 与 §9、`records/2026-10-20/user-messages.md`（用户的全部原话）。需要查细节时再翻 `records/2026-10-20/chat.md` 和 `records/2026-10-20/build-logs.tar.xz`（解到临时目录里看，别解到 `build/`）。
2. 搭环境：`sh tools/server-setup.sh`。它不需要 root，缺 JDK 或游戏 jar 会自己下载，最后跑一局 1 分钟的检查。出错就自己修好（只改脚本或环境，不动游戏本体），然后在 PLAN.md §28 里记一笔。按它打印的建议设并行数 JOBS（物理核数减二，每局约 2 GB 内存）。
3. 然后按 `docs/HANDOFF.md` 第 6 节"下一步"的顺序一项项做下去，不必每步等用户确认。遵守第 3 节的规则：通用代码、少开子代理、公平建造、只推这个分支。
   - 每完成一块：在 PLAN.md 记结果，提交并推送。推送如果因为没有 GitHub 凭据失败，就只在本地提交，在汇报里告诉用户怎么配（`gh auth login`，或配好 git 凭据）。
4. 停下来等用户的时候：
   - 线 2 赢了带规划器开局的 Brain（第 6 节第 3 步）。
   - 需要用户才能做的决定。
   - 用户的额度提示。
   其余时候自己往下做。
5. 汇报用中文，简短，先说结果，别用自己发明的术语。
