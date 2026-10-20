"""Crop real, synthetic-demo screenshots; never reconstruct application UI."""
import argparse
import hashlib
import json
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--captures', type=Path, required=True)
    parser.add_argument('--business-evidence', type=Path, required=True)
    args = parser.parse_args()
    target = ROOT / 'platform/assets/onboarding'
    target.mkdir(parents=True, exist_ok=True)
    receipts = []

    def export(source, name, crop=None, public=True):
        with Image.open(source) as image:
            if crop:
                assert 0 <= crop[0] < crop[2] <= image.width
                assert 0 <= crop[1] < crop[3] <= image.height
                image = image.crop(crop)
            image = image.convert('RGB')
            capture = args.captures / (name + '.png')
            if source.resolve() != capture.resolve():
                image.save(capture)
            if public:
                image.save(target / (name + '.webp'), quality=90, method=6)
            receipts.append({'name': name, 'source': str(source),
                'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                'cropPixels': crop, 'size': image.size,
                'sourceType': 'Local synthetic business demonstration; saved screenshot'})

    # Existing captures are reused byte-for-byte in content; only encoding changes.
    for name in ['conversation', 'indicators']:
        export(args.captures / (name + '.png'), name)
    trace = args.business_evidence / 'p15-final-boxed-trace.png'
    cost = args.business_evidence / 'boxed-batch-cost.png'
    assert Image.open(trace).size == (1318, 1836), 'Review crop coordinates for a new source'
    assert Image.open(cost).size == (1318, 1895), 'Review crop coordinates for a new source'
    export(trace, 'traceability', (104, 313, 1278, 1650))
    export(trace, 'trace-upstream', (115, 850, 620, 1077), public=False)
    export(trace, 'trace-output', (501, 313, 1278, 555), public=False)
    export(trace, 'trace-shipment', (104, 1470, 1278, 1650), public=False)
    export(cost, 'cost-detail', (260, 913, 757, 1204), public=False)
    export(cost, 'cost', (260, 913, 757, 1204))
    (args.captures / 'asset-provenance.json').write_text(
        json.dumps({'status': 'PREPARED', 'transforms': 'Rectangular crops and format encoding only',
            'businessMutations': 0, 'assets': receipts}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'status': 'PREPARED', 'publicAssets': 4, 'businessMutations': 0}))

if __name__ == '__main__':
    main()
