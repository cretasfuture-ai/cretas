// F 线 · 结构 6「嵌入现有页面」的共享入口：「交给数字 X」按钮，带上当前页上下文进入该员工工作台，办完可「回到原页面」。
// 第六批在飞期间不改 A–E 页面；第六批合并后各线按 unified-spec/F-crew-embed.md 接入（一行挂载，命令式，Vue 和 DOM 页都能用）。
import {CREW,type CrewId} from './crew-config'
export interface CrewContext{kind:string;id:string;label:string}
export interface CrewEntryOptions{crew:CrewId;context:CrewContext|(()=>CrewContext|null);back?:string;label?:string;variant?:'button'|'link'}
// 组装工作台地址（框架内 hash 路由）。back 默认取当前 hash，回来时原样还原筛选和选中项。
export function crewHref(o:{crew:CrewId;context:CrewContext|null;back?:string;ws?:string}):string{
  const q=new URLSearchParams({id:o.crew})
  if(o.context){q.set('ctxKind',o.context.kind);q.set('ctxId',o.context.id);q.set('ctxLabel',o.context.label.slice(0,80))}
  const back=o.back??location.hash.replace(/^#/,'');if(back)q.set('back',back)
  const ws=o.ws??new URLSearchParams(location.hash.split('?')[1]||'').get('ws');if(ws==='ydwm')q.set('ws','ydwm')
  return '#/crew/worker?'+q.toString()
}
export function crewEntry(parent:HTMLElement,o:CrewEntryOptions){
  const c=CREW.find(x=>x.id===o.crew);if(!c)throw Error('未知的数字员工：'+o.crew)
  const b=document.createElement('button');b.type='button';b.className=o.variant==='link'?'cs-button cs-button-link crew-entry':'cs-button crew-entry'
  b.textContent=o.label||'交给'+c.name;b.dataset.crew=c.id
  const ctx=()=>typeof o.context==='function'?o.context():o.context
  const sync=()=>{const x=ctx();b.title=x?'带上「'+x.label+'」进入'+c.name+'的工作台；写操作仍先出预览，确认后才办':'进入'+c.name+'的工作台'}
  b.addEventListener('mouseenter',sync);b.addEventListener('focus',sync);sync()
  b.addEventListener('click',()=>{const h=crewHref({crew:c.id,context:ctx(),back:o.back})
    // 在统一框架（六膳门 iframe / 味美 frame 路由）里直接换 hash；FrameRoot 的 hashchange 会同步外壳地址
    if(location.pathname.startsWith('/__unified/lsm/')){location.hash=h;return}
    // 味美编译页（不在框架里）：交给外壳打开框架路由（需 D 在 shell.js 处理 crew-entry 消息，见 F-crew-embed.md）
    try{window.top!.postMessage({source:'crew-entry',type:'open',route:h.slice(1)},location.origin)}catch{location.hash=h}})
  parent.appendChild(b)
  return {element:b,remove:()=>b.remove(),refresh:sync}
}
