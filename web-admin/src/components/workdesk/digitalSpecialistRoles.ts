export const digitalSpecialistRoles = {
  sales: { path: '/workdesk/sales-owner', title: '销售数字专员工作台', specialty: '销售', avatar: 'clerk',
    description: '跟进客户、核对订单，继续使用现有销售流程。', scope: '客户跟进、订单查询与销售办理；提交仍需原业务确认。',
    links: [['客户订单 · AI 录入', '/sales/orders?assistant=order-entry'], ['销售订单', '/sales/orders'], ['客户管理', '/sales/customers']] },
  purchase: { path: '/workdesk/purchaser', title: '采购数字专员工作台', specialty: '采购', avatar: 'clerk',
    description: '核对库存与供应商，继续使用现有采购流程。', scope: '采购需求、库存预警与供应商查询；采购办理沿用原确认。',
    links: [['采购订单 · AI 录入', '/procurement/orders?assistant=order-entry'], ['采购订单', '/procurement/orders'], ['供应商管理', '/procurement/suppliers']] },
  inventory: { path: '/workdesk/warehouse-keeper', title: '库存数字专员工作台', specialty: '库存', avatar: 'keeper',
    description: '核对待收货物与库存，再进入原仓储流程办理。', scope: '待收、库存和仓储记录查询；实际收货需核对并确认。',
    links: [['入库任务与批次', '/warehouse/materials'], ['库存查询', '/warehouse/inventory-query'], ['出货管理', '/warehouse/shipments']] },
  finance: { path: '/workdesk/finance-manager', title: '财务数字专员工作台', specialty: '财务', avatar: 'accountant',
    description: '核对经营数据、应收应付和待办审批。', scope: '财务报表与待办核对；审批资格及确认沿用原流程。',
    links: [['财务三表', '/finance/three-statements'], ['应收应付', '/finance/ar-ap'], ['会计期间', '/finance/accounting-period']] },
  'quality-manager': { path: '/workdesk/quality-manager', title: '质量追溯数字专员工作台', specialty: '质量追溯', avatar: 'inspector',
    description: '核对食品安全、批次追溯和召回处理。', scope: '追溯及召回核对；冻结、通知与处置沿用各自权限和确认。',
    links: [['质量记录', '/quality/inspections'], ['召回管理', '/quality/recalls'], ['入库任务与批次', '/warehouse/materials']] },
  production: { path: '/workdesk/production-manager', title: '生产数字专员工作台', specialty: '生产', avatar: 'planner',
    description: '核对生产需求，按原流程预览并确认起产。', scope: '生产需求查询与原起产流程；数量、产线和确认规则保持一致。',
    links: [['生产批次', '/production/batches'], ['生产计划', '/production/plans'], ['销售订单', '/sales/orders']] },
  'quality-chief': { path: '/workdesk/quality-chief', title: '质检数字专员工作台', specialty: '质检', avatar: 'inspector',
    description: '核对待检批次与放行条件，再确认处理。', scope: '质检与放行条件核对；实际放行仍需原预览、资格和确认。',
    links: [['质检记录', '/quality/inspections'], ['入库任务与批次', '/warehouse/materials']] },
  'ai-value': { path: '/dashboard/ai-value', title: 'AI价值数字专员工作台', specialty: 'AI价值', avatar: 'clerk',
    description: '查看当前企业的真实AI使用与预警处理记录。', scope: '已有AI价值统计；此处提供数据汇总，不代办业务。',
    links: [['数据分析', '/smart-bi/ai-query'], ['概览', '/dashboard']] },
  logistics: { path: '/scheduling/logistics/workbench', title: '排线数字专员工作台', specialty: '配送排线', avatar: 'planner',
    description: '按订单、路线、确认和导出，完成原有三步排程。', scope: '原配送排线流程；生成计划会保存，确认及导出沿用原规则。',
    links: [['调度记录', '/scheduling/logistics/records'], ['配送订单', '/scheduling/logistics/orders'], ['车辆与司机', '/scheduling/logistics/resources']] },
} as const;

export type DigitalSpecialistRole = keyof typeof digitalSpecialistRoles;
export const digitalSpecialistTitles: Record<string, string> = Object.fromEntries(
  Object.values(digitalSpecialistRoles).map((role) => [role.path, role.title]),
);
