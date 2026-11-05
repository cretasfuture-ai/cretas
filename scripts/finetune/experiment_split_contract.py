"""Reject text and expression-family leakage before an experiment loads weights."""
from difflib import SequenceMatcher
from flywheel_reviewed import normalized


def validate_splits(train, validation, test):
    splits = {'train': train, 'validation': validation, 'test': test}
    for name, rows in splits.items():
        if not rows or any(not r.get('text', '').strip() or not r.get('group_id') for r in rows):
            raise ValueError(name + ' needs nonempty text and explicit family IDs')
    for a, b in [('train', 'validation'), ('train', 'test'), ('validation', 'test')]:
        if {r['group_id'] for r in splits[a]} & {r['group_id'] for r in splits[b]}:
            raise ValueError('Expression family crosses ' + a + '/' + b)
        right = [normalized(r['text']) for r in splits[b]]
        for row in splits[a]:
            text = normalized(row['text'])
            if any(text == other or SequenceMatcher(None, text, other).ratio() >= .85 for other in right):
                raise ValueError('Duplicate/near-duplicate crosses ' + a + '/' + b)
