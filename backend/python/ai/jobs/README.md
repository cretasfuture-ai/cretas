# ai/jobs —— 手动运行的离线任务

这里的任务**没有 cron / systemd timer**。现在没有真实流量, 没人读的定时任务比没有更糟;
等点选日志里有了真数据、有人定期审核 PENDING 条目, 再决定要不要挂定时。

## promote_selections —— 澄清点选 -> 精确表 PENDING 提议

把 `ai_intent_selection_log`(用户在澄清按钮里替系统选的意图)里「多人多次选了同一个意图」的归一化说法,
提议为 `ai_intent_phrase_registry` 的 **PENDING** 条目(`source='SELECTION'`)。
永不产出 APPROVED, 永不覆盖已有键(任何状态、任何 source, 含 HUMAN / REJECTED)。

```bash
cd backend/python
# 默认 dry-run: 只读, 打印读数(含分母)与提议, 不写
PYTHONPATH=.:smartbi python -m ai.jobs.promote_selections
# 看过 dry-run 再落库(只写 PENDING)
PYTHONPATH=.:smartbi python -m ai.jobs.promote_selections --apply
# 显式给库(缺省用 settings.food_kb_db_url, 即 cretas 库)
PYTHONPATH=.:smartbi python -m ai.jobs.promote_selections --dsn postgresql://...
```

阈值(`MIN_PICKS=3` / `MIN_DISTINCT_USERS=2` / `MIN_TOP_SHARE=0.8`)与全部跳过规则见模块 docstring。
退出码: 0 = 量到了(点选日志为空也是 0, 读数里会写 `selection log rows read: 0`); 2 = 没量到(连不上库 / 表不存在)。

审核: 在 web-admin / SQL 里把 PENDING 改成 APPROVED 或 REJECTED(REJECTED 保留, 本任务不会再提议同一个键)。
