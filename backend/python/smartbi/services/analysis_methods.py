"""Source-defined semantics supplied to teachers and local business explanations.

Definitions describe existing deterministic metrics, not model-created arithmetic.
No factory answers, amounts, threshold tuning or identity inference.
"""
STATISTIC_DEFINITIONS = {
    'lineage_business_links':'领退料明细通过来源ID、工单ID/编码、产品编码及父级查询验证后，关联到工单用料行；部分可再关联投料工序。这不是批次或物料物理流转证明。',
    'work_order_data_request':'程序逐单生成的资料、责任角色及补齐用途。实际清单覆盖全部工单，模型只解释提供的代表项；同一工单可有多个缺口。',
    'custom_field_basis':'所有以FieldOrderCount结尾的指标都是填有该自定义字段的工单张数，绝不是字段的价格、重量或金额。已有字段仍需确认单位、实际/计划属性、生效范围；不能代替库存计价或批次分配。',
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
    'groupId': '仅是匿名产品编码与成品单位的组标识，不表示规格、材料身份或工艺阶段已核实；不同组的名称冲突、工资零值不能互相套用。',
    'common_input_price_check': '共同材料编码和相同单位下的已记录正数量领料账面单价比较。相同不证明采购价稳定，不同不证明采购涨价；多价和同码异名需单独核实。',
    'eligibleQuantityRatio': '同产品编码同单位且良品量均已提供时，可算成本工单良品量除该组全部已知良品量。缺量时不计算；不是成本金额覆盖率。',
}
REVIEW_DIRECTIONS = {
    'coverage': '先查可核算与不可核算子集是否集中于不同产品、批量或阶段，不能将已算子集外推全厂；按张数与按同单位产量的覆盖不同。币种及完工、费用发生、录入日期分别核对。',
    'material': '区分提醒条数、材料行数和受影响工单数；同一单可能多次提醒，代表比率不是整体。净耗核对领料减退料及审批状态；零值查计价来源，主料是否另记，不用当前价填历史价。',
    'labor': '人工占比先查材料分母完整性和工资是否另记，再比较同工艺；累计任务工资与报工估算不叠加。机器运行或经过时长不等于多人累计工时，无口径依据不推时薪或效率。',
    'finished': '工单最终良品、报工审批、入库数量分别核对，不能跨层相加。包装、半成品和成品的加工边界、规格、单位及批次未确认，不称成本链已贯通。',
    'product_cost': '先解释高差价组，再用提供的低差价组作账面对照；不同组不能交叉归因。差额先分材料与人工，再逐材料看共同编码的账面单价、组合与阶段。单位估值变化不重复除数量变化。不能用零人工作正常基准；无明细不伪造重算。',
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
