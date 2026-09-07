# ML-YOLO-BOUNDARY-REVIEW-20260907

- Status: review; scope is released by merging this change. Owner: current Codex task.
- Base SHA: `cdd3d68fdc3a7667eed58dc9d93ddb53dc0a55cd`.
- Scope: `backend/python/label_qc/services/v8_pipeline.py`, `backend/python/tests/test_label_qc_v8_pipeline.py`, `docs/specs/label-qc-boundary-review.md`, this dated archive and the removed ACTIVE entry.
- Correction: retain review for shallow top-boundary single-class observations instead of reopening an unsupported missing-label claim. Original R7 weights retained.
- Validation: `python -m pytest tests/test_label_qc_v8_pipeline.py tests/test_label_qc_screening.py -q` passed 77 tests. Hash-bound 344-photo paired replay had unchanged boxes, two intended missing-color-to-review transitions and zero unexpected transitions; all seven adjudicated defects retained location-matched hits. Details and evidence hashes are in the specification.
- The separately trained tray model failed the 200-photo stress gate (4 to 8 flagged photos) and is rejected. Edge-label retraining is also rejected. Neither model is approved for upload or activation.
- No production mutation or deployment is attested by this pre-merge archive. Deployment must use clean exact main and the official Python release entry after applicable checks; model directory remains R7.
