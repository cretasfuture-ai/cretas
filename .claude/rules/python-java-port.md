---
paths:
  - "backend/python/smartbi_compat/**"
  - "tests/fixtures/java-smartbi-golden/**"
  - "scripts/record-java-golden.sh"
---

# Java→Python Port 规范

**最后更新**: 2026-09-05（压缩：只留 12 条规则的反/正 pattern 与适用条件；修复历史、audit 来源、dict-eq 证据全文在 `docs/rules-casebook/python-java-port-cases.md`，编号一致）
**适用**: SmartBI analysis endpoint 的 Java→Python byte-shape parity port（Phase 2A 50 个端点及后续同类 port）。这些不是 endpoint-specific bug，是 Java 语义 vs Python 习惯的系统性差异，每条都被 ≥2 个 sister chat 独立踩过。

## Rule 1：Null fallback 用 `is not None`，禁 Python `or` / 裸 truthy

Java `!= null` 逐字翻成 `is not None`；`Decimal("0")`、`0`、`""`、`[]`、`False` 在 Python 是 falsy 但在 Java 非 null。

```python
# ❌ r.get("total_cost") or r.get("actual_amount")     # Decimal("0") 被跳过, 累计差异在生产数据上爆炸
# ❌ if r.get("material_cost"):                          # 同根源的 truthy 形式
# ✅ r["total_cost"] if r.get("total_cost") is not None else r.get("actual_amount")
# ✅ if r.get("material_cost") is not None:
# ✅ sum((...), Decimal("0"))                            # 对齐 Java reduce(BigDecimal.ZERO, +); 只有 fallback 路径才显式 None
```

## Rule 2：WEEK period key 用 calendar year，不用 ISO year

```python
_y, iso_week, _d = d.isocalendar()
return f"{d.year}-W{iso_week:02d}"     # mirror Java date.getYear(); 跨年周 2024-12-30 Java 给 2024-W01, isocalendar 给 2025
```

适用：任何 `LocalDate.getYear()` / `WeekFields.ISO.weekOfYear()` 边界。

## Rule 3：Python 函数签名 1:1 mirror Java，不包 wrapper

`_get_profit_metrics(factory_id, start_date, end_date)`，不是 `(factory_id, range_: DateRange)`。`DateRange` 只作 envelope 级工具（如 dashboard composite 的 `dateRange` 字段）。

## Rule 4：BigDecimal 序列化用 `_decimal_to_number`

FastAPI 默认把 Decimal 序列化成字符串 `"0.00"`，Java Jackson 输出数值。用 `_decimal_to_number(v)`（`analysis_finance.py`：整数值返 int，否则 float）。

**Phase 2A 锁定 dict-eq gate，不是 strict-byte**：`Decimal("100.00")` → `100`、`99.9900` → `99.99` 这类 trailing-zero / int-collapse 是预期差异，⛔ 不修；T6.1 dryrun 99.945% 是官方 parity 标准。要修的仍是语义错误（Rule 4 正 pattern）、Java legacy fallback 的结构分歧（Pattern B）、以及 Rule 8 / 11 / 12 那些 byte-strict 项。升 strict-byte 的条件（前端 hash 比对 raw JSON、第三方 byte-identical 契约）Phase 2A 都没有。

## Rule 5：共享 SQL helper 用 `SELECT *`，不裸列字段

新建的跨 sister chat 共享 helper（如 `_query_finance_data`）用 `SELECT *`，consumer 用 `dict.get` 容忍加列。已 merge 的单用途 helper（`_query_finance_payable_data`）保留显式列，不改。

## Rule 6：输入边界 None-check，拒绝静默零结果

asyncpg 把 None 转 NULL，`BETWEEN NULL AND NULL` 永远 false 返 0 行；Java 同处抛 NPE→500。新建 SQL helper 对 `start_date`/`end_date` 为 None 直接 `raise ValueError(...)`。已有 helper 由 caller 保证，不回填。

## Rule 7：非整数阈值比较用 `Decimal`，整数阈值才用 `float()`

`value < Decimal("75.5")`，不是 `float(value) < 75.5`。profit 的 15/25/0/20 是整数所以 `float()` 与 Java `doubleValue()` 一致；sister 改成非整数阈值时必须换 Decimal。

## Rule 8：`Map.of(N)` 的 Jackson key order 不可凭直觉，必须录 golden 反推

Java `Map.of` 按内部 hash 迭代，与参数顺序无关，且 `Map.of(2)/(3)/(4)` 各不相同；不同调用站点的 `Map.of(2)` 也可能不同（`_new_yaxis_entry` 与 sub-endpoints 的 yAxis 就不同，⛔ 不复用 helper 的 order）。步骤：`./scripts/record-java-golden.sh F999 <factoryId> <endpoint> <args> > tests/fixtures/java-smartbi-golden/<name>.json` → 看 golden 里每个 `Map.of` 位置的 key 顺序 → Python dict literal 按 golden 重写。不适用：Java 端用 DTO + `@JsonPropertyOrder` 的位置。`LinkedHashMap` 保插入序，可直接 mirror。

## Rule 9：Lombok + Jackson 序列化三个 quirk，全部以 golden 为准

1. **字段名**：`Introspector.decapitalize` 遇连续大写把首段整体降小写：`xAxisField` → `"xaxisField"`，`URLPath` → `"URLPath"`。
2. **null emit**：DTO 没有 `@JsonInclude(NON_NULL)`（Phase 2A 大部分 DTO）时 null 字段显式输出 `"field": null`，Python dict 也要全 emit None。`grep -n "@JsonInclude" <DTO>.java` 为 0 即默认行为。
3. **派生 getter**：`@Data` 的 `is*` boolean 与手写 `getXxx()` 都会被 Jackson 当字段（`DateRange` 源码 5 字段、golden 7 字段）。`grep -nE "public.*get[A-Z]|public.*is[A-Z]" <DTO>.java` 列出全部。

checklist：录 golden 优先 → grep `@JsonInclude` → grep getter 列表 → dict literal 按 golden 的字段名 / 顺序 / null 镜像。

## Rule 10：`divide(scale, rounding).multiply(K)` 的中间步 round 必须 mirror

```python
# ❌ (actual / target * 100).quantize(Decimal("0.01"), ROUND_HALF_UP)        # 1/3 → 33.33
# ✅ intermediate = (actual / target).quantize(Decimal("0.0001"), ROUND_HALF_UP)   # Java divide(…, 4, HALF_UP)
#    result = (intermediate * Decimal("100")).quantize(Decimal("0.01"), ROUND_HALF_UP)   # → 33.33 / 实测 14.91 vs 14.9074
```

适用：所有百分比 / 比率 / 增长率（completion rate、MoM、YoY、margin、supplier concentration），以及任何 `(n / d * K).quantize(...)` 写法。

## Rule 11：Jackson `LocalDateTime` 会去掉微秒的尾随零

Python `isoformat()` 给 `10:30:00.150710`，Java 给 `10:30:00.15071`（全零时整个小数部分去掉）。所有 mirror Java 的 datetime 用 `smartbi_compat.schema_compat._java_isoformat(dt)`；日志 / 控制台时间戳例外。

## Rule 12：`String.format("%.Nf")` 是 HALF_UP，Python f-string `:.Nf` 是银行家舍入

```python
# ❌ f"{float(concentration):.1f}%"   /  f"{d:.1f}"  /  "%.1f" % d  /  d.quantize(Decimal("0.01"))   # 最后这个默认 ROUND_HALF_EVEN
# ✅ f"{concentration.quantize(Decimal('0.1'), rounding=ROUND_HALF_UP)}%"
# ✅ from smartbi_compat._java_compat import _format_decimal_half_up; f"{_format_decimal_half_up(concentration, 1)}%"
```

先 `quantize(..., ROUND_HALF_UP)` 到目标位数再交给 f-string 渲染是安全的（`_format_completion_pct` 那种写法）。

## 三层关系

Rule 4 管 value 的序列化形式，Rule 8 管 key 顺序，Rule 9 管字段名 / null / 派生字段 —— 合起来才是输出层的 byte-shape。Rule 10 管中间算术精度，Rule 12 管最终显示舍入，两层各自都会让 Rule 10 修完的值在最后一步再 diverge。

## 工具

- `_decimal_to_number`：`backend/python/smartbi_compat/api/analysis_finance.py`
- `_java_isoformat`：`backend/python/smartbi_compat/schema_compat.py`
- `_format_decimal_half_up`：`backend/python/smartbi_compat/_java_compat.py`
- 录 golden：`scripts/record-java-golden.sh`；命名 `tests/fixtures/java-smartbi-golden/analysis-finance-{F999|F001}-{type}.json`，无 `_meta` envelope
- 测试桩：`monkeypatch.setattr("smartbi_compat.api.analysis_finance._query_finance_data", fake_query)`（用模块路径字符串；`from … import` 绑死的名字 setattr 够不着）

## 何时新增 rule

≥2 个 sister chat 都会踩的 Python vs Java 语言习惯 / 序列化 / 字段命名差异 → 进本文件；某端点的 shape 决定或 Phase 2A 一次性迁移决定 → 留 spec。新增时把实测证据写进案例库，这里只留 pattern。
