"""Explicitly reviewed training intake; evaluation evidence is never training truth."""
from __future__ import annotations

import hashlib
import json
import unicodedata
from difflib import SequenceMatcher
from pathlib import Path
from typing import Dict, List


def _normalize_catalog_label(label: str) -> str:
    """Use the runtime catalog when available, including from standalone CLI."""
    try:
        from ai.factory_catalog import normalize_training_label
    except ModuleNotFoundError:
        import sys
        backend_python = Path(__file__).resolve().parents[2] / 'backend' / 'python'
        if str(backend_python) not in sys.path:
            sys.path.insert(0, str(backend_python))
        from ai.factory_catalog import normalize_training_label
    return normalize_training_label(label)


def normalized(text: str) -> str:
    return ''.join(unicodedata.normalize('NFKC', text).casefold().split())


def text_hash(text: str) -> str:
    return hashlib.sha256(normalized(text).encode('utf-8')).hexdigest()


def read_jsonl(path: Path) -> List[Dict]:
    return [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]


def label_catalog(catalog: Dict) -> List[str]:
    if not isinstance(catalog, dict):
        raise ValueError('Label catalog must be an object')
    if 'labels' in catalog:
        labels = catalog['labels']
    else:
        mapping = catalog.get('label_to_id')
        if not isinstance(mapping, dict) or not mapping:
            raise ValueError('label_to_id must be a nonempty mapping')
        if any(type(v) is not int for v in mapping.values()) or set(mapping.values()) != set(range(len(mapping))):
            raise ValueError('Label IDs must be unique contiguous integers')
        labels = list(mapping)
    if (not isinstance(labels, list) or not labels or
            any(not isinstance(x, str) or not x.strip() for x in labels) or len(labels) != len(set(labels))):
        raise ValueError('Labels must be a unique nonempty string list')
    if 'num_labels' in catalog and catalog['num_labels'] != len(labels):
        raise ValueError('Label count mismatch')
    return labels


def reject_conflicts(records: List[Dict]) -> None:
    labels = {}
    for row in records:
        key = normalized(row['text'])
        if key in labels and labels[key] != row['label']:
            raise ValueError('Conflicting training labels across sources; adjudication required')
        labels[key] = row['label']


class HoldoutGuard:
    def __init__(self, path: Path):
        self.rows = read_jsonl(path)
        if not self.rows or any(not isinstance(r.get('text'), str) or not r['text'].strip() for r in self.rows):
            raise ValueError('Holdout must contain nonempty text rows')
        self.texts = [normalized(r['text']) for r in self.rows]
        self.groups = {r['group_id'] for r in self.rows if r.get('group_id')}
        from factory_accuracy_support import OverlapIndex
        self._index = OverlapIndex(self.rows)

    def overlaps(self, row: Dict) -> bool:
        return self._index.overlaps(row)


def load_reviewed(path: Path, labels_path: Path, holdout_path: Path) -> List[Dict]:
    """Only reviewed, non-evaluation rows in the current label catalog can train.

    Factory records additionally pass the existing per-factory consent/withdrawal
    rules. The exporter MUST still apply the shared protected-term gate to the
    complete merged output, including manually written/synthetic rows.
    """
    catalog = json.loads(labels_path.read_text(encoding='utf-8-sig'))
    labels = label_catalog(catalog)
    guard = HoldoutGuard(holdout_path)
    result, seen = [], set()
    for row in read_jsonl(path):
        if not isinstance(row.get('text'), str) or not row['text'].strip():
            raise ValueError('Flywheel text must be nonempty')
        review = row.get('review') or {}
        provenance = row.get('provenance') or {}
        if row.get('split') != 'train' or review.get('status') != 'approved':
            continue
        if not review.get('reviewer') or not review.get('basis') or not row.get('record_id'):
            raise ValueError('Approved records need reviewer, basis and record_id')
        if not row.get('group_id') or not provenance.get('evidence_refs'):
            raise ValueError('Approved records need group_id and evidence_refs')
        if provenance.get('source_kind') not in ('synthetic', 'factory'):
            raise ValueError('Evaluation/unknown provenance cannot train')
        raw_label = row.get('label')
        try:
            label = _normalize_catalog_label(raw_label)
        except ValueError as exc:
            raise ValueError(str(exc)) from exc
        if label not in labels and raw_label not in labels:
            raise ValueError('Flywheel label is absent from current model catalog')
        if guard.overlaps(row):
            raise ValueError('Flywheel training record overlaps protected holdout')
        text = row['text'].strip()
        if row.get('factory_id') or provenance['source_kind'] == 'factory':
            from training_consent_gate import apply_utterance_rule
            approved = apply_utterance_rule([{'factory_id': row.get('factory_id'), 'user_input': text}], ('user_input',))
            if not approved:
                continue
            text = approved[0]['user_input']
        candidate = dict(row, text=text)
        if guard.overlaps(candidate):
            raise ValueError('Redacted flywheel record overlaps holdout')
        fingerprint = text_hash(text)
        if fingerprint in seen:
            raise ValueError('Duplicate reviewed flywheel utterance; resolve labels first')
        seen.add(fingerprint)
        result.append({'text': text, 'label': label, 'source': 'flywheel_reviewed',
                       'confidence': 1.0, 'group_id': row['group_id'],
                       'flywheel_evidence': {'record_id': row['record_id'], 'review': review,
                                             'provenance': provenance, 'text_sha256': fingerprint}})
    return result


def exclude_holdout(records: List[Dict], path: Path) -> List[Dict]:
    """Apply to ALL sources, not just the new flywheel file."""
    guard = HoldoutGuard(path)
    return [r for r in records if not guard.overlaps(r)]
