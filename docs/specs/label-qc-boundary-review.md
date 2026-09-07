# Shallow top-boundary label review

A shallow tray touching the top image boundary can hide part of a label. The v8 safety pass marks an incomplete observation `UNJUDGEABLE`, but the extended lower-compartment pass previously reopened a missing-label claim when it found only one label class. Extending downward does not recover pixels outside the photograph.

Within the existing shallow-tray recheck, a top coordinate at or below the existing one-pixel tolerance now keeps a single-class observation `UNJUDGEABLE`. Detected labels and confidences remain available and `needs_review` stays true. Finding both classes still produces `CLEAR`; interior trays retain existing missing-label decisions. No inference calls, thresholds, tray boxes, label ownership, or model weights change.

## Code-only acceptance with original R7

- Target tests: 77 passed across the v8 pipeline and screening modules. Boundary single-class, both-class, interior, tolerance and review-state behavior are covered.
- A hash-bound Mac mini replay with ONNX Runtime 1.28.0 covered 344 photos. All tray boxes were unchanged. Exactly two development trays changed from `MISSING_COLOR_LABEL` to `UNJUDGEABLE`; no other verdict changes occurred.
- Development: missing-label-flagged photos 5 to 3 across 40 photos; photos needing review stayed 30. This pool includes training sources and is not an independent accuracy estimate.
- Independent normal photos: missing-label flags stayed 0/77; photos needing review stayed 1/77.
- Hard-negative stress photos: missing-label flags stayed 4/200; photos needing review stayed 8/200. This exposed stress pool is not an independent accuracy estimate, and the four existing flags remain unresolved.
- The original 27-photo protected pool was unchanged. Separately applying the frozen human truth adjudication, all seven actual defects retained their location-matched hits in a fresh Windows paired replay. Matching requires the adjudicated verdict and containment at least 0.5, not simply any alarm elsewhere in the photograph.
- Source, input, baseline receipt and original R7 model-manifest hashes were checked. The Mac receipt SHA-256 is `09d50d925ed28bad4ebe39fd0dcb3b80b0dfc6df276bd75fbc2c1780d0ddb18d`. The unchanged R7 model manifest SHA-256 is `bba131c66380466e620ff249da2eca6a0920e121c285a73a58acffd7bdff2900`.

## Separate model experiments: rejected

A locally trained conservative tray model reduced development misses from 9 to 7. Even with the boundary correction, however, a fresh paired 200-photo stress replay increased flagged photos from 4 to 8. It is rejected for promotion. Its ONNX SHA-256 is `94eb05fc6cbb26b43924fea94397448e2cd11fc248c6d069960cf5c4824d5188`.

The model's export parity passed on 24 validation images. Paired local CPU P95 was 2.8% higher, with a dense-photo median hotspot 18.5% higher. These are measurements of the rejected model plus code correction, not measurements of this code-only release.

A separately trained edge-label candidate still failed one of three adjudicated targets and is also rejected. Those three new human reviews describe truncation and label visibility only; they are not bounding-box training truth. Originals and earlier human annotations remain unchanged.

## Evidence and release boundary

Local evidence is retained under `D:/CretasVisionLab/audits/new-photos-20260907/`: `mac-boundary-regression/r7-code-verification.json`, `mac-boundary-regression/r7-code-receipt.json`, `r7-code-defects.json`, `mac-boundary-regression/hard-receipt.json`, and the training experiment directories. Private photographs, object references and model binaries stay outside Git.

This change selects only the code correction. Production continues to use the original R7 weights. Merge and deployment are separate release steps; this acceptance record alone does not attest that either occurred.
