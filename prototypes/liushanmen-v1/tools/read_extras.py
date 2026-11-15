"""Export the collector's extra read-only Blacklake resources (batch 3) for the prototype.

Reads the latest committed snapshot exactly like read_source.py (read-only SQLite, hash verified,
factory binding checked) and writes private/extras-current.json. Person fields were already
stripped by the collector before storage; this only selects resource keys.
"""
import json, os
from pathlib import Path
from read_source import read

ROOT = Path(__file__).resolve().parents[1]
EXTRA_KEYS = [
    'project_cost', 'project_cost_detail',
    'sale_order', 'purchase_order', 'purchase_requirement', 'customer', 'vendor',
    'customer_price', 'vendor_price',
    'receivable', 'receipt', 'payable', 'payment', 'customer_ledger', 'vendor_ledger', 'invoicing',
    'inspection_order', 'inspection_result', 'inspection_item', 'inspection_spec',
    'collaborative_task', 'outsource_task', 'outsource_deliver_return', 'outsource_receive_return',
    'owner_project', 'equipment', 'operation_log',
]


def main():
    v, p = read()
    binding = json.loads((ROOT / 'private/binding.json').read_text('utf8'))
    if str(v['source_factory_id']) != binding['sourceFactoryId']:
        raise ValueError('SOURCE_FACTORY_MISMATCH')
    data = p['data']
    resources = {k: data.get(k) for k in EXTRA_KEYS if isinstance(data.get(k), list)}
    out = {
        'factoryId': 'LIUSHANMEN', 'sourceFactoryId': binding['sourceFactoryId'],
        'sourceSystem': 'Blacklake OpenAPI', 'sourceAccount': p.get('sourceAccount', 'default'),
        'revision': p['contentHash'], 'checkedAt': p['checkedAt'],
        'extraState': p.get('extraState'),
        'missingKeys': [k for k in EXTRA_KEYS if k not in resources],
        'counts': {k: len(rows) for k, rows in resources.items()},
        'resources': resources,
    }
    target = ROOT / 'private/extras-current.json'
    temp = target.with_suffix('.tmp')
    temp.write_text(json.dumps(out, ensure_ascii=False), encoding='utf8')
    os.replace(temp, target)
    print(json.dumps({'revision': out['revision'][:12], 'checkedAt': out['checkedAt'], 'counts': out['counts'], 'missing': out['missingKeys']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
