const {envelope,unwrap}=require('./workspace.cjs');
function cachedPosts(w,path){return Object.entries(w.baseline.responses).filter(([key,response])=>key.startsWith('POST '+path+' ')&&response.status===200).map(([key,response])=>{let body;try{body=JSON.parse(key.slice(key.indexOf(' {')+1))}catch{body=null}return {body,response}})}
function queryContract(w,path,body){
 if(path.endsWith('/bom/yield-estimate/recalculate-preview')){const source=w.state.configs[path]??unwrap(cachedPosts(w,path)[0]?.response.body);if(!Array.isArray(source))return null;const ids=body?.productTypeIds;return envelope(ids?.length?source.filter(row=>ids.includes(row.productTypeId)):structuredClone(source),'标准出成率来自真实快照与本地修改')}
 if(path.endsWith('/ai-intents/execute')&&body?.intentCode){const match=cachedPosts(w,path).find(item=>item.body?.intentCode===body.intentCode&&(!body.context?.indicator_code||item.body?.context?.indicator_code===body.context.indicator_code));if(match){const response=structuredClone(match.response);if(body.sessionId&&response.body?.data)response.body.data.sessionId=body.sessionId;return response.body}}
 return null;
}
module.exports={queryContract,cachedPosts};
