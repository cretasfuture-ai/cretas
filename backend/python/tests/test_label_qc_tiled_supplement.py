from types import SimpleNamespace

import numpy as np
import pytest

from label_qc.services.v8_pipeline import (
    _V8CascadeModels, _is_candidate_tray_source, _tiled_supplement_enabled,
)
from label_qc.services.yolo_detector import Detection


def test_manifest_defaults_off_and_requires_explicit_boolean():
    assert _tiled_supplement_enabled({}) is False
    assert _tiled_supplement_enabled({"trayTiledSupplementEnabled": True}) is True
    assert _tiled_supplement_enabled({"trayTiledSupplementEnabled": False}) is False


@pytest.mark.parametrize("value", [None, 0, 1, "false", "true", [], {}])
def test_invalid_manifest_flag_fails_closed(value):
    with pytest.raises(RuntimeError, match="must be a boolean"):
        _tiled_supplement_enabled({"trayTiledSupplementEnabled": value})


class Production:
    def __init__(self, shape, original, tiles):
        self.shape, self.original, self.tiles = shape, original, iter(tiles)
        self.calls = []

    def detect_trays(self, image, confidence):
        self.calls.append((image.shape, confidence))
        if image.shape == self.shape:
            return self.original
        assert confidence == 0.60
        return next(self.tiles)


def cascade(production, enabled=True, full=None):
    return _V8CascadeModels(
        production=production,
        tray_supplement=SimpleNamespace(detect=lambda image, conf: []),
        production_label=SimpleNamespace(),
        candidate_label=SimpleNamespace(reset_stats=lambda: None),
        tiled_supplement_enabled=enabled,
        tray_full_frame_supplement=full,
    )


@pytest.mark.parametrize("enabled,count,shape", [
    (False, 1, (1000, 500, 3)),
    (True, 8, (1000, 500, 3)),
    (True, 1, (500, 1000, 3)),
    (True, 1, (500, 500, 3)),
])
def test_default_full_layout_and_landscape_do_not_run_tiles(enabled, count, shape):
    original = [Detection(20, 100, 150, 300, .9, 0)] * count
    production = Production(shape, original, [])
    model = cascade(production, enabled)
    assert model.detect_trays(np.zeros(shape, dtype=np.uint8), .6) == original
    assert len(production.calls) == 1


def test_duplicate_tiles_map_to_original_coordinates_and_keep_label_routes():
    original = Detection(20, 100, 150, 300, .8, 0)
    # Same physical tray appears in all four overlapping tiles.
    tiles = [[Detection(180-x, 400-y, 320-x, 600-y, .95, 0)]
             for x, y in [(0, 0), (175, 0), (0, 350), (175, 350)]]
    production = Production((1000, 500, 3), [original], tiles)
    model = cascade(production)
    rows = model.detect_trays(np.zeros(production.shape, dtype=np.uint8), .6)
    assert rows[0] is original
    assert len(rows) == 2
    assert rows[1].as_xyxy() == [180, 400, 320, 600]
    assert len(model._label_routes) == 2
    assert model.last_raw_detections[-1]["source"] == "candidate_tiled_supplement"
    assert model.last_stats["tiledSupplementBoxesKept"] == 1


def test_internal_tile_cuts_overlap_and_far_background_are_excluded():
    original = Detection(20, 100, 150, 300, .8, 0)
    production = Production((1000, 500, 3), [original], [
        [original, Detection(200, 300, 325, 450, .9, 0),
         Detection(200, 25, 240, 45, .95, 0)],
        [Detection(0, 300, 120, 450, .9, 0)],
        [Detection(170, 0, 280, 150, .9, 0)],
        [],
    ])
    model = cascade(production)
    assert model.detect_trays(np.zeros(production.shape, dtype=np.uint8), .6) == [original]


def test_capacity_never_displaces_existing_trays_or_runs_unneeded_tiles():
    original = Detection(20, 100, 150, 300, .8, 0)
    extra = [Detection(180, 400, 320, 600, .9, i + 1) for i in range(59)]
    production = Production((1000, 500, 3), [original], [])
    model = cascade(production, full=SimpleNamespace(detect=lambda image, conf: extra))
    rows = model.detect_trays(np.zeros(production.shape, dtype=np.uint8), .6)
    assert len(rows) == 60
    assert rows[0] is original
    assert len(production.calls) == 1


def test_tiled_detections_use_candidate_fail_closed_policies():
    assert _is_candidate_tray_source("candidate_tiled_supplement")


def test_tile_detector_failure_is_not_silently_ignored():
    production = Production((1000, 500, 3), [], [])
    model = cascade(production)
    with pytest.raises(StopIteration):
        model.detect_trays(np.zeros(production.shape, dtype=np.uint8), .6)
