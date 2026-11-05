"""Selective same-Mini review of disagreements; neither pass receives gold labels."""
import asyncio
import json
from factory_accuracy_api import SyntheticClient,MINI
from factory_experiment import write


async def review_disagreements(root,phase,rows,cloud,pool):
    selected=[r for r in rows if cloud[r['record_id']]['prediction']!=r['prediction'] or cloud[r['record_id']]['confidence']<.8]
    directory=root/(phase+'-critic-shadow');directory.mkdir(exist_ok=True)
    (directory/'shared-budget-root').write_text('parent',encoding='utf-8')
    client=SyntheticClient(directory,concurrency=4)
    instructions=('复核食品工厂意图分歧。两个提议都可能错，先从用户原话确定业务对象、动作、所需输出，再核对完整定义。'
        '不要默认相信任一提议，也不要为了达成一致选相近意图。具体类型优先于泛类；查询不能当执行。'
        '缺业务对象或指标、同样可能属于两类且没有上下文时必须__CLARIFY__；缺具体实体ID不影响明确意图。'
        '只返回最终标签与把握，不执行操作，不生成解释。')
    try:
        async def one(row):
            first=cloud[row['record_id']]
            values=first['candidates']
            definitions=[dict(value=code,description=pool.defs[code]['name']+'：'+pool.defs[code]['meaning']) if code in pool.defs else dict(value=code,description='对象/指标/动作不够明确，需要澄清。') for code in values]
            content=json.dumps(dict(text=row['text'],local_proposal=row['prediction'],mini_proposal=first['prediction'],definitions=definitions),ensure_ascii=False)
            schema=dict(type='object',properties=dict(label=dict(type='string',enum=values),confidence=dict(type='number',minimum=0,maximum=1)),required=['label','confidence'],additionalProperties=False)
            result=await client.ask(phase+':critic:'+row['record_id'],MINI,instructions,content,schema,2048,effort='medium')
            return dict(record_id=row['record_id'],prediction=result['label'],confidence=result['confidence'])
        output=await asyncio.gather(*(one(row) for row in selected))
        write(root/(phase+'-critic.jsonl'),output)
        for row in output:cloud[row['record_id']]['review']=row
        return cloud
    finally:await client.close()
