export interface CostTableColumn<T extends object> {
  key:string;label:string;type?:'text'|'number'|'date'|'select';
  width?:number;minWidth?:number;sortable?:boolean;filterable?:boolean;
  suffix?:string;count?:boolean;scale?:number;labels?:Record<string,string>;value?:(row:T)=>unknown;
}
export interface CostTableFilter {text?:string;min?:string|number;max?:string|number;selected?:string;missingOnly?:boolean}
export interface CostTableSort {prop?:string;order?:'ascending'|'descending'|null}
export const tableValue=<T extends object>(row:T,column:CostTableColumn<T>):unknown=>{const value=column.value?column.value(row):Reflect.get(row,column.key);if(column.labels&&value!=null)return column.labels[String(value)]||String(value);return typeof value==='number'&&column.scale?value*column.scale:value;};
const missing=(value:unknown)=>value==null||value===''||(typeof value==='number'&&!Number.isFinite(value));
export const filterActive=(filter:CostTableFilter|undefined)=>!!filter&&(!!filter.missingOnly||!!filter.text?.trim()||!!String(filter.min??'').trim()||!!String(filter.max??'').trim()||!!filter.selected);
export function queryCostTable<T extends object>(rows:readonly T[],columns:readonly CostTableColumn<T>[],filters:Record<string,CostTableFilter>,sort:CostTableSort):T[]{
  const filtered=rows.filter(row=>columns.every(column=>{
    const f=filters[column.key];if(!filterActive(f))return true;
    const value=tableValue(row,column);if(f?.missingOnly)return missing(value);if(missing(value))return false;
    if(f?.text?.trim()&&!String(value).toLocaleLowerCase().includes(f.text.trim().toLocaleLowerCase()))return false;
    if(f?.selected&&String(value)!==f.selected)return false;
    for(const bound of ['min','max'] as const){
      const raw=String(f?.[bound]??'').trim();if(!raw)continue;
      const actual=column.type==='number'?(typeof value==='number'?value:NaN):String(value);
      const limit=column.type==='number'?Number(raw):raw;
      if(typeof actual==='number'&&(!Number.isFinite(actual)||!Number.isFinite(limit)))return false;
      if(bound==='min'&&actual<limit||bound==='max'&&actual>limit)return false;
    }
    return true;
  }));
  const column=columns.find(c=>c.key===sort.prop);if(!column||!sort.order)return filtered;
  const direction=sort.order==='descending'?-1:1;
  return filtered.map((row,index)=>({row,index})).sort((a,b)=>{
    const av=tableValue(a.row,column),bv=tableValue(b.row,column);
    if(missing(av)||missing(bv))return missing(av)&&missing(bv)?a.index-b.index:missing(av)?1:-1;
    const compare=typeof av==='number'&&typeof bv==='number'?av-bv:String(av).localeCompare(String(bv),'zh-CN',{numeric:true});
    return compare*direction||a.index-b.index;
  }).map(r=>r.row);
}
