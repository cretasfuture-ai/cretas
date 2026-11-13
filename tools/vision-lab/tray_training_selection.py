"""Validate the actual tray training image selection before model loading."""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import yaml


def validate_training_selection(root: Path, data_yaml: Path) -> None:
    """Check consumed images, rather than trusting an exclusion claim."""
    provenance_path = root / "provenance.json"
    if not provenance_path.is_file():
        raise RuntimeError("dataset training provenance is missing")
    provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
    if not isinstance(provenance, dict):
        raise RuntimeError("dataset training provenance is not an object")
    rows = provenance.get("rows") or []
    train_rows: dict[str, dict[str, Any]] = {}
    for row in rows:
        if row.get("split") != "train":
            continue
        stem = str(row.get("stem") or "")
        if not stem or stem in train_rows:
            raise RuntimeError("dataset training provenance stem is empty or duplicated")
        if "training_excluded" in row and type(row["training_excluded"]) is not bool:
            raise RuntimeError(f"training_excluded provenance flag is not boolean: {stem}")
        train_rows[stem] = row
    config = yaml.safe_load(data_yaml.read_text(encoding="utf-8"))
    if not isinstance(config, dict):
        raise RuntimeError("dataset training selector is missing")
    configured_root = Path(str(config.get("path") or root))
    if not configured_root.is_absolute():
        configured_root = root / configured_root
    if configured_root.resolve() != root:
        raise RuntimeError("dataset training root boundary drift")
    selectors = config.get("train")
    selectors = selectors if isinstance(selectors, list) else [selectors]
    selected: list[Path] = []
    image_suffixes = {".avif", ".bmp", ".dng", ".heic", ".heif", ".jp2", ".jpeg",
                      ".jpeg2000", ".jpg", ".mpo", ".png", ".tif", ".tiff", ".webp", ".pfm"}
    for entry in selectors:
        if not isinstance(entry, str) or not entry.strip():
            raise RuntimeError("dataset training selector is missing")
        selector = Path(entry)
        selector = selector if selector.is_absolute() else root / selector
        selector = selector.resolve()
        if not selector.is_relative_to(root):
            raise RuntimeError("dataset training selector escapes its provenance root")
        if selector.is_dir():
            selected.extend(p.resolve() for p in selector.rglob("*") if p.is_file() and p.suffix.lower() in image_suffixes)
        elif selector.is_file() and selector.suffix == ".txt":
            for line in selector.read_text(encoding="utf-8").splitlines():
                if line.strip():
                    image = Path(line.strip())
                    if not image.is_absolute() and not line.strip().startswith("./"):
                        raise RuntimeError("dataset training list requires absolute or ./ relative image paths")
                    selected.append((image if image.is_absolute() else selector.parent / image).resolve())
        else:
            raise RuntimeError(f"dataset training selector is invalid: {selector}")
    if not selected:
        raise RuntimeError("dataset training selection is empty")
    for image in selected:
        if not image.is_file() or image.suffix.lower() not in image_suffixes:
            raise RuntimeError(f"dataset training image is invalid: {image}")
        row = train_rows.get(image.stem)
        if row is None or image.parent != (root / "images" / "train").resolve():
            raise RuntimeError(f"dataset selected image is not bound to training provenance: {image}")
        if row.get("training_excluded") is True:
            raise RuntimeError(f"dataset selected excluded training image: {image.stem}")
