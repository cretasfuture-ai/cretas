/**
 * 问数据（AI）的路由决定 (问题 51, LSM-COSTBI-F, 2026-11-18)。
 *
 * 改前: 选了数据源的咨询页仍先问 Java 意图层 (executeIntent 不带 uploadId),
 * Java 只要认出一个工厂系统意图就把这轮吃掉 —— 实测「各产品的销售额占比是多少？」
 * 被答成「还需要 indicator_code」「操作类请求」「生产进度看板」, Python 上传问答
 * 一次都没收到 (prod python-service.log 0 次)。
 *
 * 规则: 咨询页 + 已选数据源 ⇒ 直接走上传数据问答 (Python, 按问题出图 + 一图一分析)。
 * 操作页、或没有数据源 ⇒ 仍走 Java 意图层 (写操作的预览/确认链不变)。
 */
export type AiTab = 'consult' | 'operate';

export interface AiQueryRouteInput {
  tab: AiTab;
  /** 操作页只对有写权限的账号生效; 只读账号停在咨询。 */
  hasAnyWrite: boolean;
  /** 下拉选了上传数据源, 或页面已载入数据行。 */
  hasAnalysisData: boolean;
  /** 本轮问题原文; 以动作词开头(建/录入/新增/删除/提交/审批…)的仍交给 Java, 由它出「请切换到操作页」卡。 */
  question?: string;
}

const WRITE_ACTION_PREFIX = /^\s*(请|帮我|麻烦|我要|我想|给我)?\s*(新建|创建|新增|添加|建立|建一个|建个|建|录入|登记|入库|出库|删除|删掉|删|提交|审批|批准|驳回|修改|更新|取消|作废)/;

/** 「给五花肉入库 200kg」这类 给…入库/出库 句式。 */
const WRITE_GIVE_STOCK = /^\s*给.{1,20}(入库|出库)/;

/** 问题明显是写操作(动作词开头)。「分析/统计/查看」等分析类开头不算。 */
export function looksLikeWriteAction(question: string | undefined): boolean {
  return !!question && (WRITE_ACTION_PREFIX.test(question) || WRITE_GIVE_STOCK.test(question));
}

export type AiQueryRoute = 'upload-analysis' | 'java-intent';

export function chooseAiQueryRoute(input: AiQueryRouteInput): AiQueryRoute {
  const operate = input.tab === 'operate' && input.hasAnyWrite;
  if (!operate && input.hasAnalysisData && !looksLikeWriteAction(input.question)) return 'upload-analysis';
  return 'java-intent';
}
