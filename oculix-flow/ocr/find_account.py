"""Locate one exact account in a saved Qianniu account-header screenshot.

No desktop capture or input is performed. Coordinates are relative to the input
image, even though recognition runs at 2x resolution. No fuzzy account repair is
allowed: only Unicode NFKC normalization and leading/trailing whitespace trim.
An already complete, unique low-confidence candidate may be re-read from a
small grayscale crop at 3x, still requiring the same exact complete account.
"""

from __future__ import annotations

import argparse
import contextlib
import json
import math
from pathlib import Path
import sys
import unicodedata


MIN_CONFIDENCE = 0.90
OCR_SCALE = 2
TILE_WIDTH = 480
TILE_OVERLAP = 240
ACTIVE_BLUE_RATIO = 0.50
REFINEMENT_SCALE = 3


class AccountError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


class JsonArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise AccountError("INVALID_INPUT", message)


def normalize_account(text: str) -> str:
    return unicodedata.normalize("NFKC", text).strip()


def tile_offsets(width: int) -> list[int]:
    """Cover every account tab without shrinking a very wide, short header."""
    if width <= TILE_WIDTH:
        return [0]
    offsets = list(range(0, width - TILE_WIDTH + 1, TILE_WIDTH - TILE_OVERLAP))
    if offsets[-1] != width - TILE_WIDTH:
        offsets.append(width - TILE_WIDTH)
    return offsets


def merge_tile_rows(rows):
    """Deduplicate one glyph box seen in overlapping crops, never distinct tabs."""
    merged = []
    for box, text, confidence in rows:
        x0, y0 = min(p[0] for p in box), min(p[1] for p in box)
        x1, y1 = max(p[0] for p in box), max(p[1] for p in box)
        duplicate = None
        for index, (old_box, old_text, old_confidence) in enumerate(merged):
            if normalize_account(text) != normalize_account(old_text):
                continue
            ox0, oy0 = min(p[0] for p in old_box), min(p[1] for p in old_box)
            ox1, oy1 = max(p[0] for p in old_box), max(p[1] for p in old_box)
            intersection = max(0, min(x1, ox1) - max(x0, ox0)) * max(0, min(y1, oy1) - max(y0, oy0))
            union = (x1 - x0) * (y1 - y0) + (ox1 - ox0) * (oy1 - oy0) - intersection
            if union > 0 and intersection / union >= .60:
                duplicate = index
                if confidence > old_confidence:
                    merged[index] = (box, text, confidence)
                break
        if duplicate is None:
            merged.append((box, text, confidence))
    return merged


def choose_account(rows, account: str):
    """Require exactly one complete account, including low-confidence duplicates."""
    exact = [row for row in rows or [] if normalize_account(str(row[1])) == account]
    if not exact:
        raise AccountError("ACCOUNT_NOT_FOUND", "未识别到完整匹配的账号，请确认账号栏可见且账号名称完整。")
    if len(exact) != 1:
        raise AccountError("ACCOUNT_AMBIGUOUS", "发现多个相同账号，无法唯一定位，已停止操作。")
    box, text, confidence = exact[0]
    confidence = float(confidence)
    if not math.isfinite(confidence) or confidence < MIN_CONFIDENCE:
        raise AccountError("LOW_CONFIDENCE", "账号文字识别置信度不足 0.90，已停止操作。")
    return box, normalize_account(text), confidence


def choose_account_with_refinement(image, rows, account: str, engine):
    """Re-read only an exact unique low-confidence label, never repair its text.

    Grayscale removes the selected tab's blue tint before enlarging its tiny
    white glyphs. Retaining the original box keeps the click and blue-ring
    checks tied to the whole-header observation rather than the enlarged crop.
    """
    try:
        return choose_account(rows, account), None
    except AccountError as exc:
        if exc.code != "LOW_CONFIDENCE":
            raise
    box, initial_text, initial_confidence = next(row for row in rows
        if normalize_account(str(row[1])) == account)
    if len(box) != 4 or any(not math.isfinite(float(v)) for point in box for v in point):
        raise AccountError("INVALID_BBOX", "账号精读区域无效。")
    x0, y0 = min(p[0] for p in box), min(p[1] for p in box)
    x1, y1 = max(p[0] for p in box), max(p[1] for p in box)
    if x0 < 0 or y0 < 0 or x1 > image.width or y1 > image.height or x1 <= x0 or y1 <= y0:
        raise AccountError("INVALID_BBOX", "账号精读区域超出截图范围。")
    from PIL import Image, ImageOps
    import numpy as np
    left, top = max(0, math.floor(x0) - 5), max(0, math.floor(y0) - 5)
    right, bottom = min(image.width, math.ceil(x1) + 5), min(image.height, math.ceil(y1) + 5)
    crop = ImageOps.grayscale(image.crop((left, top, right, bottom)))
    enlarged = crop.resize((crop.width * REFINEMENT_SCALE, crop.height * REFINEMENT_SCALE), Image.Resampling.LANCZOS)
    refined_rows, _ = engine(np.asarray(enlarged))
    detail = {"method": "grayscale-local", "scale": REFINEMENT_SCALE,
              "crop": [left, top, right, bottom], "initialText": initial_text,
              "initialConfidence": float(initial_confidence),
              "recognized": [{"text": row[1], "confidence": float(row[2]), "bbox": row[0]}
                             for row in refined_rows or []]}
    print(json.dumps({"accountRefinement": detail}, ensure_ascii=False, allow_nan=False), file=sys.stderr)
    refined_box, normalized, confidence = choose_account(refined_rows, account)
    if len(refined_box) != 4 or any(not math.isfinite(float(v)) for point in refined_box for v in point):
        raise AccountError("INVALID_BBOX", "账号精读返回的区域无效。")
    mapped = [[float(x) / REFINEMENT_SCALE + left, float(y) / REFINEMENT_SCALE + top]
              for x, y in refined_box]
    rx0, ry0 = min(p[0] for p in mapped), min(p[1] for p in mapped)
    rx1, ry1 = max(p[0] for p in mapped), max(p[1] for p in mapped)
    if (rx0 < left or ry0 < top or rx1 > right or ry1 > bottom or rx1 <= rx0 or ry1 <= ry0
            or abs((rx0 + rx1 - x0 - x1) / 2) > 5
            or abs((ry0 + ry1 - y0 - y1) / 2) > 5
            or not .75 <= (rx1 - rx0) / (x1 - x0) <= 1.25):
        raise AccountError("INVALID_BBOX", "账号精读位置与原始候选不一致，已停止。")
    detail["confidence"] = confidence
    return (box, normalized, confidence), detail


def selected_blue(image, box) -> tuple[bool, float]:
    """Measure the small background ring around text, excluding the text itself."""
    import numpy as np

    width, height = image.size
    x0 = max(0, math.floor(min(point[0] for point in box)))
    y0 = max(0, math.floor(min(point[1] for point in box)))
    x1 = min(width, math.ceil(max(point[0] for point in box)))
    y1 = min(height, math.ceil(max(point[1] for point in box)))
    left, top = max(0, x0 - 5), max(0, y0 - 4)
    right, bottom = min(width, x1 + 5), min(height, y1 + 4)
    rgb = np.asarray(image.crop((left, top, right, bottom)))
    ring = np.ones(rgb.shape[:2], dtype=bool)
    ring[y0 - top:y1 - top, x0 - left:x1 - left] = False
    if not ring.any():
        raise AccountError("INVALID_BBOX", "账号区域边界不足，无法确认选中状态。")
    blue = (rgb[:, :, 0] < 130) & (rgb[:, :, 2] > 170) & (rgb[:, :, 1] >= 70) & (rgb[:, :, 1] <= 190)
    ratio = float(np.count_nonzero(blue & ring) / np.count_nonzero(ring))
    return ratio >= ACTIVE_BLUE_RATIO, ratio


def locate_account(image_path: Path, requested_account: str) -> dict:
    account = normalize_account(requested_account)
    if not account:
        raise AccountError("INVALID_ACCOUNT", "账号不能为空。")
    if not image_path.is_file():
        raise AccountError("IMAGE_NOT_FOUND", "账号栏截图不存在。")
    # Model/library messages must not corrupt the single JSON result on stdout.
    with contextlib.redirect_stdout(sys.stderr):
        from PIL import Image
        import numpy as np
        from rapidocr_onnxruntime import RapidOCR

        with Image.open(image_path) as source:
            image = source.convert("RGB")
        if image.width < 1 or image.height < 1:
            raise AccountError("INVALID_IMAGE", "账号栏截图尺寸无效。")
        engine = RapidOCR()
        located = []
        offsets = tile_offsets(image.width)
        for left in offsets:
            tile = image.crop((left, 0, min(left + TILE_WIDTH, image.width), image.height))
            enlarged = tile.resize((tile.width * OCR_SCALE, tile.height * OCR_SCALE), Image.Resampling.LANCZOS)
            tile_rows, _elapsed = engine(np.asarray(enlarged)[:, :, ::-1].copy())
            for tile_box, text, confidence in tile_rows or []:
                box = [[float(x) / OCR_SCALE + left, float(y) / OCR_SCALE] for x, y in tile_box]
                located.append((box, text, float(confidence)))
        rows = merge_tile_rows(located)
        print(json.dumps({"imageSize": list(image.size), "tileOffsets": offsets,
                          "recognized": [{"text": row[1], "confidence": row[2], "bbox": row[0]} for row in rows]},
                         ensure_ascii=False, allow_nan=False), file=sys.stderr)
        (box, normalized, confidence), refinement = choose_account_with_refinement(image, rows, account, engine)
    if len(box) != 4 or any(not math.isfinite(v) for point in box for v in point):
        raise AccountError("INVALID_BBOX", "账号定位结果无效。")
    x0, y0 = min(p[0] for p in box), min(p[1] for p in box)
    x1, y1 = max(p[0] for p in box), max(p[1] for p in box)
    if x0 < 0 or y0 < 0 or x1 > image.width or y1 > image.height or x1 <= x0 or y1 <= y0:
        raise AccountError("INVALID_BBOX", "账号定位超出截图范围。")
    active, ratio = selected_blue(image, box)
    return {
        "ok": True,
        "match": {
            "center": [round((x0 + x1) / 2, 3), round((y0 + y1) / 2, 3)],
            "bbox": [[round(x, 3), round(y, 3)] for x, y in box],
            "normalizedText": normalized,
            "confidence": confidence,
            "active": active,
            "blueRatio": ratio,
        },
        "imageSize": [image.width, image.height],
        "recognitionScale": OCR_SCALE,
        "recognitionTileWidth": TILE_WIDTH,
        "refinement": refinement,
    }


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = JsonArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path, help="Saved account-header screenshot")
    parser.add_argument("--account", required=True, help="Complete account name; exact matching only")
    try:
        args = parser.parse_args()
        payload = locate_account(args.image, args.account)
        exit_code = 0
    except AccountError as exc:
        payload = {"ok": False, "error": {"code": exc.code, "message": str(exc)}, "match": None}
        exit_code = 2
    except Exception as exc:
        print(f"find_account: {type(exc).__name__}: {exc}", file=sys.stderr)
        payload = {"ok": False, "error": {"code": "OCR_ERROR", "message": "账号识别失败，已停止操作。"}, "match": None}
        exit_code = 2
    print(json.dumps(payload, ensure_ascii=False, allow_nan=False))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
