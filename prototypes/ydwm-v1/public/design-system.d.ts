export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';
export type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';
export interface OverlayHandle {element: HTMLDialogElement; close(options?: {immediate?: boolean; force?: boolean}): Promise<boolean>}
export interface OverlayOptions {
  title: string; content: HTMLElement | string; size?: string | number; className?: string;
  actions?: Array<{label:string;variant?:Variant;onClick:(handle:OverlayHandle)=>void|Promise<void>}>;
  onClose?:()=>void; beforeClose?:(handle:OverlayHandle)=>boolean|Promise<boolean>;
}
export interface CretasSystem {
  version:string; escape(value:unknown):string;
  status(label:string,tone?:Tone):HTMLSpanElement;
  toast(options:{message:string;tone?:Tone;action?:{label:string;onClick:()=>void|Promise<void>};duration?:number;className?:string}):{element:HTMLElement;close:()=>Promise<void>};
  dialog(options:OverlayOptions):OverlayHandle; drawer(options:OverlayOptions):OverlayHandle;
  renderAnswer(text:string):string; tabs(root:HTMLElement,onChange?:(value:string,tab:HTMLButtonElement)=>void):void;
  help:{(parent:HTMLElement,options:{label:string;text:string}):HTMLButtonElement;dismiss():void};
  menu(options:{anchor:HTMLElement;items:Array<{label:string;ariaLabel?:string;description?:string;disabled?:boolean;variant?:Variant;onSelect?:()=>void|Promise<void>}>}):{element:HTMLElement;close(focus?:boolean):void};
  icons:Record<'plan'|'canvas'|'bom',string>; motion():number;
  /** 'missing' 未填 / 'zero' 已记录 0 / 'invalid' 格式错 / 'value' */
  valueKind(v:unknown):'missing'|'zero'|'invalid'|'value';
  fmtNumber(v:unknown,o?:{digits?:number;missing?:string;invalid?:string}):string;
  /** 两位小数＋币种；缺失 '—'，绝不显示成 0（DECISIONS T5） */
  fmtMoney(v:unknown,o?:{currency?:string;digits?:number;missing?:string}):string;
  /** 两位小数＋单位；缺单位时标「（单位未填）」 */
  fmtQty(v:unknown,unit?:string|null,o?:{digits?:number;missing?:string}):string;
  fmtPct(v:unknown,o?:{digits?:number;missing?:string}):string;
  safeRatio(num:unknown,den:unknown,reason?:string):{value:number|null;reason:string};
  ai:CretasAI;
  /** BL 21：解析粘贴的多张单号（换行、逗号、空格、顿号分隔），去重保序 */
  parseNos(text:string,o?:{max?:number}):string[];
  matchNos<T>(rows:T[],nos:string[],getNo:(r:T)=>unknown):{found:T[];missing:string[]};
  /** BL 21：搜索框旁「批量」按钮；onSearch 返回 {found:条数, missing:未找到单号} 用于显示结果 */
  batchNoSearch(parent:HTMLElement|null,o:{onSearch:(nos:string[])=>{found?:number;missing?:string[]}|Promise<{found?:number;missing?:string[]}>|void;onClear?:()=>void;label?:string;placeholder?:string}):{element:HTMLElement;close():void};
  /** BL 23：相对日期（today/week/month/last7/last30），保存快捷项而不是具体日期 */
  relativeRange(key:'today'|'week'|'month'|'last7'|'last30'|string,today?:Date):{key:string;label:string;from:string;to:string}|null;
  relativeDate(parent:HTMLElement|null,o:{value?:string;onChange?:(r:{key:string;label:string;from:string;to:string}|null)=>void;keys?:string[];allowAll?:boolean}):{element:HTMLElement;value:string;set(v:string):void};
  /** BL 22：已存视图页签（内置 2–3 个 + 用户最多 5 个，按账号存）与状态计数小标签 */
  viewTabs(parent:HTMLElement|null,o:{page:string;builtins:Array<{id:string;name:string;state:Record<string,unknown>}>;getState:()=>Record<string,unknown>;apply:(state:Record<string,unknown>)=>void;counts?:Array<{key:string;label:string;count:number|null}>;countKey?:string;onCount?:(key:string)=>void;active?:string}):{element:HTMLElement;refresh(counts?:Array<{key:string;label:string;count:number|null}>,countKey?:string):void;active:string};
  /** 第五批（R21）：替换原生 prompt。返回输入的文字；取消返回 null。必填与字数在弹层里校验 */
  askText(o?:{title?:string;label?:string;placeholder?:string;value?:string;hint?:string;maxLength?:number;required?:boolean;okLabel?:string}):Promise<string|null>;
  /** R22：不需要填原因的确认框（替换原生 confirm）。确认 true；取消、×、Esc 都是 false */
  confirmAction(o?:{title?:string;message?:string;okLabel?:string;cancelLabel?:string;danger?:boolean}):Promise<boolean>;
  /** 第五批（R21）：替换原生 confirm 的「填原因确认」。返回填写的原因；取消返回 null */
  confirmReason(o?:{title?:string;message?:string;object?:string;reasonLabel?:string;placeholder?:string;minLength?:number;maxLength?:number;required?:boolean;okLabel?:string;danger?:boolean}):Promise<string|null>;
  /** 第六批（R23）：Cretas ↔ 黑湖对账（纯函数，只读） */
  reconcile<C=Record<string,unknown>,B=Record<string,unknown>>(o:ReconcileOptions<C,B>):ReconcileResult<C,B>;
  /** 渲染对账表：四类页签带计数、逐字段 Cretas / 黑湖 / 差、找谁、黑湖数据时点、导出 CSV */
  reconcileView<C=Record<string,unknown>,B=Record<string,unknown>>(parent:HTMLElement|null,o:ReconcileOptions<C,B>&{title?:string;asOf?:string|null;cretasAt?:string|null;keyLabel?:string;fileName?:string}):{element:HTMLElement;readonly result:ReconcileResult<C,B>;refresh(o:Partial<ReconcileOptions<C,B>>&{asOf?:string|null;title?:string}):void};
  reconcileCSV(result:ReconcileResult,o?:{fields?:ReconcileField[];title?:string;asOf?:string|null}):string;
  RECON_STATES:Record<ReconcileState,{label:string;tone:string}>;
  readViews(page:string):Array<{id:string;name:string;state:Record<string,unknown>}>;
  /** B3：派生数量旁的「口径」按钮；点开显示加项/减项/一行公式，并由代码核对加减结果与显示值 */
  formula(parent:HTMLElement|null,spec:FormulaSpec|(()=>FormulaSpec)):HTMLButtonElement;
  openFormula(anchor:HTMLElement,spec:FormulaSpec):void;
  /** B6：字段级变更；labels 只比较列出的字段 */
  diffFields(before:Record<string,unknown>|null|undefined,after:Record<string,unknown>|null|undefined,labels?:Record<string,string>):FieldChange[];
  changeLogHTML(entries:ChangeEntry[],o?:{empty?:string}):string;
  changeLog(parent:HTMLElement|null,entries:ChangeEntry[],o?:{empty?:string}):HTMLDivElement;
  changeLogDrawer(o:{title?:string;object?:string;load:()=>ChangeEntry[]|Promise<ChangeEntry[]>}):OverlayHandle;
  /** 第三批②：黑湖 operation_log 行 → 日志条目（无字段前后值、无操作人，如实标注） */
  blacklakeLog(rows:BlacklakeOpRow[]):ChangeEntry[];
  /** 读统一入口 /__unified/d/lsm-oplog（按单号 code / 实例 id / 对象名过滤，只读） */
  loadBlacklakeLog(q:{code?:string;id?:string|number;object?:string;limit?:number},o?:{url?:string}):Promise<ChangeEntry[]>;
  blacklakeLogDrawer(o:{code?:string;id?:string|number;object?:string;title?:string;label?:string}):OverlayHandle;
  /** 来源标记：黑湖 / Cretas 补充 / 味美 / 未返回 / 系统计算 */
  SOURCES:Record<SourceTagKind,{label:string;tone:Tone;title:string}>;
  sourceTag(source:SourceTagKind,o?:{at?:string;by?:string}):HTMLSpanElement; sourceTagHTML(source:SourceTagKind,o?:{at?:string;by?:string}):string;
  /** 缺口提示：缺什么、找谁补，就地给「补充」「Excel 导入」 */
  gapNotice(parent:HTMLElement|null,o:{title:string;detail?:string;owner?:string;onSupplement?:()=>void;onImport?:()=>void;supplementLabel?:string;importLabel?:string}):HTMLDivElement;
  /** 单条补充：表单 → 预览来源值/补充后 → 确认 → onSubmit 返回回读结果 */
  supplementForm(o:{title:string;object?:string;note?:string;fields:Array<{key:string;label:string;type?:'text'|'number'|'select'|'textarea';unit?:string;required?:boolean;min?:number;value?:unknown;sourceValue?:unknown;options?:Array<string|{value:string;label:string}>}>;onSubmit:(r:{values:Record<string,unknown>;reason:string})=>Promise<{viewHistory?:()=>void}|void>}):OverlayHandle;
  /** Excel 补充：预检 → 确认导入 → 回读 */
  supplementImport(o:{title:string;templateHref?:string;templateLabel?:string;note?:string;onParse:(file:File)=>Promise<{rows:unknown[];errors?:string[]}>;onConfirm:(parsed:{rows:unknown[];errors?:string[]})=>Promise<{count?:number;viewHistory?:()=>void}|void>}):OverlayHandle;
}
export type DataSource='blacklake'|'cretas'|'ydwm'|'missing'|'system';
/** R23：在原有来源之外加「Cretas 主来源」（cretasMain）。单独成型，免得要求各线的 Record<DataSource,…> 都补这一项 */
export type SourceTagKind=DataSource|'cretasMain';
export interface FormulaItem{label:string;value:unknown;unit?:string;source?:DataSource}
export interface FormulaSpec{title?:string;result:{value:unknown;unit?:string};plus?:FormulaItem[];minus?:FormulaItem[];formula?:string;note?:string}
export interface FieldChange{field:string;label?:string;before:string;after:string}
export interface ChangeEntry{at:string;by?:string;action?:string;reason?:string;/** 说明（不是原因），如「黑湖未返回字段前后值」 */note?:string;source?:DataSource;changes?:FieldChange[]}
export interface BlacklakeOpRow{instanceId?:number|string;instanceCode?:string;objectCode?:string;objectName?:string;operateType?:string;operateTimeString?:string;tsMs?:number;fieldType?:string}
export type AiState='answered'|'draft'|'done'|'mismatch'|'queued'|'running'|'stale'|'missing'|'forbidden'|'unavailable'|'sourceError'|'rejected';
export interface AiFact{label:string;value:unknown;unit?:string;kind?:string;delta?:number;status?:string;/** 来源：Cretas 补充时悬浮窗标「含 Cretas 补充」 */source?:DataSource}
export interface AiCheck{paragraphs:Array<{text:string;ok:boolean;reasons:string[];conflict:boolean}>;passed:number;rejected:number}
export interface AiPosition{right:number;bottom:number;width:number}
export interface CretasAI{
  STATES:Record<AiState,{label:string;tone:Tone}>; TEXT:Record<'unavailable'|'stillQueued'|'stopWaiting'|'noObject'|'readOnly',string>;
  badge(state:AiState,extra?:string):HTMLSpanElement; badgeHTML(state:AiState,extra?:string):string;
  queueText(o:{position?:number;waitedMs?:number}):string; sourceErrorText(status:number,name?:string):string;
  cacheKey(o:{workspace?:string;objectType:string;objectId?:string;filter?:unknown;sourceHash?:string;ruleVersion?:string;question?:string}):string;
  hash(v:unknown):string;
  loadPosition(workspace?:string):AiPosition; savePosition(workspace:string,p:AiPosition):void; clampPosition(p:AiPosition):AiPosition;
  /** 解释模式：数字绑定 + 互相矛盾检查；未通过段落不作结论 */
  checkParagraphs(text:string,facts?:AiFact[],o?:{allow?:number[]}):AiCheck; renderChecked(r:AiCheck):string;
  numbersIn(text:string):Array<{raw:string;value:number;unit:string}>;
}
declare global { interface Window {CretasUI:CretasSystem} }

export type ReconcileState='match'|'diff'|'cretasOnly'|'blacklakeOnly';
export interface ReconcileField<C=any,B=any>{key:string;label:string;/** text：按文字完全相同比较（单位、规格等）；默认数值，两边都不是数字时也自动按文字比 */type?:'number'|'text';unit?:string;digits?:number;/** 允许误差，默认 0.005 */tolerance?:number;cretas?:(r:C)=>unknown;blacklake?:(r:B)=>unknown}
export interface ReconcileOptions<C=any,B=any>{cretas:C[];blacklake:B[];key:((r:C|B)=>unknown)|{cretas:(r:C)=>unknown;blacklake:(r:B)=>unknown};fields:ReconcileField<C,B>[];owner?:(row:ReconcileRow<C,B>)=>string;label?:(r:C|B,row:ReconcileRow<C,B>)=>string}
export interface ReconcileRow<C=any,B=any>{key:string;state:ReconcileState;label:string;owner:string;cretas:C|null;blacklake:B|null;diffs:{key:string;label:string;unit:string;c:unknown;b:unknown;delta:number|null;note?:string}[];dupCretas:number;dupBlacklake:number}
export interface ReconcileResult<C=any,B=any>{rows:ReconcileRow<C,B>[];counts:{all:number;match:number;diff:number;cretasOnly:number;blacklakeOnly:number}}
