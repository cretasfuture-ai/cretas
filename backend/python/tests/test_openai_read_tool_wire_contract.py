"""Schema/wire preservation only; real API/model evaluation is recorded separately."""
import copy
import importlib.util
import pathlib
import json
from types import SimpleNamespace
import pytest

spec = importlib.util.spec_from_file_location('strict_read_wire', pathlib.Path(__file__).resolve().parents[3] / 'tests/semantic-read/openai_strict_tools.py')
wire = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wire)


SCHEMA = dict(type='object', properties=dict(action=dict(type='string', enum=['CONTINUE', 'CORRECT']),
    parameters=dict(type='object', properties=dict(page=dict(type='integer', minimum=1, default=1),
        status=dict(type='string', enum=['AVAILABLE', 'DEPLETED'], **{'x-status-words': {'DEPLETED': ['已耗尽']}}))),
    evidence=dict(type='object', properties=dict(status=dict(type='string')))), required=['action', 'parameters'])


def test_wire_closes_objects_and_makes_only_original_optionals_nullable_without_mutation():
    before = copy.deepcopy(SCHEMA)
    result = wire.strict_schema(SCHEMA)
    assert SCHEMA == before
    assert result['required'] == ['action', 'parameters', 'evidence']
    assert result['properties']['action']['type'] == 'string'
    nested = result['properties']['parameters']
    assert nested['additionalProperties'] is False and nested['required'] == ['page', 'status']
    assert nested['properties']['page']['type'] == ['integer', 'null']
    assert nested['properties']['page']['minimum'] == 1 and 'default' not in nested['properties']['page']
    assert nested['properties']['status']['enum'] == ['AVAILABLE', 'DEPLETED', None]
    assert 'x-status-words' not in nested['properties']['status']
    assert result['properties']['evidence']['additionalProperties'] is False


def test_optional_nulls_are_absence_not_defaults_or_inherited_conditions():
    raw = dict(action='CONTINUE', parameters=dict(page=2, status=None), evidence=None)
    assert wire.project_optional_nulls(raw, SCHEMA) == dict(action='CONTINUE', parameters=dict(page=2))
    assert raw['parameters']['status'] is None


def test_required_empty_delta_is_kept_and_values_are_not_reinterpreted():
    assert wire.project_optional_nulls(dict(action='CONTINUE', parameters=dict(page=None, status=None)), SCHEMA) == dict(action='CONTINUE', parameters={})
    primitive = dict(type='object', properties=dict(zero=dict(type='number'), flag=dict(type='boolean')), required=[])
    assert wire.project_optional_nulls(dict(zero=0, flag=False), primitive) == dict(zero=0, flag=False)


@pytest.mark.parametrize('raw', [dict(action='CONTINUE'), dict(action='CONTINUE', parameters=None), dict(action='CONTINUE', parameters={}, page=2)])
def test_missing_required_null_required_and_undeclared_keys_still_fail_closed(raw):
    with pytest.raises(ValueError):
        wire.project_optional_nulls(raw, SCHEMA)


def test_handler_wrapper_enables_only_the_read_resolver_and_projects_without_changing_values():
    transport = SimpleNamespace(response_tool_payload=lambda raw, effort: copy.deepcopy(raw), response_as_chat=lambda body: copy.deepcopy(body))
    wire.enable_read_tool_strict_wire(transport)
    payload = transport.response_tool_payload(dict(tools=[dict(name='resolve_read_turn', parameters=SCHEMA, strict=False),
        dict(name='other_function', parameters=SCHEMA, strict=False)]), 'low')
    assert payload['tools'][0]['strict'] is True and payload['tools'][1]['strict'] is False
    arguments = json.dumps(dict(action='CONTINUE', parameters=dict(page=2, status=None), evidence=None))
    result = transport.response_as_chat(dict(choices=[dict(message=dict(tool_calls=[dict(function=dict(name='resolve_read_turn', arguments=arguments))]))]))
    actual = json.loads(result['choices'][0]['message']['tool_calls'][0]['function']['arguments'])
    assert actual == dict(action='CONTINUE', parameters=dict(page=2))


def test_two_handler_threads_do_not_share_schema_state():
    import concurrent.futures
    import threading
    transport = SimpleNamespace(response_tool_payload=lambda raw, effort: copy.deepcopy(raw), response_as_chat=lambda body: copy.deepcopy(body))
    wire.enable_read_tool_strict_wire(transport)
    barrier = threading.Barrier(2)

    def caller(field):
        schema = dict(type='object', properties={field: dict(type='string')}, required=[field])
        transport.response_tool_payload(dict(tools=[dict(name='resolve_read_turn', parameters=schema)]), 'low')
        barrier.wait(timeout=5)
        result = transport.response_as_chat(dict(choices=[dict(message=dict(tool_calls=[dict(function=dict(name='resolve_read_turn', arguments=json.dumps({field: field})))]))]))
        return json.loads(result['choices'][0]['message']['tool_calls'][0]['function']['arguments'])

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(caller, ['one', 'two']))
    assert results == [dict(one='one'), dict(two='two')]
