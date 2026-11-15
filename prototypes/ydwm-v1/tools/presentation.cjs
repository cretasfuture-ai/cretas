// Keep provider details in the audit, not in normal product copy.
function message(value){return String(value||'操作未完成，请重试。').replace(/Mac mini(?:\s*·\s*Qwen3\s*8B)?/gi,'解读服务').replace(/qwen3:8b|qwen3\s*8b/gi,'').replace(/本地模型/g,'智能解读').replace(/SSH\/curl|SSH/g,'连接').replace(/；没有切换云端模型。|，未调用云端替代。/g,'。')}
module.exports={message};
