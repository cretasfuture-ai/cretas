"""评测共用: 考卷 / 工具池 / 网关标签定义。所有候选模型都从这里取同一份输入。"""
from __future__ import annotations

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent

CTX_TEXT = {
    "factory": "食品加工厂（卤制品）员工",
    "restaurant": "餐饮门店老板或店长",
}

# 网关四分类: key -> 给零样本模型看的中文说明
ROUTES = {
    "read": "查询或分析本店/本厂的业务数据（库存、营业额、产量、质检结果、考勤、订单、报表等），只看不改",
    "write": "要新增、修改、删除或提交业务记录（入库出库、报工、改价格、下架菜品、登记损耗、打卡请假、下采购单、审批等），会改动系统里的数据",
    "consult": "咨询知识、标准、方法或建议（食品安全法规、工艺要求、怎么做更好、系统怎么用），不针对某条具体业务数据的查改",
    "oos": "与食品工厂或餐饮经营业务无关的闲聊、问候、生活问题或系统范围外的请求",
}

RESTAURANT_POOL_DOMAINS = {"restaurant", "system", "foodknowledge", "cron"}


def load_tools() -> dict[str, dict]:
    return json.loads((HERE / "tools_repo.json").read_text(encoding="utf-8"))


def pool_for(ctx: str, tools: dict[str, dict]) -> list[str]:
    if ctx == "restaurant":
        return sorted(n for n, t in tools.items() if t["domain"] in RESTAURANT_POOL_DOMAINS)
    return sorted(n for n, t in tools.items() if t["domain"] != "restaurant")


def load_testset() -> list[dict]:
    rows = []
    for line in (HERE / "testset.jsonl").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


def tool_text(t: dict, max_len: int = 120) -> str:
    """给模型看的工具说明: 截断到首句附近, 避免超长描述淹没上下文。"""
    d = " ".join(t["description"].split())
    return d[:max_len]


def state_text(row: dict) -> str:
    return f"用户身份：{CTX_TEXT[row['ctx']]}\n用户说：{row['q']}"
