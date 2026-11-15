// A 线共用：读验收会话（第四批补修）。
// 顺序：外壳顶层 ?acceptanceSession= → 本框架 search → 本框架 hash 里的查询参数（直接打开框架深链时只有这里有）。
// 都没有返回 ''，调用方再决定用默认区还是本线默认练习会话；不猜。
export function acceptanceSession():string{
  let v=''
  try{v=new URLSearchParams(window.top!.location.search).get('acceptanceSession')||''}catch{/* 跨域读不到顶层，继续往下找 */}
  if(v)return v
  v=new URLSearchParams(location.search).get('acceptanceSession')||''
  if(v)return v
  const h=location.hash,i=h.indexOf('?')
  return i<0?'':new URLSearchParams(h.slice(i+1)).get('acceptanceSession')||''
}
