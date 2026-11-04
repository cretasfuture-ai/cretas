"""Bind incremental class IDs to the existing classifier head before loading weights."""
import hashlib
import json
import os
from pathlib import Path
from flywheel_reviewed import label_catalog


def _normalize_catalog_label(label):
    try:
        from ai.factory_catalog import normalize_training_label
    except ModuleNotFoundError:
        import sys
        backend_python = Path(__file__).resolve().parents[2] / 'backend' / 'python'
        if str(backend_python) not in sys.path:
            sys.path.insert(0, str(backend_python))
        from ai.factory_catalog import normalize_training_label
    return normalize_training_label(label)


def validate_replay_inputs(new_data, old_data):
    if not old_data:
        return
    new, old = Path(new_data), Path(old_data)
    if (new.resolve() == old.resolve() or os.path.samefile(new, old) or
            hashlib.sha256(new.read_bytes()).digest() == hashlib.sha256(old.read_bytes()).digest()):
        raise ValueError('New data and replay baseline must be distinct corpora')


def read_mapping(path):
    data = json.loads(Path(path).read_text(encoding='utf-8-sig'))
    label_catalog(data)
    mapping = data.get('label_to_id')
    if not isinstance(mapping, dict):
        raise ValueError('Training requires explicit label_to_id, not a label list')
    label_catalog({'label_to_id': mapping})
    if data.get('id_to_label') is not None and {str(v): k for k,v in mapping.items()} != {str(k):v for k,v in data['id_to_label'].items()}:
        raise ValueError('Forward/reverse mapping mismatch')
    return mapping


def resolve_mapping(model_path, provided, records):
    model = Path(model_path)
    if not model.is_dir() or not (model/'config.json').is_file():
        raise ValueError('Incremental training requires an existing local model directory')
    mapping = read_mapping(model/'label_mapping.json')
    if provided and read_mapping(provided) != mapping:
        raise ValueError('Requested label IDs differ from the existing model head')
    config = json.loads((model/'config.json').read_text(encoding='utf-8-sig'))
    count = config.get('num_labels', len(config.get('id2label', {})))
    if count != len(mapping):
        raise ValueError('Model head size and label mapping differ')
    reverse = {str(v): k for k,v in mapping.items()}
    configured = config.get('id2label')
    if configured is not None:
        configured = {str(k): v for k,v in configured.items()}
        generic = configured == {str(i): 'LABEL_'+str(i) for i in range(len(mapping))}
        if not generic and configured != reverse:
            raise ValueError('Model config id2label differs from sidecar class IDs')
    configured_forward = config.get('label2id')
    if configured_forward is not None:
        generic_forward = configured_forward == {'LABEL_'+str(i): i for i in range(len(mapping))}
        if not generic_forward and configured_forward != mapping:
            raise ValueError('Model config label2id differs from sidecar class IDs')
    # Keep the complete historical head untouched, but never allow a retired
    # label into a new training corpus. Confirmed aliases are normalized before
    # the existing head-ID membership check.
    for record in records:
        record['label'] = _normalize_catalog_label(record.get('label'))
    unknown = {r['label'] for r in records} - set(mapping)
    if unknown:
        raise ValueError('Training data contains labels absent from existing model')
    return mapping
