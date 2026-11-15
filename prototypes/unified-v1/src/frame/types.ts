// 六膳门工作区类型（来自 liushanmen-v1/src/types.ts，字段原样保留）
export type Row=Record<string,unknown>
export interface Source {factoryId:'LIUSHANMEN';factoryName:string;sourceSystem:string;sourceFactoryId:string;revision:string;runId:string;checkedAt:string;referenceAt:string;materialAt:string;resources:Record<string,Row[]>;costs:Cost[];counts:Record<string,number>;limitations:string[];sourceGaps:Row[];lineagePolicy:string}
export interface Cost extends Row {projectId:string|number;projectCode:string;product:string;productCode:string;unit:string|null;status:string;planned:number|null;actual:number|null;finishedGood:number|null;materialCost:number|null;laborCost:number|null;identifiedDirectCost:number|null;unitIdentifiedDirectCost:number|null;unitCostAvailable:boolean;issues:string[];materialLinesDetail:Row[];processes:Row[]}
export interface Analysis {id:string;scopeKey:string;question:string;answer:string;sourceRevision:string;at:string;localWorkflowHash?:string}
export interface Followup {status:string;note:string;sourceRevision:string;at:string}
export interface Workspace {factoryId:'LIUSHANMEN';version:number;analyses:Analysis[];followups:Record<string,Followup>}
export type ListKind='products'|'orders'|'reports'|'processes'|'bom'
// project/product 由六膳门来源直接解析；其余对象类型（stock/document/inspection/task…）由各线经 ai-objects.ts 注册
export type Scope={kind:'project'|'product'|(string&{});id:string}
export interface MenuEntry{path:string;title:string;group:string;reason?:string;page?:string}
