"""工厂意图目录清理计划 (owner 2026-10-03 批准: 先出清单, 1 和 2 直接做, 然后完整清理、最后收敛)。

单一来源: 本文件里的 PLAN 同时生成 ① 给人看的清单 (markdown) ② 迁移 SQL。
⛔ 餐饮不动 (owner 2026-09-06「餐饮的部分不要管」): business_type=COMMON 的意图**不删不停用**,
   只把 business_type 改成 RESTAURANT —— 工厂看不到了, 餐饮看到的集合逐条不变。

动作:
  SOFT_DELETE  deleted_at=now()      确实没用: 有可执行的同类意图、自己没有执行器的重复项 (关键词并入保留方)
  DEACTIVATE   is_active=false       暂时用不到 / 工具从未实现 / 不允许 AI 直接触达的危险操作 (随时可恢复)
  SCOPE_OUT    business_type=RESTAURANT  COMMON 意图从工厂目录移出 (餐饮可见性不变)
  MERGE_INTO   软删 + 关键词并入 target (同一个 Tool 的重复意图)
  REVIEW       不在本批动, 需 owner 判断 (产品上是否需要)
  HARDEN       保留在聊天里, 但收紧: sensitivity_level=HIGH (WriteGuard 必须二次确认) + 补齐权限码
"""

# ── 第二批 (owner 2026-10-03 裁定) ──────────────────────────────────────────────────
# 「第1类统一停用, 第2类合并」+「设备配置是还不做的部分; 现有支持的功能例如工序、规则,
#   聊天里允许设置, 但需要权限和严格确认」+「同一个工具你去审核是否需要合并」+
#   「餐饮类没有用了, 可以都单独移出工厂」(⇒ COMMON 与 COMMON 之间的合并不再受餐饮约束)。
# (intent_code, action, reason, merge_target_or_permission)
PLAN2: list[tuple[str, str, str, str | None]] = [
    # 设备配置: 还不做 → 停用 / 移出
    *[(c, "DEACTIVATE", "设备配置, 现阶段不做", None) for c in (
        "ISAPI_CONFIG_FIELD_DETECTION", "ISAPI_CONFIG_LINE_DETECTION", "ISAPI_QUERY_CAPABILITIES",
        "SCALE_ADD_DEVICE", "SCALE_ADD_DEVICE_VISION", "SCALE_UPDATE_DEVICE")],
    ("COLD_CHAIN_EQUIPMENT_REGISTER", "SCOPE_OUT", "设备配置 (COMMON), 现阶段不做", None),
    # 系统内部管理: 不需要 → 停用 / 移出
    ("FACTORY_FEATURE_TOGGLE", "DEACTIVATE", "系统功能开关, 不在聊天里改", None),
    ("FORM_GENERATION", "DEACTIVATE", "表单字段生成 (开发配置), 不在聊天里做", None),
    *[(c, "SCOPE_OUT", "Skill/Tool 治理 (COMMON), 不在工厂聊天里做", None) for c in (
        "GOVERNANCE_PATTERN_DISCOVERY", "GOVERNANCE_SKILL_COMPOSE", "GOVERNANCE_SKILL_MANAGE")],
    # 现有业务配置: 保留, 但必须有权限 + 严格确认 (Tool 均已声明 WRITE → WriteGuard 二次确认)
    ("WORK_PROCESS_CONFIG_UPDATE", "HARDEN", "配置产品工序", "production:read_write"),
    ("RULE_CONFIG", "HARDEN", "规则配置", "system:read_write"),
    ("CONVERSION_RATE_UPDATE", "HARDEN", "转换率配置", "production:read_write"),
    ("SCHEDULING_SET_AUTO", "HARDEN", "排产模式: 全自动", "system:read_write"),
    ("SCHEDULING_SET_MANUAL", "HARDEN", "排产模式: 人工确认", "system:read_write"),
    ("SCHEDULING_SET_DISABLED", "HARDEN", "排产模式: 禁用自动排产", "system:read_write"),
    ("FACTORY_NOTIFICATION_CONFIG", "HARDEN", "通知设置", "system:read_write"),
    ("PERIOD_REOPEN", "HARDEN", "反结账", "finance:read_write"),
    # 同一个 Tool 的重复意图: 审核后 3 组合并 (Tool 不读意图码, 两个意图执行结果逐字相同)
    ("MY_APPROVAL_WORKDESK", "MERGE_INTO", "与待审批列表同一个 Tool, Tool 不区分意图", "APPROVAL_PENDING_QUERY"),
    ("WAREHOUSE_KEEPER_TODAY_TASKS", "MERGE_INTO", "与今日待收货同一个 Tool, Tool 不区分意图", "MATERIAL_TODAY_RECEIVING_QUERY"),
    ("PURCHASER_WEEKLY_PLAN", "MERGE_INTO", "与低库存预警同一个 Tool, Tool 不区分意图", "STOCK_ALERT"),
    # ⛔ 不合并: SPRINT10_SHIPMENT_PENDING_TODAY 与 SHIPMENT_CONFIRM_CREATE 共用一个双模式 Tool
    #    (无订单号=列今日待发, 有订单号=创建发货单)。前者是查询, 并入写意图后会在咨询页被拦。
    # 按条件切出来的切片: 目标 Tool 参数是超集且调用同一个服务
    ("SHIPMENT_BY_CUSTOMER", "MERGE_INTO", "按客户查出货: 查询出货的 Tool 支持客户筛选 (原 Tool 强制要客户ID)", "SHIPMENT_QUERY"),
    ("SHIPMENT_BY_DATE", "MERGE_INTO", "按日期查出货: 查询出货的 Tool 支持日期范围", "SHIPMENT_QUERY"),
    ("ALERT_BY_LEVEL", "MERGE_INTO", "按级别查告警: 告警列表 Tool 支持级别筛选", "ALERT_LIST"),
    # ⛔ 不合并: ALERT_BY_EQUIPMENT (按设备名解析, 列表 Tool 只认设备ID);
    #    考勤 4 个与客户/供应商 6 个 (各 Tool 参数互不覆盖, 硬并会丢功能)。
]
# (intent_code, action, reason, merge_target)
PLAN: list[tuple[str, str, str, str | None]] = [
    # ── 1. 工具不存在 (迁移写了配置, Java 里从未实现) —— 功能未做, 停用 ──────────────
    *[(c, "DEACTIVATE", "绑定的 Tool 在代码里不存在, 识别到只会报「Tool 未注册」", None) for c in (
        "BOM_RECIPE_ACTIVATE", "BOM_RECIPE_CLONE_WITH_MODIFY", "BOM_RECIPE_COST_CALCULATE",
        "BOM_RECIPE_CREATE_FROM_TEXT", "BOM_RECIPE_CREATE_FROM_SAMPLE",
        "PAYMENT_REQUEST_QUERY", "PURCHASE_EXCEPTION_QUERY")],
    # ── 2. 危险操作: 不让 AI 直接触达 (只留在正常页面上做) ───────────────────────────
    *[(c, "DEACTIVATE", "破坏性操作, 不允许由聊天直接触达", None) for c in (
        "DATA_BATCH_DELETE", "INVENTORY_CLEAR", "USER_DELETE", "EQUIPMENT_DELETE",
        "CUSTOMER_DELETE", "ORDER_DELETE", "SUPPLIER_DELETE", "MATERIAL_BATCH_DELETE",
        "SCALE_DELETE_DEVICE", "CONFIG_RESET")],
    *[(c, "DEACTIVATE", "账号与权限管理, 不允许由聊天直接触达", None) for c in (
        "USER_CREATE", "USER_DISABLE", "USER_ROLE_ASSIGN")],
    # ── 3. 餐饮意图混进了工厂目录 ─────────────────────────────────────────────────────
    *[(c, "SCOPE_OUT", "餐饮意图被标成 COMMON, 工厂不该看到", None) for c in (
        "RESTAURANT_OPS_RECIPE_COST", "RESTAURANT_OPS_SALES_SUMMARY", "RESTAURANT_OPS_TREND_ANALYSIS",
        "REVENUE_REPORT_GENERATE")],
    # ── 4. 元意图 / 测试: AI 改 AI 自己的配置 ────────────────────────────────────────
    *[(c, "DEACTIVATE", "元意图 (通过聊天改 AI 意图配置), 无执行器", None) for c in (
        "INTENT_CREATE", "INTENT_UPDATE", "INTENT_ANALYZE")],
    ("WORKFLOW_RULE_TEST", "SCOPE_OUT", "测试类意图, 工厂一线不该在聊天里看到", None),
    # ── 5. 没有执行器、但已有可执行的同类意图 —— 删, 关键词并入可执行的那个 ────────────
    ("COLD_CHAIN_TEMPERATURE", "MERGE_INTO", "无执行器; 查温度已有可执行意图", "COLD_CHAIN_TEMP_QUERY"),
    ("PRODUCTION_PLAN_CREATE_FULL", "MERGE_INTO", "无执行器; 建计划已有可执行意图", "PRODUCTION_PLAN_CREATE"),
    ("ORDER_FILTER", "MERGE_INTO", "无执行器; 订单查询已有可执行意图", "ORDER_LIST"),
    ("COST_TREND_ANALYSIS", "MERGE_INTO", "无执行器; 成本查询已有可执行意图", "COST_QUERY"),
    ("WORK_ORDER_UPDATE", "DEACTIVATE", "无执行器 (修改工单未接工具)", None),
    ("APPROVAL_SUBMIT", "DEACTIVATE", "无执行器 (提交审批未接工具)", None),
    ("BOM_CATEGORY_FILTER", "DEACTIVATE", "无执行器", None),
    ("PLAN_FROM_SALES_ORDER_ITEM", "DEACTIVATE", "无执行器", None),
    ("WORK_REPORT_MODE_QUERY", "DEACTIVATE", "无执行器", None),
    *[(c, "DEACTIVATE", "无执行器; 财务比率没有接工具 (财务报表类另有可执行意图)", None) for c in (
        "QUERY_FINANCE_ROA", "QUERY_FINANCE_ROE", "QUERY_LIQUIDITY", "QUERY_SOLVENCY", "QUERY_DUPONT_ANALYSIS")],
    # COMMON 的无执行器项: 不删, 移出工厂目录 (餐饮可见性不变)
    *[(c, "SCOPE_OUT", "无执行器 (COMMON, 只移出工厂目录)", None) for c in (
        "COLD_CHAIN_MONITORING", "SSOP_DAILY_SANITATION", "WAREHOUSE_TODAY_RECEIVING_PENDING", "HACCP_CHECKPOINT",
        "ADDITIVE_COMPLIANCE", "FOOD_SAMPLE_LIFECYCLE", "SUPPLIER_QUALIFICATION_ALERT", "PRODUCT_PRODUCT_SALES_RANKING",
        "FREE_GIFT_INBOUND", "INVENTORY_GAIN_INBOUND", "SALES_DELIVERY_BATCH_ALLOCATE", "TAX_GROUP_INVOICE",
        "TRACE_CODE_FORMAT", "CAMERA_STREAMS", "CAMERA_SUBSCRIBE", "CAMERA_UNSUBSCRIBE",
        "SYSTEM_FEEDBACK", "SYSTEM_PASSWORD_RESET", "SYSTEM_PERMISSION_QUERY", "SYSTEM_PROFILE_EDIT",
        "SYSTEM_SETTINGS", "SYSTEM_SWITCH_FACTORY")],
    # ── 6. 同一个 Tool 的重复意图 ─────────────────────────────────────────────────────
    ("QUALITY_CHECK_EXECUTE", "MERGE_INTO", "与新建质检绑定同一个 Tool", "QUALITY_CHECK_CREATE"),
    ("EFFICIENCY_COMPARE_ALIAS", "SCOPE_OUT", "「别名」重复 (COMMON, 只移出工厂目录)", None),
    # ── 待 owner 判断 (本批不动) ──────────────────────────────────────────────────────
    ("SYSTEM_HELP", "REVIEW", "常用但没有执行器 (走动态选工具): 应接一个帮助 Skill, 不是删", None),
    ("MY_APPROVAL_WORKDESK", "REVIEW", "与待审批列表同一个 Tool: 合并要先确认工作台是否另有展示", None),
    ("WAREHOUSE_KEEPER_TODAY_TASKS", "REVIEW", "与今日待收货同一个 Tool", None),
    ("PURCHASER_WEEKLY_PLAN", "REVIEW", "与低库存预警同一个 Tool", None),
    ("SPRINT10_SHIPMENT_PENDING_TODAY", "REVIEW", "查询类却绑定「确认发货创单」Tool, 且代码名带 Sprint 痕迹", None),
    *[(c, "REVIEW", "按查询条件切出来的切片: 合并前要核对 Tool 是否支持条件参数", None) for c in (
        "SHIPMENT_BY_CUSTOMER", "SHIPMENT_BY_DATE", "SHIPMENT_STATS",
        "ATTENDANCE_DEPARTMENT", "ATTENDANCE_STATS", "ATTENDANCE_STATS_BY_DEPT", "ATTENDANCE_MONTHLY", "ATTENDANCE_HISTORY",
        "ALERT_BY_EQUIPMENT", "ALERT_BY_LEVEL",
        "CUSTOMER_ACTIVE", "CUSTOMER_BY_TYPE", "CUSTOMER_SEARCH", "SUPPLIER_ACTIVE", "SUPPLIER_BY_CATEGORY", "SUPPLIER_SEARCH")],
    *[(c, "REVIEW", "系统/设备配置类: 是否允许在聊天里改, 需产品判断", None) for c in (
        "FACTORY_FEATURE_TOGGLE", "SCHEDULING_SET_AUTO", "SCHEDULING_SET_MANUAL", "SCHEDULING_SET_DISABLED",
        "FACTORY_NOTIFICATION_CONFIG", "RULE_CONFIG", "WORK_PROCESS_CONFIG_UPDATE", "CONVERSION_RATE_UPDATE",
        "ISAPI_CONFIG_FIELD_DETECTION", "ISAPI_CONFIG_LINE_DETECTION", "FORM_GENERATION",
        "GOVERNANCE_PATTERN_DISCOVERY", "GOVERNANCE_SKILL_COMPOSE", "GOVERNANCE_SKILL_MANAGE",
        "PERIOD_REOPEN")],
]
