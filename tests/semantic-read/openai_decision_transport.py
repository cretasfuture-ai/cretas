"""Evaluation-only Decisions transport using the production choice prompt contract.

System/user prompts and option construction reuse production builders. Structured
choice replaces the assistant partial prefix and token-logprob output protocol;
its full-choice probabilities are native, not production logprob score parity.
Inherited request-purpose and shortlist both call the real configured mini.
"""
import asyncio
import hashlib
import math
import random

from ai.matcher.decision import (
    LETTERS, SPECIAL_OPTIONS, NONE_KEY, CLARIFY_KEY, build_choice_messages, intent_text,
)


def choice_plan(query, role, options, history, special_options, k):
    if not options:
        return None
    opts = list(options[:k - (len(SPECIAL_OPTIONS) if special_options else 0)])
    if len(opts) == 1 and not special_options:
        return {"shortcut": {str(opts[0]["intent_code"]): 1.0}}
    random.Random(query).shuffle(opts)
    if special_options:
        opts += SPECIAL_OPTIONS
    if not opts or len(opts) > len(LETTERS):
        raise ValueError("Unsupported option count")
    messages = build_choice_messages(query, role, opts, history)
    keys = {"NONE": NONE_KEY, "CLARIFY": CLARIFY_KEY}
    mapping = {letter: keys.get(row['intent_code'], str(row['intent_code']))
               if special_options and i >= len(opts)-len(SPECIAL_OPTIONS) else str(row['intent_code'])
               for i,(letter,row) in enumerate(zip(LETTERS,opts))}
    choices = [dict(value=letter, description=f"{row['intent_code']}：{intent_text(row)}")
               for letter,row in zip(LETTERS,opts)]
    return dict(mapping=mapping, messages=messages, payload=dict(model='gpt-6-luna',
        input=messages[1]['content'], questions=[dict(type='choice',name='intent',
            instructions=messages[0]['content'], choices=choices)]))


def decision_type(transport):
    class PromptContractDecision(transport.LiveDecision):
        async def choose(self, query, role, options, history=None, special_options=False):
            plan = choice_plan(query,role,options,history,special_options,self.k)
            if plan is None:
                return None
            if 'shortcut' in plan:
                return plan['shortcut']
            transport.log('decision_prompt_contract',dict(
                system_sha256=hashlib.sha256(plan['messages'][0]['content'].encode()).hexdigest(),
                input_sha256=hashlib.sha256(plan['messages'][1]['content'].encode()).hexdigest(),
                option_codes=plan['mapping'], source='ai.matcher.decision.build_choice_messages',
                purpose='LIVE_MINI_INHERITED_REQUEST_PURPOSE_CAN_OVERRIDE_ROUTE',
                probability_protocol='NATIVE_FULL_CHOICE_NOT_CHAT_TOP_LOGPROBS',
                assistant_partial='REPLACED_BY_NATIVE_CHOICE_QUESTION'))
            body = await asyncio.to_thread(transport.ask,plan['payload'],'decisions','choice')
            answer = next((a for a in body.get('answers',[]) if a.get('name')=='intent' and a.get('type')=='choice'),None)
            if answer is None:
                return None
            rows = answer.get('probabilities',[])
            probabilities = {r['value']: r['probability'] for r in rows}
            if len(rows)!=len(probabilities) or set(probabilities)!=set(plan['mapping']):
                raise ValueError('Choice distribution mismatch')
            if any(isinstance(p,bool) or not isinstance(p,(int,float)) or not math.isfinite(p) or not 0<=p<=1
                   for p in probabilities.values()):
                raise ValueError('Invalid native probability')
            # No normalization, threshold adjustment or confidence boosting.
            return {plan['mapping'][letter]:probability for letter,probability in probabilities.items()}
    return PromptContractDecision
