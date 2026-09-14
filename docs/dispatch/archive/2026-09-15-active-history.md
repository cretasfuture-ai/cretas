# Dispatch 完成记录 — 2026-09-15

- `OPS-VL-SLOT-QWEN38-FLASH-20260915` — `merged` — Owner: organizer — Base SHA `8c04949d54`；PR #793 squash 合入 `6eb782eee6`。VL 槽位由空链改为三账号轮转 `qwen3.8-flash`(aliyun_c_vl→a_vl→b_vl, prod 探针 4.8/6.2/10.6 s), 逻辑账号带 `_vl` 后缀只服务 VL 槽; `LLM_VL_MODEL`/`LLM_VL_ACCOUNTS` 可配但只能在 `_VISION_VERIFIED` 登记表里选; 其它 7 槽候选链逐字不变(golden 快照仅 vl 变)。pytest 9655 passed/1 pre-existing failed(注册表超龄, 另开票)。部署: `deploy-smartbi-python.sh --env prod` 02:50, 进程启动 02:50:41 晚于代码 mtime 02:49:09, 运行文件含 `aliyun_c_vl` 10 处; 判据真打 `/api/llm/vision` 两张样张 HTTP 200 (4.2 s / 8.2 s)。scope 随本归档释放。
