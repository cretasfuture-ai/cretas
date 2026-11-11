"""Measure the generative fallback call (free-text parameter extraction) per call (line E).

Replays main's ``ToolDispatchService.extractParametersWithLLM`` request: the same system prompt
(tool name + description), the user sentence, and one ``extract_parameters`` tool carrying the
tool's real parameter schema (dumped from compiled main classes by ``ToolSchemaDump.java``).
Main sends it to Python ``/api/llm/tool-call`` on the CHAT slot; here ``--backend qwen`` uses the
same ``llm_router`` CHAT chain, ``--backend mini`` the OpenAI eval transport (daily free group).
Records input/output tokens and latency per call. No business execution.
"""
import argparse
import asyncio
import json
import pathlib
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / 'backend' / 'python'))
sys.path.insert(0, str(HERE))

SYSTEM = """你是一个参数提取助手。你的任务是从用户的自然语言输入中提取操作所需的参数。

当前操作: %s
操作描述: %s

请仔细分析用户输入，提取其中包含的参数值。
- 如果用户明确提供了某个参数的值，请提取它
- 如果用户没有提供某个参数，不要猜测或编造，直接忽略该参数（不要填 0 或默认值）
- 参数值应该是用户原文中的信息，不要修改或翻译
- 同一个数字只填给一个参数；备注、原因、说明这类文字参数只照抄用户原话，
  用户没说就不填，不要写你自己的分析或提示

请使用 extract_parameters 工具返回提取的参数。
"""


def payload(tool_name, tool, utterance):
    return {'messages': [{'role': 'system', 'content': SYSTEM % (tool_name, tool['description'] or '执行业务操作')},
                         {'role': 'user', 'content': utterance}],
            'tools': [{'type': 'function', 'function': {'name': 'extract_parameters',
                                                         'description': '从用户输入中提取 %s 操作所需的参数' % tool_name,
                                                         'parameters': tool['schema']}}],
            'tool_choice': 'auto'}


async def main_async(a):
    from decision_eval_transport import Ledger, _usage_tokens, load_llm_env
    rows = json.loads(pathlib.Path(a.rows).read_text('utf-8'))['rows']
    schemas = json.loads(pathlib.Path(a.schemas).read_text('utf-8'))
    out = []
    if a.backend == 'qwen':
        load_llm_env(a.llm_env)
        from common.llm_router import SLOT, call_chain
        from common.llm_metrics import llm_caller_context
        from common.llm_redactor import utterance_egress

        async def call(p):
            with llm_caller_context('e_fallback_measure'), utterance_egress():
                return await call_chain(SLOT.CHAT, p, timeout=20, total_timeout=20)
    else:
        ledger = Ledger(pathlib.Path(a.out).parent, a.cap, pathlib.Path(a.key_file), quota_roots=a.quota_from)

        async def call(p):
            req = dict(model='gpt-5.4-mini-2026-03-17', messages=p['messages'], tools=p['tools'],
                       tool_choice='auto', reasoning_effort='low', max_completion_tokens=600)
            return await ledger.post('chat/completions', req, 600)
    for r in rows:
        tool = schemas.get(r['tool'])
        if not tool:
            continue
        t = time.perf_counter()
        try:
            body = await call(payload(r['tool'], tool, r['utterance']))
            tin, tout, cached = _usage_tokens(body.get('usage') or {})
            msg = ((body.get('choices') or [{}])[0].get('message') or {})
            calls = msg.get('tool_calls') or []
            args = calls[0]['function']['arguments'] if calls else None
            out.append(dict(id=r['id'], tool=r['tool'], utterance=r['utterance'], in_tokens=tin, out_tokens=tout,
                            cached_tokens=cached, latency_ms=round((time.perf_counter() - t) * 1000), model=body.get('model'),
                            arguments=args))
        except Exception as ex:  # noqa: BLE001
            out.append(dict(id=r['id'], tool=r['tool'], error=type(ex).__name__ + ': ' + str(ex)[:200]))
    pathlib.Path(a.out).write_text(json.dumps(out, ensure_ascii=False, indent=1), 'utf-8')
    ok = [o for o in out if 'in_tokens' in o]
    if ok:
        ins = sorted(o['in_tokens'] for o in ok)
        outs = sorted(o['out_tokens'] for o in ok)
        lat = sorted(o['latency_ms'] for o in ok)
        print(dict(calls=len(ok), errors=len(out) - len(ok), in_p50=ins[len(ins) // 2], in_max=ins[-1],
                   out_p50=outs[len(outs) // 2], out_max=outs[-1], latency_p50=lat[len(lat) // 2], latency_max=lat[-1]))


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--backend', choices=('qwen', 'mini'), required=True)
    p.add_argument('--rows', required=True)
    p.add_argument('--schemas', required=True)
    p.add_argument('--out', required=True)
    p.add_argument('--llm-env', default=r'C:/Users/Steve/my-prototype-logistics/backend/python/.env')
    p.add_argument('--cap', type=float, default=1.0)
    p.add_argument('--key-file', default=str(pathlib.Path.home() / '.codex/secrets/cretas-openai-eval.env'))
    p.add_argument('--quota-from', nargs='*', default=[])
    asyncio.run(main_async(p.parse_args()))


if __name__ == '__main__':
    main()
