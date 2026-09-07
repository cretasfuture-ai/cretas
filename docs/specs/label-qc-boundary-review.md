# Shallow top-boundary label review

A shallow tray touching the top image boundary can hide part of a label. The v8 safety pass correctly marks an incomplete observation `UNJUDGEABLE`, but the extended lower-compartment pass previously reopened a missing-label claim when it found only one label class. Extending the crop downward does not recover pixels outside the top of the photograph.

For the existing shallow-tray recheck, a top coordinate at or below the existing one-pixel boundary tolerance now keeps a single-class observation `UNJUDGEABLE`. The detected labels and their confidences remain available, and `needs_review` stays true. Finding both classes still produces `CLEAR`; interior trays retain the existing missing-label decisions. This change adds no inference calls and does not alter detection thresholds, tray boxes, or label ownership.

## Validation

- Target tests: 77 passed across the v8 pipeline and screening modules. Cases cover each single label class at the boundary, both labels at the boundary, interior missing labels, the boundary tolerance, and the visible review state.
- Three local human adjudications confirmed truncated trays with both label classes visible. The proposed safety correction keeps all three available for review instead of making unsupported missing-color claims.
- The formal implementation matches the offline prototype on all 40 development photos. These photos include training sources and are not an independent accuracy estimate.
- A Mac mini replay used ONNX Runtime 1.28.0 and verified source, model, and image hashes. The 144-photo paired run matched the Windows outputs on all 40 common development cases. The 77 independent normal photos had zero missing-label claims on both sides. The six adjudicated defects present in that pool retained their hits; the seventh, subsequently added adjudicated defect was separately replayed on Windows and retained its hit.

## Separate model candidate

The accompanying offline tray candidate was trained locally from R7 with the same 232 training and 34 validation images, a learning rate of 0.00002, and unchanged 960-pixel input. No tiled supplement is enabled. Its artifact remains outside Git and has not been promoted by this code change.

Together with the correction, the candidate changes development matched/missed counts from 691/9 to 693/7, with no photo-level recall regression or added missing-label claim. Unmatched predictions increase from 15 to 33; these include ignored/partial regions and must not all be described as confirmed false positives. One additional development photo and two additional independent normal photos require human review. This review burden is a material tradeoff, not an accuracy gain.

The separately trained edge-label candidate did not recover all three adjudicated targets and is not selected for deployment. The production label model remains the candidate bundle's label model.

The 200-photo hard-negative stress replay, paired local CPU latency check, and exported-model parity gate are recorded separately before any model promotion. Production deployment and code merge are separate steps; this document does not attest that either occurred.

Local evidence is retained under `D:/CretasVisionLab/audits/new-photos-20260907/`: `formal-boundary-check`, `mac-boundary-regression`, `boundary-evaluation`, `conservative-training`, and `edge-label-review`. Original photographs and prior human annotations were not overwritten.
