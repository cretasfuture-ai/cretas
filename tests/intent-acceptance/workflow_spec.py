"""验收集规格: 工厂完整工作流的业务域 / 角色 / 说法风格 / 期望姿态, 以及意图 -> 业务域归属。

业务域按一个正常食品工厂的真实流程排: 采购 -> 入库仓储 -> 生产报工 -> 质检放行 -> 出库发货 -> 销售对账 -> 经营管理 (+ 其他)。
意图归属用 intent_code + 名称 + 描述的关键词判定 (确定性、可重跑); 归不进去的进 OTHER, 出题时只当「域外/系统」素材。
"""
import re

STAGES = {
    "PROCURE": "采购（采购订单、供应商、到货预期、采购价）",
    "RECEIVE": "入库仓储（收货、入库、库存、批次、过期临期、盘点、周转框）",
    "PRODUCE": "生产报工（开工、报工、工序、领料、产线、排产、设备、工时）",
    "QUALITY": "质检放行（来料检验、过程检、不良、放行/扣留、留样、CCP、召回）",
    "SHIP": "出库发货（出库单、发货、装车、物流、退货）",
    "SALES": "销售对账（销售订单、客户、价目、发票、应收应付、对账、收付款）",
    "MGMT": "经营管理（报表、成本、看板、考勤人事、审批、预警、数据分析）",
}

ROLES = {
    "OPERATOR": "一线操作员（车间生产：开工、报工、领料、看任务、报设备问题）",
    "WAREHOUSE": "仓管（收货入库、出库、盘点、库存、过期临期）",
    "QC": "质检员（来料检验、过程检、不良登记、放行、留样）",
    "DISPATCHER": "调度/班组长（排产、插单、派工、催料、看产线进度、人手）",
    "BOSS": "老板/厂长（经营看板、订单与交付、成本、应收、异常、审批）",
}

STYLES = {
    "FRONTLINE": "一线说法：短、口语、省略、行话和口头禅、偶尔错别字或方言词，直接说事",
    "INDIRECT": "间接表达：不点名功能，描述处境或结果（「那批牛腱子是不是该处理了」「客户老催我」），要靠常识推出想办的事",
    "MISREAD": "易曲解：字面上容易被认成别的功能（查与改只差一个字、同一个词可指两种单据、动词含糊、对象不明），标准答案给真实意图或应先问",
}

# 期望姿态（识别层）：DIRECT 只读直接办；ASK 应先问；WRITE 写操作（必须经确认卡，识别层不得当只读直出）；REFUSE 不支持；OOD 与工厂无关
STANCES = ("DIRECT", "ASK", "WRITE", "REFUSE", "OOD")

_RULES = [
    ("PROCURE", r"采购|供应商|PURCHASE|SUPPLIER|PROCURE|到货预期|进货"),
    ("QUALITY", r"质检|检验|不良|放行|扣留|留样|CCP|召回|QUALITY|INSPECT|RECALL|HOLD|SSOP|偏离|不合格|NONCONFORM"),
    ("SHIP", r"出库|发货|装车|物流|退货|SHIPMENT|SHIP_|OUTBOUND|DELIVER|TRACE_SHIP|配送|运单"),
    ("SALES", r"销售|客户|价目|发票|应收|应付|对账|收款|付款|结算|SALES|CUSTOMER|INVOICE|RECEIVABLE|PAYABLE|CRM|订单"),
    ("PRODUCE", r"报工|开工|工序|领料|产线|排产|生产|工时|设备|PRODUCTION|WORK_ORDER|PROCESS|SCHEDUL|EQUIPMENT|BOM|配方|产量|工单|SCALE|电子秤|称重"),
    ("RECEIVE", r"入库|收货|库存|批次|过期|临期|盘点|周转|仓|MATERIAL|INVENTORY|WAREHOUSE|STOCK|BATCH|EXPIR|物料|原料"),
    ("MGMT", r"报表|成本|看板|考勤|人事|审批|预警|分析|HR|REPORT|COST|DASHBOARD|ANALYSIS|ALERT|ATTENDANCE|APPROVAL|薪|绩效|利润|毛利|经营"),
]


def stage_of(code: str, name: str, desc: str = "") -> str:
    """订单类意图: 销售订单归 SALES、采购订单归 PROCURE (采购规则排在前)。"""
    text = f"{code} {name}"
    for stage, pat in _RULES:
        if re.search(pat, text, re.I):
            return stage
    return "OTHER"


def split_catalog(catalog):
    out = {s: [] for s in STAGES}
    out["OTHER"] = []
    for it in catalog:
        out[stage_of(it["code"], it["name"], it.get("desc", ""))].append(it)
    return out
