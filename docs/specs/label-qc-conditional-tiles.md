# Conditional tray supplementation

The optional bundle manifest boolean `trayTiledSupplementEnabled` defaults to
`false`. Missing flags retain the existing R7 path; non-boolean values fail model
loading. This feature does not change model weights or the label threshold.

When enabled, portrait images with fewer than eight primary detections receive
two upper overlapping crops, each 65% of the source width and height, anchored
at the left and right edges. The existing cascade already supplements the lower
band; this optional pass focuses on the upper band. The existing
primary detector processes them at its fixed 0.60 confidence. This reuses the
primary count already computed by the cascade, with no extra full-image pass.

Internal crop-edge detections are rejected before mapping coordinates back to
the source image. The cascade deduplicates crop proposals, applies the existing
far-background exclusion to new proposals, rejects overlap with existing boxes,
and only adds boxes within the remaining 60-tray capacity. Existing detections
are never replaced. Label routes are rebuilt in the same confidence order as
screening. The new `candidate_tiled_supplement` source participates in existing
candidate fail-closed policies.

The runtime audit reports `tiledSupplementTriggered` and
`tiledSupplementBoxesKept`. Detector errors propagate instead of silently
pretending supplementation succeeded.

## Validation and activation boundary

Target tests cover default-off behavior, malformed manifest flags, orientation
and count routing, coordinate mapping, duplicate crops, internal crop edges,
background/overlap rejection, capacity, label-route alignment, and error
propagation. Existing v8 and screening regressions must also pass.

The initial offline prototype recovered two reviewed targets in 40 development
images while preserving baseline boxes. These images include training sources;
they are not an independent accuracy estimate or production activation proof.
Complete effect and paired latency measurements must bind the final source and
bundle hashes. Include triggered cases as a separate latency group: aggregate
percentiles can hide the cost of rare supplemental passes. Do not enable this
flag in a production bundle unless the applicable recall, normal-image and
latency gates pass. Leaving the flag absent is the compatibility path.

The two-upper-crop revision retains both recoveries in the same development
set while halving additional detector calls. It intentionally removes lower
crop coverage; this development result does not establish equivalence on new
images. The first paired timing run passes the original percentile calculation,
but has large control variation and seven of nine triggered pairs exceed 15%
overhead (median 27.2%). Stable latency acceptance remains unconfirmed. Keep the
feature disabled pending representative, controlled measurements; a noisy
aggregate percentile is insufficient activation evidence.
