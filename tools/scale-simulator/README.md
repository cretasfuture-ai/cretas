# 电子秤模拟器

纯 Python 标准库，不进生产包。帧格式取自种子 `V2026_01_04_11__scale_initial_data.sql`，是「系统声称支持的格式」，不是真秤抓包。

```bash
python scale_sim.py list                                   # 协议 + 场景
python scale_sim.py dump-fixtures                          # 重新生成 fixtures/frames.json
python scale_sim.py dump-fixtures --check                  # 校验 fixtures 与模拟器一致 (漂移则退出码 1)
python scale_sim.py serve --protocol KELI_D2008_ASCII --port 9100 --scenario weigh_cycle --drop-after 40
```

- 协议：`KELI_D2008_ASCII`、`YAOHUA_XK3190_ASCII`、`MODBUS_RTU_STANDARD`
- `serve` 场景：`weigh_cycle`（抖动→稳定→回零）、`corrupt_mix`（好帧夹残帧/错 CRC/乱码）、`steady`
- `--drop-after N`：发 N 帧后断开，服务继续监听，用来测重连
- 固定样本场景（`fixtures/frames.json`）：稳定、不稳定、零点、去皮后负数、克单位、超量程、半帧、无结束符、字母混入、空重量、错符号、未知稳定标志、乱码、科学计数法、缺起始标记、错 CRC、异常应答等，共 28 个

改了 `scale_sim.py` 里的场景就要重新 `dump-fixtures` 并提交 `fixtures/frames.json`；Java 测试（`backend/java/cretas-api/.../service/scale/sim/`）读这个文件，是模拟器和后端测试之间的唯一事实来源。

现场联调：`docs/integrations/scale/scale-field-bringup-checklist.md`；现状与缺口：`docs/integrations/scale/scale-integration-current-state.md`。
