# 2026-10-20 的记录（云端会话迁移到服务器时留下的）

- `user-messages.md`：用户的全部消息，按时间（52 条）。
- `chat.md`：整段对话。包括用户的消息、Claude 回给用户的文字，以及对话三次被压缩时写下的上下文摘要；不含工具调用和输出。个人信息已去掉。
- `summaries/`：各批测试的结果表。
  - `summary-l2a-even.txt`：线 2，20 局。
  - `summary-l1brain-campaign.txt`：线 1 的 Brain 基线；那时战役模式的科技表有误，作废。
  - `summary-s1/s2/p1/p2-sector.txt`、`summary-b2-even.txt`：更早的对战。
  - `bench-first7.txt`：经济基准。
  - `climb-40d-key-lines.txt`：线 3 爬科技的关键行。
- `build-logs.tar.xz`：这几天 `build/` 下的全部日志（`build/match`、`build/bench`、`build/logs`、`build/intent`、`build/duel`、`build/defbench`、`build/lab`），不含类文件快照和游戏文件。用 `tar -xJf build-logs.tar.xz -C <临时目录>` 解开。

现状和下一步见 `docs/HANDOFF.md`。
