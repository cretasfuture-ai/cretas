"""Opt-in OpenAI wire format for the existing read resolver, never business logic.

https://developers.openai.com/api/docs/guides/function-calling#strict-mode
Optional original properties become required nullable wire properties. Only those
optional nulls are removed on return; required values, zero and false are preserved.
"""
import copy
import hashlib
import json
import threading


def strict_schema(original):
    result = {k: copy.deepcopy(v) for k, v in original.items()
              if not k.startswith('x-') and k not in {'default', 'properties', 'required', 'additionalProperties'}}
    if 'properties' in original or original.get('type') == 'object':
        fields = original.get('properties', {})
        required = set(original.get('required', []))
        result['properties'] = {}
        for name, field in fields.items():
            converted = strict_schema(field)
            if name not in required:
                typ = converted.get('type')
                if isinstance(typ, str):
                    converted['type'] = [typ, 'null'] if typ != 'null' else typ
                elif isinstance(typ, list):
                    converted['type'] = list(dict.fromkeys(typ + ['null']))
                else:
                    converted = {'anyOf': [converted, {'type': 'null'}]}
                if 'enum' in converted and None not in converted['enum']:
                    converted['enum'].append(None)
                converted['description'] = str(converted.get('description', '')) + ' 未修改或未提供时填null，不填旧值或默认值。'
            result['properties'][name] = converted
        result['required'] = list(fields)
        result['additionalProperties'] = False
    if 'items' in original:
        result['items'] = strict_schema(original['items'])
    if 'anyOf' in original:
        result['anyOf'] = [strict_schema(x) for x in original['anyOf']]
    if '$defs' in original:
        result['$defs'] = {k: strict_schema(v) for k, v in original['$defs'].items()}
    return result


def project_optional_nulls(value, original):
    if original.get('type') == 'object' or 'properties' in original:
        if not isinstance(value, dict):
            raise ValueError('Required structured object missing')
        fields = original.get('properties', {})
        required = set(original.get('required', []))
        if set(value) - set(fields) or required - set(value):
            raise ValueError('Structured function keys differ from declared contract')
        result = {}
        for key, item in value.items():
            if item is None:
                if key not in required:
                    continue
                if 'null' not in ([fields[key].get('type')] if isinstance(fields[key].get('type'), str) else fields[key].get('type', [])):
                    raise ValueError('Required function value is null')
            result[key] = project_optional_nulls(item, fields[key]) if item is not None else None
        return result
    if original.get('type') == 'array' and isinstance(value, list):
        return [project_optional_nulls(x, original.get('items', {})) for x in value]
    return value


def enable_read_tool_strict_wire(transport):
    """Handler-local original schema prevents schema leakage across concurrent callers."""
    local = threading.local()
    payload_original, response_original = transport.response_tool_payload, transport.response_as_chat

    def payload(raw, effort):
        result = payload_original(raw, effort)
        local.schemas = {}
        for tool in result.get('tools', []):
            if tool.get('name') == 'resolve_read_turn':
                local.schemas[tool['name']] = copy.deepcopy(tool['parameters'])
                tool['parameters'] = strict_schema(tool['parameters'])
                tool['strict'] = True
                if hasattr(transport, 'log'):
                    transport.log('openai_wire_schema', dict(tool=tool['name'], strict=True,
                        schema_sha256=hashlib.sha256(json.dumps(tool['parameters'], sort_keys=True).encode()).hexdigest()))
        return result

    def response(body):
        result = response_original(body)
        for choice in result.get('choices', []):
            for call in choice.get('message', {}).get('tool_calls') or []:
                function = call.get('function') or {}
                schema = getattr(local, 'schemas', {}).get(function.get('name'))
                if schema is not None:
                    projected = project_optional_nulls(json.loads(function['arguments']), schema)
                    function['arguments'] = json.dumps(projected, ensure_ascii=False)
        return result

    transport.response_tool_payload, transport.response_as_chat = payload, response
