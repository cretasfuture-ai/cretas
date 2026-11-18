reg('/production/plans', { title:'生产工单', sub:'计划与执行记录', src:'黑湖只读',
  gap:'工单、工序任务和逐笔报工来自黑湖，这里只读；完工入库字段黑湖没有返回，闭环里「入」显示为问号，不拿别的数据顶替。',
  blocks:[
    {t:'kpis', items:[['工单总数',9,'今天 2026-11-16'],['执行中',4,'GD-1116-01、02、03、09'],['已超期 / 落后',2,'GD-1116-09 超期，GD-1116-02 落后'],['未开始',2,'GD-1117-06、07']]},
    {t:'table', title:'生产工单', filters:['status','schedule'],
      cols:[{k:'projectCode',l:'工单'},{k:'productName',l:'产品'},{k:'status',l:'状态',p:1},{k:'planAmount',l:'计划量',n:1},{k:'good',l:'工单良品',n:1},{k:'unit',l:'单位'},{k:'workPct',l:'工单进度'},{k:'timePct',l:'时间进度'},{k:'schedule',l:'工期',p:1},{k:'tasks',l:'工序任务',n:1},{k:'reports',l:'报工',n:1},{k:'loop',l:'闭环'}],
      rows:[
        {projectCode:'GD-1116-01',productName:'酱牛肉',status:'执行中',planAmount:800,good:520,unit:'kg',workPct:'65.0%',timePct:'66.7%',schedule:'正常',tasks:5,reports:6,loop:'领✓ 产✓ 入?',_d:{'计划时间':'2026-11-15 至 2026-11-17','路线对应':'已对应当前工艺路线'}},
        {projectCode:'GD-1116-02',productName:'卤鸡爪',status:'执行中',planAmount:1200,good:900,unit:'kg',workPct:'75.0%',timePct:'100.0%',schedule:'落后',tasks:5,reports:7,loop:'领✓ 产✓ 入?',_d:{'计划时间':'2026-11-14 至 2026-11-16','说明':'今天是计划结束日，时间进度比工单进度多 25 个百分点'}},
        {projectCode:'GD-1116-03',productName:'川味香肠',status:'执行中',planAmount:600,good:150,unit:'kg',workPct:'25.0%',timePct:'33.3%',schedule:'正常',tasks:4,reports:3,loop:'领✓ 产✓ 入?',_d:{'计划时间':'2026-11-16 至 2026-11-18'}},
        {projectCode:'GD-1116-09',productName:'肉丸',status:'执行中',planAmount:700,good:560,unit:'kg',workPct:'80.0%',timePct:'100.0%',schedule:'已超期',tasks:4,reports:5,loop:'领✓ 产✓ 入?',_d:{'计划时间':'2026-11-13 至 2026-11-15','说明':'已过计划结束日 1 天仍未结束'}},
        {projectCode:'GD-1117-06',productName:'酱牛肉',status:'新建',planAmount:800,good:0,unit:'kg',workPct:'0.0%',timePct:'未开始',schedule:'未开始',tasks:5,reports:0,loop:'领— 产— 入?',_d:{'计划时间':'2026-11-17 至 2026-11-18'}},
        {projectCode:'GD-1117-07',productName:'卤鸡爪',status:'新建',planAmount:1200,good:0,unit:'kg',workPct:'0.0%',timePct:'未开始',schedule:'未开始',tasks:5,reports:0,loop:'领— 产— 入?',_d:{'计划时间':'2026-11-17 至 2026-11-19'}},
        {projectCode:'GD-1115-04',productName:'五香豆干',status:'已结束',planAmount:1000,good:960,unit:'kg',workPct:'96.0%',timePct:'100.0%',schedule:'已完成',tasks:4,reports:8,loop:'领✓ 产✓ 入?',_d:{'计划时间':'2026-11-14 至 2026-11-15'}},
        {projectCode:'GD-1115-05',productName:'辣椒酱',status:'已结束',planAmount:500,good:480,unit:'kg',workPct:'96.0%',timePct:'100.0%',schedule:'已完成',tasks:3,reports:4,loop:'领✓ 产✓ 入?',_d:{'计划时间':'2026-11-14 至 2026-11-15'}},
        {projectCode:'GD-1114-08',productName:'烤鸭',status:'已取消',planAmount:400,good:0,unit:'kg',workPct:'0.0%',timePct:'已取消',schedule:'已取消',tasks:3,reports:0,loop:'领— 产— 入?',_d:{'计划时间':'2026-11-14 至 2026-11-15','说明':'黑湖里已取消，不再计入进度'}}
      ],
      actions:[{l:'导出',msg:'已按当前筛选导出工单清单（示例）'}]}
  ]});

reg('/production/material-flow', { title:'领料', sub:'来源业务记录', src:'黑湖只读',
  gap:'这里只有已发和退回的实际记录；每张工单应领多少（需求）、实际耗用多少，黑湖没有提供，所以不显示净耗和差异。',
  blocks:[
    {t:'kpis', items:[['领料出库',6,'单据（张）'],['退料入库',2,'单据（张）'],['领料合计','1,830','kg（只加同单位）'],['退料合计','60','kg']]},
    {t:'table', title:'领料与退料明细', filters:['kind'],
      cols:[{k:'bizAt',l:'业务时间'},{k:'orderNo',l:'单号'},{k:'kind',l:'动作'},{k:'product',l:'物料'},{k:'qty',l:'数量',n:1,d:1},{k:'unit',l:'单位'},{k:'wh',l:'仓库'},{k:'rel',l:'关联工单'}],
      rows:[
        {bizAt:'2026-11-16 08:10',orderNo:'LL-1116-001',kind:'领料',product:'牛腱肉',qty:1160,unit:'kg',wh:'原料冷库',rel:'GD-1116-01'},
        {bizAt:'2026-11-16 08:40',orderNo:'LL-1116-002',kind:'领料',product:'卤料包',qty:16,unit:'袋',wh:'辅料库',rel:'GD-1116-01'},
        {bizAt:'2026-11-15 14:20',orderNo:'LL-1115-003',kind:'领料',product:'鸡爪',qty:1420,unit:'kg',wh:'原料冷库',rel:'GD-1116-02'},
        {bizAt:'2026-11-16 09:15',orderNo:'LL-1116-004',kind:'领料',product:'猪前腿肉',qty:510,unit:'kg',wh:'原料冷库',rel:'GD-1116-03'},
        {bizAt:'2026-11-13 10:30',orderNo:'LL-1113-005',kind:'领料',product:'肉丸坯',qty:820,unit:'kg',wh:'半成品库',rel:'GD-1116-09'},
        {bizAt:'2026-11-14 13:50',orderNo:'LL-1114-006',kind:'领料',product:'豆干坯',qty:1150,unit:'kg',wh:'半成品库',rel:'GD-1115-04'},
        {bizAt:'2026-11-16 10:05',orderNo:'TL-1116-001',kind:'退料',product:'牛腱肉',qty:40,unit:'kg',wh:'原料冷库',rel:'GD-1116-01'},
        {bizAt:'2026-11-15 17:40',orderNo:'TL-1115-002',kind:'退料',product:'鸡爪',qty:20,unit:'kg',wh:'原料冷库',rel:'GD-1116-02'}
      ],
      actions:[{l:'导出',msg:'已导出领退料明细（示例）'}]}
  ]});

reg('/production/batches', { title:'生产批次', sub:'按工单展示', src:'黑湖只读',
  gap:'黑湖没有独立的生产批次号，这里按工单展示；批次号和完工入库批次黑湖未返回，不自己编号。要看原料批次追溯，去入库批次页。',
  blocks:[
    {t:'kpis', items:[['工单（批次）',9],['执行中',4],['已结束',2],['良品合计','3,570','kg（同单位相加）']]},
    {t:'table', title:'按工单展示的生产批次', filters:['status'],
      cols:[{k:'projectCode',l:'工单'},{k:'productName',l:'产品'},{k:'status',l:'状态',p:1},{k:'good',l:'良品',n:1},{k:'unit',l:'单位'},{k:'reports',l:'报工笔数',n:1},{k:'lastReport',l:'最近报工'},{k:'stockIn',l:'完工入库批次'}],
      rows:[
        {projectCode:'GD-1116-01',productName:'酱牛肉',status:'执行中',good:520,unit:'kg',reports:6,lastReport:'2026-11-16 15:10',stockIn:'黑湖未返回'},
        {projectCode:'GD-1116-02',productName:'卤鸡爪',status:'执行中',good:900,unit:'kg',reports:7,lastReport:'2026-11-16 14:20',stockIn:'黑湖未返回'},
        {projectCode:'GD-1116-03',productName:'川味香肠',status:'执行中',good:150,unit:'kg',reports:3,lastReport:'2026-11-16 13:40',stockIn:'黑湖未返回'},
        {projectCode:'GD-1116-09',productName:'肉丸',status:'执行中',good:560,unit:'kg',reports:5,lastReport:'2026-11-16 11:05',stockIn:'黑湖未返回'},
        {projectCode:'GD-1117-06',productName:'酱牛肉',status:'新建',good:0,unit:'kg',reports:0,lastReport:'无报工',stockIn:'黑湖未返回'},
        {projectCode:'GD-1117-07',productName:'卤鸡爪',status:'新建',good:0,unit:'kg',reports:0,lastReport:'无报工',stockIn:'黑湖未返回'},
        {projectCode:'GD-1115-04',productName:'五香豆干',status:'已结束',good:960,unit:'kg',reports:8,lastReport:'2026-11-15 17:20',stockIn:'黑湖未返回'},
        {projectCode:'GD-1115-05',productName:'辣椒酱',status:'已结束',good:480,unit:'kg',reports:4,lastReport:'2026-11-15 16:00',stockIn:'黑湖未返回'},
        {projectCode:'GD-1114-08',productName:'烤鸭',status:'已取消',good:0,unit:'kg',reports:0,lastReport:'无报工',stockIn:'黑湖未返回'}
      ]}
  ]});

reg('/production/approval', { title:'生产报工 · 只读', sub:'逐笔产出 · 审批在黑湖办理', src:'黑湖只读',
  gap:'每一笔都是黑湖的原始报工，不叠加成工单良品；审批只能在黑湖办理，这里没有通过、驳回按钮。人工列要有工价规则才算，没配置的显示「未配置」。',
  blocks:[
    {t:'kpis', items:[['近两天报工',10,'笔'],['待审批',3,'在黑湖办理'],['已审批',7],['今日报工',5,'2026-11-16']]},
    {t:'table', title:'逐笔报工', filters:['approvalStatus','process'],
      cols:[{k:'id',l:'报工ID'},{k:'project',l:'来源工单'},{k:'process',l:'工序'},{k:'taskId',l:'任务ID'},{k:'good',l:'良品',n:1},{k:'unit',l:'单位'},{k:'approvalStatus',l:'审批',p:1},{k:'labor',l:'人工（工价规则）'},{k:'endAt',l:'报工结束'}],
      rows:[
        {id:'BG-1116-001',project:'GD-1116-01',process:'蒸煮卤制',taskId:'T-0311',good:120,unit:'kg',approvalStatus:'待审批',labor:'未配置',endAt:'2026-11-16 15:10'},
        {id:'BG-1116-002',project:'GD-1116-02',process:'包装',taskId:'T-0322',good:180,unit:'kg',approvalStatus:'待审批',labor:'0.8 工时',endAt:'2026-11-16 14:20'},
        {id:'BG-1116-003',project:'GD-1116-03',process:'腌制',taskId:'T-0334',good:150,unit:'kg',approvalStatus:'待审批',labor:'未配置',endAt:'2026-11-16 13:40'},
        {id:'BG-1116-004',project:'GD-1116-09',process:'包装',taskId:'T-0347',good:200,unit:'kg',approvalStatus:'已审批',labor:'1.0 工时',endAt:'2026-11-16 11:05'},
        {id:'BG-1116-005',project:'GD-1116-01',process:'腌制',taskId:'T-0309',good:400,unit:'kg',approvalStatus:'已审批',labor:'未配置',endAt:'2026-11-16 10:30'},
        {id:'BG-1115-006',project:'GD-1115-04',process:'包装',taskId:'T-0288',good:480,unit:'kg',approvalStatus:'已审批',labor:'1.6 工时',endAt:'2026-11-15 17:20'},
        {id:'BG-1115-007',project:'GD-1115-05',process:'灌装',taskId:'T-0291',good:240,unit:'kg',approvalStatus:'已审批',labor:'0.9 工时',endAt:'2026-11-15 16:00'},
        {id:'BG-1115-008',project:'GD-1116-09',process:'冷却',taskId:'T-0343',good:360,unit:'kg',approvalStatus:'已审批',labor:'未配置',endAt:'2026-11-15 15:10'},
        {id:'BG-1115-009',project:'GD-1116-02',process:'蒸煮卤制',taskId:'T-0318',good:520,unit:'kg',approvalStatus:'已审批',labor:'未配置',endAt:'2026-11-15 11:30'},
        {id:'BG-1115-010',project:'GD-1115-04',process:'干燥',taskId:'T-0285',good:480,unit:'kg',approvalStatus:'已审批',labor:'1.2 工时',endAt:'2026-11-15 09:40'}
      ],
      actions:[{l:'去黑湖审批',msg:'审批在黑湖办理，这里只读（示例提示）'}]}
  ]});

reg('/production/analysis-query', { title:'生产分析', sub:'生产查询与分析', src:'黑湖只读',
  gap:'黑湖没有废品原因、工时和设备时间，所以不做损耗分析和设备效率；不同单位的数量不相加，按产品加单位分组。汇总全部由程序计算。',
  blocks:[
    {t:'kpis', items:[['工单',9,'快照日 2026-11-16'],['计划量合计','7,200','kg'],['良品合计','3,570','kg'],['良品 ÷ 计划','49.6%','同单位 kg']]},
    {t:'table', title:'按产品汇总', filters:['unit'],
      cols:[{k:'name',l:'产品'},{k:'unit',l:'单位'},{k:'n',l:'工单数',n:1},{k:'plan',l:'计划量',n:1},{k:'good',l:'良品',n:1},{k:'rate',l:'良品 ÷ 计划'}],
      rows:[
        {name:'酱牛肉',unit:'kg',n:2,plan:1600,good:520,rate:'32.5%'},
        {name:'卤鸡爪',unit:'kg',n:2,plan:2400,good:900,rate:'37.5%'},
        {name:'川味香肠',unit:'kg',n:1,plan:600,good:150,rate:'25.0%'},
        {name:'肉丸',unit:'kg',n:1,plan:700,good:560,rate:'80.0%'},
        {name:'五香豆干',unit:'kg',n:1,plan:1000,good:960,rate:'96.0%'},
        {name:'辣椒酱',unit:'kg',n:1,plan:500,good:480,rate:'96.0%'},
        {name:'烤鸭',unit:'kg',n:1,plan:400,good:0,rate:'0.0%（已取消）'}
      ]},
    {t:'line', title:'近 14 天报工笔数', unit:'笔', d:0, zero:true,
      labels:['11-03','11-04','11-05','11-06','11-07','11-08','11-09','11-10','11-11','11-12','11-13','11-14','11-15','11-16'],
      series:[{name:'报工笔数',data:[3,4,5,2,0,4,6,5,6,7,5,6,5,5]}]}
  ]});

reg('/production/kanban', { title:'状态看板', sub:'工单状态看板', src:'黑湖只读',
  gap:'按黑湖工单状态分列；卡片只读，不能拖动改状态，状态要在黑湖办理，这里不回写。',
  blocks:[
    {t:'board', columns:[
      {title:'新建（2）', cards:[
        {t:'GD-1117-06', s:'酱牛肉 · 计划 800 kg · 良品 0 kg', tag:'未开始'},
        {t:'GD-1117-07', s:'卤鸡爪 · 计划 1,200 kg · 良品 0 kg', tag:'未开始'}]},
      {title:'执行中（4）', cards:[
        {t:'GD-1116-01', s:'酱牛肉 · 计划 800 kg · 良品 520 kg · 工单进度 65.0%', tag:'正常'},
        {t:'GD-1116-02', s:'卤鸡爪 · 计划 1,200 kg · 良品 900 kg · 工单进度 75.0%', tag:'落后'},
        {t:'GD-1116-03', s:'川味香肠 · 计划 600 kg · 良品 150 kg · 工单进度 25.0%', tag:'正常'},
        {t:'GD-1116-09', s:'肉丸 · 计划 700 kg · 良品 560 kg · 工单进度 80.0%', tag:'已超期'}]},
      {title:'已结束（2）', cards:[
        {t:'GD-1115-04', s:'五香豆干 · 计划 1,000 kg · 良品 960 kg', tag:'已完成'},
        {t:'GD-1115-05', s:'辣椒酱 · 计划 500 kg · 良品 480 kg', tag:'已完成'}]},
      {title:'已取消（1）', cards:[
        {t:'GD-1114-08', s:'烤鸭 · 计划 400 kg · 良品 0 kg', tag:'已取消'}]}
    ]}
  ]});

reg('/dashboard/production-progress', { title:'车间大屏', sub:'工单进度大屏', src:'黑湖只读',
  gap:'黑湖没有车间、产线、设备、工位维度的数据，本屏不画产线图，也不显示设备状态；只展示工单进度和最近报工。办理去工单、报工和称重报工。',
  blocks:[
    {t:'kpis', items:[['执行中工单',4],['已超期 / 落后',2,'肉丸超期，卤鸡爪落后'],['今日报工笔数',5,'2026-11-16 15:30'],['未开始工单',2]]},
    {t:'bars', title:'执行中工单 · 工单进度（良品 ÷ 计划量）', unit:'%', d:1,
      items:[['GD-1116-01 酱牛肉',65.0,'ok'],['GD-1116-02 卤鸡爪',75.0,'risk'],['GD-1116-03 川味香肠',25.0,'ok'],['GD-1116-09 肉丸',80.0,'risk']]},
    {t:'bars', title:'执行中工单 · 时间进度（已用天数 ÷ 计划天数）', unit:'%', d:1,
      items:[['GD-1116-01 酱牛肉',66.7],['GD-1116-02 卤鸡爪',100.0],['GD-1116-03 川味香肠',33.3],['GD-1116-09 肉丸',100.0]]},
    {t:'table', title:'最近报工', search:false, detail:false,
      cols:[{k:'endAt',l:'报工结束'},{k:'project',l:'来源工单'},{k:'process',l:'工序'},{k:'good',l:'良品（kg）',n:1}],
      rows:[
        {endAt:'2026-11-16 15:10',project:'GD-1116-01',process:'蒸煮卤制',good:120},
        {endAt:'2026-11-16 14:20',project:'GD-1116-02',process:'包装',good:180},
        {endAt:'2026-11-16 13:40',project:'GD-1116-03',process:'腌制',good:150},
        {endAt:'2026-11-16 11:05',project:'GD-1116-09',process:'包装',good:200},
        {endAt:'2026-11-16 10:30',project:'GD-1116-01',process:'腌制',good:400}
      ]}
  ]});

reg('/production/wip', { title:'在制品与效率', sub:'工序 × 工单 · 效率与达标率', src:'黑湖只读',
  gap:'在制品来自黑湖工序任务；标准工时黑湖没有，由 Cretas 补充且不回写；没有标准的工序只显示「无标准」，不给达标率。只到工序，不做个人排名。',
  blocks:[
    {t:'tabs', tabs:[
      {label:'在制品（工序 × 工单）', blocks:[
        {t:'text', text:'格子 = 该工序已批准良品 / 任务计划量。「停在」是第一个没完成的已记录工序。当前堵点：包装 2 张、蒸煮卤制 1 张。'},
        {t:'table', title:'工序 × 工单', filters:['stuck'],
          cols:[{k:'p',l:'工单'},{k:'prod',l:'产品'},{k:'stuck',l:'停在'},{k:'c1',l:'备料清洗'},{k:'c2',l:'腌制'},{k:'c3',l:'蒸煮卤制'},{k:'c4',l:'冷却'},{k:'c5',l:'包装'}],
          rows:[
            {p:'GD-1116-01',prod:'酱牛肉',stuck:'蒸煮卤制',c1:'800/800 完成',c2:'800/800 完成',c3:'320/800 进行中',c4:'未开始',c5:'未开始'},
            {p:'GD-1116-02',prod:'卤鸡爪',stuck:'包装',c1:'1200/1200 完成',c2:'1200/1200 完成',c3:'1200/1200 完成',c4:'1200/1200 完成',c5:'900/1200 进行中'},
            {p:'GD-1116-03',prod:'川味香肠',stuck:'腌制',c1:'600/600 完成',c2:'150/600 进行中',c3:'—',c4:'未开始',c5:'未开始'},
            {p:'GD-1116-09',prod:'肉丸',stuck:'包装',c1:'700/700 完成',c2:'—',c3:'700/700 完成',c4:'700/700 完成',c5:'560/700 进行中'},
            {p:'GD-1115-04',prod:'五香豆干',stuck:'已全部完成',c1:'1000/1000 完成',c2:'1000/1000 完成',c3:'—',c4:'—',c5:'960/1000 完成'},
            {p:'GD-1115-05',prod:'辣椒酱',stuck:'已全部完成',c1:'500/500 完成',c2:'—',c3:'—',c4:'—',c5:'480/500 完成'}
          ]}
      ]},
      {label:'效率与达标率', blocks:[
        {t:'text', text:'报工时段规则未配置：配置班次后才按班次拆分有效工时、休息和班外加班；黑湖原工时不变。'},
        {t:'table', title:'按工序汇总（只到工序）', filters:['std'],
          cols:[{k:'proc',l:'工序'},{k:'std',l:'标准（秒/单位）'},{k:'tasks',l:'任务',n:1},{k:'comp',l:'可计算',n:1},{k:'stdH',l:'标准工时'},{k:'effH',l:'有效工时（黑湖）'},{k:'eff',l:'实际效率'},{k:'ok',l:'达标率'},{k:'note',l:'说明'}],
          rows:[
            {proc:'备料清洗',std:'18 / kg',tasks:8,comp:8,stdH:'3.2 小时',effH:'3.6 小时',eff:'88.9%',ok:'75.0%',note:'6 / 8 个任务达标'},
            {proc:'腌制',std:'无标准',tasks:7,comp:0,stdH:'不能算',effH:'5.1 小时',eff:'不能算',ok:'不能算',note:'没有标准工时，可点补充标准'},
            {proc:'蒸煮卤制',std:'30 / kg',tasks:7,comp:6,stdH:'4.0 小时',effH:'4.4 小时',eff:'90.9%',ok:'66.7%',note:'1 个任务单位不一致，未参与计算'},
            {proc:'冷却',std:'无标准',tasks:5,comp:0,stdH:'不能算',effH:'2.8 小时',eff:'不能算',ok:'不能算',note:'没有标准工时'},
            {proc:'包装',std:'12 / kg',tasks:9,comp:9,stdH:'3.0 小时',effH:'3.2 小时',eff:'93.8%',ok:'77.8%',note:'7 / 9 个任务达标'},
            {proc:'杀菌',std:'20 / kg',tasks:3,comp:3,stdH:'1.2 小时',effH:'1.3 小时',eff:'92.3%',ok:'66.7%',note:'2 / 3 个任务达标'}
          ],
          actions:[{l:'补充标准工时',msg:'标准工时由 Cretas 补充，不回写黑湖（示例提示）'},{l:'配置班次',msg:'班次规则示例：白班 08:00-17:00，休息 12:00-13:00'}]}
      ]}
    ]}
  ]});

reg('/production/process-hub', { title:'工序配置', sub:'工艺目录 · 画布 · 节点用料', src:'黑湖只读',
  gap:'工艺路线和标准用料来自黑湖当前配置，只读；历史工艺版本没有取得，只显示「当前参考」，不画成已确认的历史工艺；用料类别未确认的不归入原料、辅料或包材。',
  blocks:[
    {t:'tabs', tabs:[
      {label:'工艺目录', blocks:[
        {t:'table', title:'已记录工序', filters:['bind'],
          cols:[{k:'code',l:'工序编码'},{k:'name',l:'工序'},{k:'tasks',l:'任务数',n:1},{k:'boms',l:'BOM绑定',n:1},{k:'bind',l:'用料绑定'}],
          rows:[
            {code:'P01',name:'备料清洗',tasks:8,boms:12,bind:'已绑定'},
            {code:'P02',name:'腌制',tasks:7,boms:9,bind:'已绑定'},
            {code:'P03',name:'蒸煮卤制',tasks:7,boms:8,bind:'已绑定'},
            {code:'P04',name:'冷却',tasks:5,boms:0,bind:'未绑定'},
            {code:'P05',name:'包装',tasks:9,boms:6,bind:'已绑定'},
            {code:'P06',name:'杀菌',tasks:3,boms:0,bind:'未绑定'}
          ]}
      ]},
      {label:'画布', blocks:[
        {t:'steps', title:'GD-1116-01 酱牛肉 · 工序路线（当前参考）', items:[
          {t:'备料清洗', s:'投入牛腱肉 · 产出清洗后牛腱肉 · 可报工', state:'done'},
          {t:'腌制', s:'投入清洗后牛腱肉、食盐 · 产出腌制牛腱肉 · 可报工', state:'done'},
          {t:'蒸煮卤制', s:'投入腌制牛腱肉、卤料包 · 产出卤制酱牛肉 · 可报工', state:'now'},
          {t:'冷却', s:'投入卤制酱牛肉 · 产出冷却酱牛肉', state:'todo'},
          {t:'包装', s:'投入冷却酱牛肉、真空袋 · 产出酱牛肉成品 · 可报工', state:'todo'}]},
        {t:'text', text:'画布只读，不能拖动改工艺。看不到历史工艺版本，当前路线只是「当前参考」。'}
      ]},
      {label:'节点用料', blocks:[
        {t:'table', title:'本工单产品的标准用料（当前参考，非历史配方）', filters:['proc'],
          cols:[{k:'mat',l:'用料'},{k:'proc',l:'挂接工序'},{k:'qty',l:'标准量 / 基数'},{k:'kind',l:'类别'},{k:'src',l:'源行'}],
          rows:[
            {mat:'牛腱肉',proc:'备料清洗',qty:'1.45 kg / 1 kg',kind:'原料',src:'BOM-0101'},
            {mat:'食盐',proc:'腌制',qty:'0.03 kg / 1 kg',kind:'辅料',src:'BOM-0102'},
            {mat:'卤料包',proc:'蒸煮卤制',qty:'0.02 袋 / 1 kg',kind:'辅料',src:'BOM-0103'},
            {mat:'真空袋',proc:'包装',qty:'1 个 / 0.5 kg',kind:'包材',src:'BOM-0104'},
            {mat:'纸箱',proc:'包装',qty:'1 个 / 10 kg',kind:'类别未确认',src:'BOM-0105'}
          ]}
      ]}
    ]}
  ]});

reg('/production/yield-standard', { title:'标准出成率', sub:'出成标准', src:'Cretas 主来源',
  gap:'黑湖没有标准出成率数据，标准由 Cretas 补充、按工序维护，不回写黑湖。实际出成率看称重报工，按工单对比标准；没有称重记录的工序不显示实际值。',
  blocks:[
    {t:'kpis', items:[['已设标准的工序',6],['允许偏离','± 3 ~ 5','个百分点'],['最近修改','2026-11-12','示例员工']]},
    {t:'table', title:'各工序标准出成率', filters:['proc'],
      cols:[{k:'proc',l:'工序'},{k:'rate',l:'标准出成率'},{k:'tol',l:'允许偏离'},{k:'by',l:'设置人 / 时间'},{k:'why',l:'原因'}],
      rows:[
        {proc:'备料清洗',rate:'92.00%',tol:'± 3 个百分点',by:'示例员工 · 2026-11-05 09:20',why:'按去筋去膜后的平均得率设定'},
        {proc:'腌制',rate:'102.00%',tol:'± 3 个百分点',by:'示例员工 · 2026-11-05 09:25',why:'腌制吸水增重，可超过 100%'},
        {proc:'蒸煮卤制',rate:'62.00%',tol:'± 5 个百分点',by:'示例员工 · 2026-11-12 14:10',why:'近一个月称重平均 61.4%，上调 1 个点'},
        {proc:'冷却',rate:'99.00%',tol:'± 3 个百分点',by:'示例员工 · 2026-11-05 09:30',why:'冷却只有少量水分损失'},
        {proc:'包装',rate:'99.50%',tol:'± 3 个百分点',by:'示例员工 · 2026-11-05 09:35',why:'含封口试机损耗'},
        {proc:'杀菌',rate:'100.00%',tol:'± 3 个百分点',by:'示例员工 · 2026-11-08 16:00',why:'杀菌不应有重量变化'}
      ],
      actions:[{l:'新增 / 修改标准',msg:'修改标准需要填原因，旧标准留痕不删除（示例提示）'}]},
    {t:'text', text:'标准可录到 150%（腌制、复水类工序可能超过 100%）；称重报工里实际出成率达到或超过 100% 的记录标「数据疑点」，只标不改原值。'}
  ]});

reg('/system/products', { title:'产品', sub:'产品、工艺与BOM', src:'黑湖只读',
  gap:'产品、工艺和 BOM 条目来自黑湖当前配置，只读；历史版本没有取得。',
  blocks:[
    {t:'tabs', tabs:[
      {label:'产品', blocks:[
        {t:'table', title:'产品', filters:['unit'],
          cols:[{k:'code',l:'产品编码'},{k:'name',l:'产品'},{k:'unit',l:'单位'},{k:'orders',l:'关联工单',n:1},{k:'boms',l:'BOM条目',n:1},{k:'route',l:'工艺路线'}],
          rows:[
            {code:'CP-001',name:'酱牛肉',unit:'kg',orders:2,boms:8,route:'备料清洗 → 腌制 → 蒸煮卤制 → 冷却 → 包装'},
            {code:'CP-002',name:'卤鸡爪',unit:'kg',orders:2,boms:7,route:'备料清洗 → 腌制 → 蒸煮卤制 → 冷却 → 包装'},
            {code:'CP-003',name:'川味香肠',unit:'kg',orders:1,boms:7,route:'备料清洗 → 腌制 → 冷却 → 包装'},
            {code:'CP-004',name:'肉丸',unit:'kg',orders:1,boms:6,route:'备料清洗 → 蒸煮卤制 → 冷却 → 包装'},
            {code:'CP-005',name:'五香豆干',unit:'kg',orders:1,boms:5,route:'备料清洗 → 腌制 → 包装'},
            {code:'CP-006',name:'辣椒酱',unit:'kg',orders:1,boms:6,route:'备料清洗 → 包装'},
            {code:'CP-007',name:'烤鸭',unit:'kg',orders:1,boms:5,route:'备料清洗 → 腌制 → 杀菌 → 包装'}
          ]}
      ]},
      {label:'标准用料总表', blocks:[
        {t:'table', title:'标准用料', filters:['product'],
          cols:[{k:'id',l:'BOM行ID'},{k:'product',l:'产出产品'},{k:'mat',l:'用料'},{k:'qty',l:'标准量 / 基数'},{k:'kind',l:'类别'}],
          rows:[
            {id:'BOM-0101',product:'酱牛肉',mat:'牛腱肉',qty:'1.45 kg / 1 kg',kind:'原料'},
            {id:'BOM-0102',product:'酱牛肉',mat:'食盐',qty:'0.03 kg / 1 kg',kind:'辅料'},
            {id:'BOM-0201',product:'卤鸡爪',mat:'鸡爪',qty:'1.18 kg / 1 kg',kind:'原料'},
            {id:'BOM-0202',product:'卤鸡爪',mat:'卤料包',qty:'0.02 袋 / 1 kg',kind:'辅料'},
            {id:'BOM-0301',product:'川味香肠',mat:'猪前腿肉',qty:'0.85 kg / 1 kg',kind:'原料'},
            {id:'BOM-0302',product:'川味香肠',mat:'肠衣',qty:'2 米 / 1 kg',kind:'包材'},
            {id:'BOM-0601',product:'辣椒酱',mat:'干辣椒',qty:'0.60 kg / 1 kg',kind:'原料'}
          ]}
      ]}
    ]}
  ]});

reg('/production/bom/ecns', { title:'工程变更通知', sub:'黑湖没有此类数据', src:'只读来源',
  gap:'黑湖接口里没有 BOM 或工艺的变更单，也没有生产版本号，所以这里没有变更申请、影响范围和生效版本可显示。不造数据，也不留自由文本补充入口：变更单要挂在具体版本上，没有版本号的登记会变成对不上任何版本的孤立记录。',
  blocks:[
    {t:'text', text:'黑湖没有此类数据。这页现在只写缺口和能替代的入口。'},
    {t:'kv', title:'缺口说明', rows:[
      ['缺什么','变更单：申请人、变更内容、影响的产品和工序、生效日期，以及生效版本'],
      ['找谁补','要黑湖开放变更单接口，或确认以后由 Cretas 自建；在这之前本页不造数据'],
      ['现在能看什么','工序配置里的「节点用料」是当前用料依据；本地工作流保存的版本记录了每次工艺改动的时间和原因']]},
    {t:'list', title:'替代入口', items:[
      {t:'看 BOM 用料依据', s:'工序配置里的节点用料页签', act:{l:'打开',msg:'示例：跳到工序配置的节点用料'}},
      {t:'打开工序配置', s:'工艺目录、画布和用料', act:{l:'打开',msg:'示例：跳到工序配置'}}]}
  ]});

reg('/production/bom/price-adjustments', { title:'BOM调价建议', sub:'原料价变动 → 成本影响 → 调价建议（只作建议）', src:'黑湖只读',
  gap:'只作建议：本页没有批准、保存或改价按钮，不回写黑湖，不改客户价目表，不改配方行单价，采纳与否由人自己办理。成本只含已识别的直接成本（材料加人工），不含制造费用；价格不稳定的原料先核价，不出建议。',
  blocks:[
    {t:'kpis', items:[['原料价有变动',4,'种'],['受影响产品',3,'个，没有半成品传导'],['价格不稳定',1,'种，先核价不出建议'],['只有 1 个价格点',1,'种，无法判断变动']]},
    {t:'tabs', tabs:[
      {label:'调价建议', blocks:[
        {t:'text', text:'建议价 = 客户价目表现价 + 每单位成本变化（保持每单位毛利额不变）；另列保持「成本占售价比」不变的算法。成本下降时同一公式给的是降价空间，不是要求降价。'},
        {t:'table', title:'调价建议（元 / kg）', filters:['product'],
          cols:[{k:'product',l:'产品'},{k:'customer',l:'客户'},{k:'price',l:'现价',n:1,d:2},{k:'delta',l:'每单位成本变化',n:1,d:2},{k:'keepMargin',l:'建议价（保持毛利额）',n:1,d:2},{k:'keepRatio',l:'建议价（保持成本占比）',n:1,d:2},{k:'ratio',l:'成本占售价（现 → 后）'}],
          rows:[
            {product:'酱牛肉',customer:'示例客户甲',price:78,delta:5.22,keepMargin:83.22,keepRatio:85.92,ratio:'65.9% → 72.6%',_d:{'依据':'牛腱肉均价上涨，领料用量 1.45 kg / 1 kg','单位成本':'51.40（黑湖领退料成本单价）'}},
            {product:'酱牛肉',customer:'示例客户乙',price:76,delta:5.22,keepMargin:81.22,keepRatio:83.72,ratio:'67.6% → 74.5%'},
            {product:'酱牛肉',customer:'示例客户丙',price:80,delta:5.22,keepMargin:85.22,keepRatio:88.13,ratio:'64.3% → 70.8%'},
            {product:'卤鸡爪',customer:'示例客户甲',price:36,delta:0.71,keepMargin:36.71,keepRatio:37.17,ratio:'60.6% → 62.5%',_d:{'依据':'鸡爪均价上涨，领料用量 1.18 kg / 1 kg','单位成本':'21.80'}},
            {product:'川味香肠',customer:'示例客户丙',price:48,delta:1.19,keepMargin:49.19,keepRatio:49.89,ratio:'62.9% → 64.4%',_d:{'依据':'猪前腿肉均价上涨，领料用量 0.85 kg / 1 kg','单位成本':'30.20'}}
          ]},
        {t:'text', text:'辣椒酱：干辣椒价格不稳定，先核价，暂不给建议。'}
      ]},
      {label:'原料价变动', blocks:[
        {t:'table', title:'原料价变动（元 / kg，最近一次采购价对比此前均价）', filters:['state'],
          cols:[{k:'name',l:'原料'},{k:'kind',l:'类别'},{k:'base',l:'此前均价',n:1,d:2},{k:'latest',l:'最新价',n:1,d:2},{k:'delta',l:'变动',n:1,d:2},{k:'pct',l:'变动幅度'},{k:'vendor',l:'来源'},{k:'state',l:'状态'}],
          rows:[
            {name:'牛腱肉',kind:'原料',base:52.00,latest:55.62,delta:3.62,pct:'+6.96%',vendor:'示例供应商甲 · 采购订单',state:'上涨'},
            {name:'鸡爪',kind:'原料',base:14.80,latest:15.40,delta:0.60,pct:'+4.05%',vendor:'示例供应商乙 · 采购订单',state:'上涨'},
            {name:'猪前腿肉',kind:'原料',base:21.50,latest:22.90,delta:1.40,pct:'+6.51%',vendor:'示例供应商甲 · 采购订单',state:'上涨'},
            {name:'卤料包',kind:'辅料',base:9.60,latest:9.60,delta:0,pct:'0.00%',vendor:'示例供应商丙 · 采购订单',state:'不变'},
            {name:'真空袋',kind:'包材',base:0.42,latest:0.40,delta:-0.02,pct:'-4.76%',vendor:'示例供应商丙 · 采购订单',state:'下降'},
            {name:'干辣椒',kind:'原料',base:'不能算',latest:'不能算',delta:'不能算',pct:'—',vendor:'示例供应商乙 · 近 5 笔差异过大',state:'价格不稳定'},
            {name:'食盐',kind:'辅料',base:'不能算',latest:2.10,delta:'不能算',pct:'—',vendor:'示例供应商丙 · 只有 1 笔',state:'仅 1 个价格点'}
          ]}
      ]},
      {label:'缺什么', blocks:[
        {t:'list', title:'算不出的地方', items:[
          {t:'半成品没有单独定价', s:'客户价目表没有半成品的价格行，成本受影响但给不出售价建议'},
          {t:'制造费用没有分摊数据', s:'成本只含材料和人工，水电、折旧不在内'},
          {t:'干辣椒、食盐价格点不足', s:'近期采购价不稳定或只有 1 笔，先核价再看'}]}
      ]}
    ]}
  ]});

reg('/lsm/outsource', { title:'委外收发', sub:'黑湖只读', src:'黑湖只读',
  gap:'黑湖委外任务与发料、收货记录，只读。计划、已发料、实收、退回分列，不互相代替；委外费用和结算黑湖没有返回，这里不显示金额。',
  blocks:[
    {t:'kpis', items:[['委外任务',5],['已完成',2],['部分收货',2],['未收货',1]]},
    {t:'tabs', tabs:[
      {label:'委外任务', blocks:[
        {t:'table', title:'委外任务', filters:['state','vendor'],
          cols:[{k:'no',l:'任务'},{k:'vendor',l:'委外方'},{k:'product',l:'产品（工序）'},{k:'plan',l:'计划',n:1},{k:'issued',l:'已发料',n:1},{k:'real',l:'实收',n:1},{k:'back',l:'退回',n:1},{k:'unit',l:'单位'},{k:'state',l:'状态',p:1}],
          rows:[
            {no:'WW-1112-01',vendor:'示例供应商甲',product:'五香豆干（烘干）',plan:1000,issued:1000,real:960,back:0,unit:'kg',state:'已完成'},
            {no:'WW-1113-02',vendor:'示例供应商乙',product:'辣椒酱（灌装）',plan:500,issued:500,real:480,back:0,unit:'kg',state:'已完成'},
            {no:'WW-1114-03',vendor:'示例供应商甲',product:'肉丸（速冻）',plan:700,issued:700,real:360,back:0,unit:'kg',state:'进行中'},
            {no:'WW-1115-04',vendor:'示例供应商丙',product:'卤鸡爪（真空包装）',plan:600,issued:600,real:0,back:0,unit:'kg',state:'待收货'},
            {no:'WW-1116-05',vendor:'示例供应商乙',product:'川味香肠（灌肠）',plan:300,issued:150,real:0,back:0,unit:'kg',state:'进行中'}
          ]}
      ]},
      {label:'发料 / 退料', blocks:[
        {t:'table', title:'发料与退料记录', filters:['act'],
          cols:[{k:'no',l:'委外单'},{k:'act',l:'动作'},{k:'product',l:'产品'},{k:'qty',l:'数量',n:1},{k:'unit',l:'单位'},{k:'at',l:'业务时间'}],
          rows:[
            {no:'WF-1112-01',act:'发料',product:'五香豆干',qty:1000,unit:'kg',at:'2026-11-12 09:00'},
            {no:'WF-1113-02',act:'发料',product:'辣椒酱',qty:500,unit:'kg',at:'2026-11-13 10:20'},
            {no:'WF-1114-03',act:'发料',product:'肉丸',qty:700,unit:'kg',at:'2026-11-14 08:30'},
            {no:'WF-1115-04',act:'发料',product:'卤鸡爪',qty:600,unit:'kg',at:'2026-11-15 13:40'},
            {no:'WF-1116-05',act:'发料',product:'川味香肠',qty:150,unit:'kg',at:'2026-11-16 09:10'}
          ]}
      ]},
      {label:'收货 / 退货', blocks:[
        {t:'table', title:'收货与退货记录', filters:['act'],
          cols:[{k:'no',l:'委外单'},{k:'act',l:'动作'},{k:'product',l:'产品'},{k:'qty',l:'数量',n:1},{k:'unit',l:'单位'},{k:'at',l:'业务时间'}],
          rows:[
            {no:'WS-1114-01',act:'收货',product:'五香豆干',qty:960,unit:'kg',at:'2026-11-14 16:30'},
            {no:'WS-1115-02',act:'收货',product:'辣椒酱',qty:480,unit:'kg',at:'2026-11-15 15:00'},
            {no:'WS-1115-03',act:'收货',product:'肉丸',qty:200,unit:'kg',at:'2026-11-15 17:10'},
            {no:'WS-1116-04',act:'收货',product:'肉丸',qty:160,unit:'kg',at:'2026-11-16 11:20'},
            {no:'WS-1116-05',act:'退货',product:'肉丸',qty:0,unit:'kg',at:'无退货记录'}
          ]}
      ]}
    ]}
  ]});

reg('/production/shortage-cases', { title:'缺料异常', sub:'缺料事件与处理', src:'黑湖只读',
  gap:'缺口量取黑湖采购需求的「缺口」字段（需求量减已下达采购量），不自己推算也不展开 BOM。黑湖采购需求没有「影响哪张订单或工单」的字段，所以不写影响范围；要看订单交期风险去交付风险页。只读，补采购回采购需求和采购订单办理。',
  blocks:[
    {t:'kpis', items:[['有缺口的采购需求',4,'共 7 条'],['缺口最大','肠衣 600 米'],['已过计划到货日',1,'PR-1116-03']]},
    {t:'table', title:'采购需求与缺口', filters:['state'],
      cols:[{k:'code',l:'采购需求'},{k:'mat',l:'物料'},{k:'req',l:'需求量',n:1},{k:'rel',l:'已下达采购',n:1},{k:'rec',l:'已收货',n:1},{k:'lack',l:'缺口',n:1},{k:'unit',l:'单位'},{k:'due',l:'计划到货'},{k:'state',l:'状态',p:1}],
      rows:[
        {code:'PR-1115-01',mat:'肠衣',req:1200,rel:600,rec:0,lack:600,unit:'米',due:'2026-11-17',state:'缺料'},
        {code:'PR-1115-02',mat:'猪前腿肉',req:450,rel:300,rec:300,lack:150,unit:'kg',due:'2026-11-16',state:'缺料'},
        {code:'PR-1116-03',mat:'鸡爪',req:1400,rel:1000,rec:0,lack:400,unit:'kg',due:'已过计划到货日（2026-11-15）',state:'缺料'},
        {code:'PR-1116-04',mat:'牛腱肉',req:1200,rel:1200,rec:1200,lack:0,unit:'kg',due:'2026-11-14',state:'正常'},
        {code:'PR-1116-05',mat:'卤料包',req:60,rel:40,rec:0,lack:20,unit:'袋',due:'2026-11-17',state:'缺料'},
        {code:'PR-1116-06',mat:'真空袋',req:5000,rel:5000,rec:5000,lack:0,unit:'个',due:'2026-11-13',state:'正常'},
        {code:'PR-1116-07',mat:'干辣椒',req:300,rel:300,rec:120,lack:0,unit:'kg',due:'2026-11-16',state:'正常',_d:{'说明':'没有缺口，但已收货只到 120 kg，余下 180 kg 还在路上'}}
      ],
      actions:[{l:'转待办',msg:'已转为采购待办（示例提示）'}]}
  ]});

reg('/production/delivery-risks', { title:'交付风险', sub:'交期 × 计划进度 × 齐套', src:'黑湖只读',
  gap:'订单交期、计划进度、齐套三项合起来看；生产和销售两处菜单是同一页，只读，办理回销售订单和缺料异常。订单没有交期或没有关联工单的，写「不能判断」，不猜。',
  blocks:[
    {t:'kpis', items:[['高风险',2,'卤鸡爪、肉丸'],['中风险',3],['低风险',2],['不能判断',1,'烤鸭订单没填交期']]},
    {t:'table', title:'未完成销售订单', filters:['risk','kit'],
      cols:[{k:'no',l:'订单'},{k:'customer',l:'客户'},{k:'product',l:'产品 · 待交'},{k:'due',l:'交期'},{k:'kit',l:'齐套'},{k:'progress',l:'计划进度'},{k:'risk',l:'风险',p:1},{k:'reason',l:'原因'}],
      rows:[
        {no:'SO-1115-02',customer:'示例客户乙',product:'卤鸡爪 1,200 kg',due:'2026-11-16（今天到期）',kit:'部分（缺 1 项）',progress:'GD-1116-02 工单进度 75.0%，落后',risk:'高',reason:'今天到期，工单落后；卤料包缺 20 袋'},
        {no:'SO-1115-03',customer:'示例客户丙',product:'肉丸 700 kg',due:'2026-11-15（已逾期 1 天）',kit:'齐套',progress:'GD-1116-09 工单进度 80.0%，已超期',risk:'高',reason:'已逾期且工单未结束，良品 560 / 700 kg'},
        {no:'SO-1116-01',customer:'示例客户甲',product:'酱牛肉 800 kg',due:'2026-11-17（还有 1 天）',kit:'齐套',progress:'GD-1116-01 工单进度 65.0%，正常',risk:'中',reason:'距交期 1 天，良品 520 / 800 kg'},
        {no:'SO-1116-04',customer:'示例客户甲',product:'川味香肠 600 kg',due:'2026-11-18（还有 2 天）',kit:'缺料（缺 2 项）',progress:'GD-1116-03 工单进度 25.0%，正常',risk:'中',reason:'肠衣、猪前腿肉有缺口，计划到货 11-16 至 11-17'},
        {no:'SO-1116-06',customer:'示例客户丙',product:'卤鸡爪 1,200 kg',due:'2026-11-20（还有 4 天）',kit:'部分（缺 1 项）',progress:'GD-1117-07 未开始',risk:'中',reason:'鸡爪缺 400 kg，已过计划到货日'},
        {no:'SO-1116-05',customer:'示例客户乙',product:'酱牛肉 800 kg',due:'2026-11-19（还有 3 天）',kit:'齐套',progress:'GD-1117-06 未开始',risk:'低',reason:'物料齐套，时间充裕'},
        {no:'SO-1116-07',customer:'示例客户丙',product:'五香豆干 1,000 kg',due:'2026-11-18（还有 2 天）',kit:'齐套',progress:'GD-1115-04 已结束，良品 960 kg',risk:'低',reason:'工单已完成，发货另看发运记录'},
        {no:'SO-1116-08',customer:'示例客户乙',product:'烤鸭 400 kg',due:'未填写',kit:'不能判断',progress:'关联工单 GD-1114-08 已取消',risk:'不能判断',reason:'订单没填交期，关联工单已取消'}
      ],
      actions:[{l:'看缺料异常',msg:'示例：跳到缺料异常页'}]}
  ]});
