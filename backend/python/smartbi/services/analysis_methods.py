"""Source-defined semantics supplied to teachers and local business explanations.

Definitions describe existing deterministic metrics, not model-created arithmetic.
No factory answers, amounts, threshold tuning or identity inference.
"""
STATISTIC_DEFINITIONS = {
    'alertCount': '本类提醒条数；不是受影响工单、物料或金额数；提醒可能重叠。附带比率是代表提醒的值，不是全厂汇总。',
    'spread': 'same_product_bridge中为同产品最高和最低单位直接成本之差除最低成本；不能擅换分母后宣称原阈值不成立。',
    'lowLaborZero': '布尔标记：同产品最低单位成本对照单的账面人工为零；不是零工资工单数量，也不是已确认没有人工。',
    'inputQuantityChange': '同产品高成本单对低成本单，每单位成品的已记录KG投入量变化率；不是全部工厂用量变化。',
    'inputValuationChange': '上述可比两单的已记录KG材料总账面金额除KG投入量后的折算值变化率；已经是单位估值变化，不再除一次数量变化；不是采购价变化。',
    'inputKindsChanged': '布尔标记：比较的材料编码集合是否不同；数值一不表示只改变了一种物料。',
    'kgMaterialCostPerFinishedChange': '同一对可比工单，只含KG材料的账面净额除最终良品量的变化；程序按（一加单位成品KG耗量变化）乘（一加每KG领料折算值变化）减一计算；不含非KG材料、人工或制造费用。',
    'recordedOutputPerInput': '账面成品量除某项上游投入量；单位换算和批次未验证，不是可直接比较的得率或损耗率。',
    'unitConversionNeedsVerification': '布尔标记：需要核对单位、规格及批次；不是异常数量，也不是已证实换算错误。',
    'sameUnitAmountRank': '在同一成品单位内，按产品组的加权单位材料加人工成本降序排名；不是总金额排名，也不代表工艺或产品价值相同。',
    'linkedLines/unlinkedLines/zeroValueLines': '材料行统计，与提醒条数口径不同；在未确认同一范围和子集关系前，不自行拼分母或相加。',
    'projectCount/taskCount/outputCount': '工单、工序任务、报工分别计数；不是一一关系，数量差不等于漏报工，产量不跨层相加。',
    'movementOrders/balanceRows': '出入库单数按所选业务日期统计；库存余额行只属于采集时点。不是完工工单生命周期收发，不能相除或倒推历史余额。',
}
REVIEW_DIRECTIONS = {
    'coverage': '先查可核算与不可核算子集是否集中于不同产品、批量或阶段，不能将已算子集外推全厂；按张数与按同单位产量的覆盖不同。币种及完工、费用发生、录入日期分别核对。',
    'material': '区分提醒条数、材料行数和受影响工单数；同一单可能多次提醒，代表比率不是整体。净耗核对领料减退料及审批状态；零值查计价来源，主料是否另记，不用当前价填历史价。',
    'labor': '人工占比先查材料分母完整性和工资是否另记，再比较同工艺；累计任务工资与报工估算不叠加。机器运行或经过时长不等于多人累计工时，无口径依据不推时薪或效率。',
    'finished': '工单最终良品、报工审批、入库数量分别核对，不能跨层相加。包装、半成品和成品的加工边界、规格、单位及批次未确认，不称成本链已贯通。',
    'product_cost': '坚持产量加权和同规格阶段比较；差额先分材料与人工，再区分单位投入量与单位领料估值。既有单位估值变化不重复除数量变化。极差看是否由单张驱动；缺明细只提出检验，不伪造重算值。',
    'production': '任务与报工不是一一对应，数量差不能认定漏报。投入产出账面相等先查实称与默认规则；单位未统一，任何大小的比值都不能作为损耗证据。',
    'inventory': '出入库按业务日期，余额按采集时点。对账需同物料批次、单位、范围及期初期末：期初加收入减发出加有依据调整等于期末；无历史余额只列缺口，不凭当前余额倒推关账。',
}


def definitions_for(pack):
    if not pack.get('isCostDataset', True):
        return {'alertCount': STATISTIC_DEFINITIONS['alertCount']}
    return dict(STATISTIC_DEFINITIONS)


def directions_for(pack):
    if not pack.get('isCostDataset', True):
        return {'coverage': '先确认行、字段、提醒及对象的统计口径；代表性样本不外推总体，正常数据不为了生成提醒而推断异常。'}
    return {sid: REVIEW_DIRECTIONS[sid] for sid in pack['sectionIds'] if sid in REVIEW_DIRECTIONS}
