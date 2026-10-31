"""Find one complete, exact control label in a saved screenshot (no UI input).

Usage: python find_control.py IMAGE --label 全部指标
All coordinates are relative to IMAGE. No substring/proportional click targeting
is permitted: a merged recognition such as 图表表格 is not an exact 表格 match.
"""
from __future__ import annotations

import argparse
import contextlib
import json
import math
from pathlib import Path
import sys
import unicodedata


def normalize_label(value):
    return "".join(unicodedata.normalize("NFKC", str(value)).split())


def choose_control(rows, label, width, height, minimum=0.90):
    expected = normalize_label(label)
    if not expected:
        return {"ok": False, "error": {"code": "INVALID_LABEL", "message": "控件文字不能为空"}}
    matches = [row for row in rows or [] if normalize_label(row[1]) == expected]
    if not matches:
        return {"ok": False, "error": {"code": "NO_MATCH", "message": "未识别到完整匹配的控件文字：" + label}}
    if len(matches) != 1:
        return {"ok": False, "error": {"code": "AMBIGUOUS", "message": "发现多个相同控件，不能唯一定位：" + label}}
    raw_box, text, confidence = matches[0]
    confidence = float(confidence)
    if not math.isfinite(confidence) or confidence < minimum:
        return {"ok": False, "error": {"code": "LOW_CONFIDENCE", "message": "控件识别置信度不足：" + label}}
    box = [[float(x), float(y)] for x, y in raw_box]
    if len(box) != 4 or any(not math.isfinite(v) for point in box for v in point):
        return {"ok": False, "error": {"code": "INVALID_BBOX", "message": "控件识别边界无效"}}
    x0, y0 = min(p[0] for p in box), min(p[1] for p in box)
    x1, y1 = max(p[0] for p in box), max(p[1] for p in box)
    if x0 < 0 or y0 < 0 or x1 > width or y1 > height or x1 <= x0 or y1 <= y0:
        return {"ok": False, "error": {"code": "INVALID_BBOX", "message": "控件识别边界超出截图"}}
    return {"ok": True, "match": {"center": [round((x0 + x1) / 2, 3), round((y0 + y1) / 2, 3)],
            "bbox": box, "text": str(text), "confidence": confidence}, "imageSize": [width, height]}


def locate_control(image_path: Path, label: str):
    if not image_path.is_file():
        return {"ok": False, "error": {"code": "IMAGE_NOT_FOUND", "message": "定位截图不存在"}}
    with contextlib.redirect_stdout(sys.stderr):
        from PIL import Image
        import numpy as np
        from rapidocr_onnxruntime import RapidOCR
        with Image.open(image_path) as opened:
            image = opened.convert("RGB")
        # Preserve full-window detail rather than aggressively downscale small labels.
        engine = RapidOCR(det_limit_side_len=2048, det_limit_type="max")
        rows, _ = engine(np.asarray(image)[:, :, ::-1].copy())
        result = choose_control(rows, label, image.width, image.height)
        if result.get("error", {}).get("code") == "LOW_CONFIDENCE":
            # Only refine a unique complete-text candidate. Never search a crop
            # after a missing/ambiguous/fuzzy match or accept reduced confidence.
            candidate = next(row for row in rows if normalize_label(row[1]) == normalize_label(label))
            original_box = candidate[0]
            x0, y0 = min(p[0] for p in original_box), min(p[1] for p in original_box)
            x1, y1 = max(p[0] for p in original_box), max(p[1] for p in original_box)
            margin = max(16, math.ceil(y1 - y0))
            left, top = max(0, math.floor(x0) - margin), max(0, math.floor(y0) - margin)
            right, bottom = min(image.width, math.ceil(x1) + margin), min(image.height, math.ceil(y1) + margin)
            if right > left and bottom > top:
                crop = image.crop((left, top, right, bottom))
                scale = 3
                enlarged = crop.resize((crop.width * scale, crop.height * scale), Image.Resampling.LANCZOS)
                refined_rows, _ = engine(np.asarray(enlarged)[:, :, ::-1].copy())
                refined = choose_control(refined_rows, label, enlarged.width, enlarged.height)
                if refined.get("ok"):
                    match = refined["match"]
                    mapped_box = [[point[0] / scale + left, point[1] / scale + top] for point in match["bbox"]]
                    cx, cy = match["center"][0] / scale + left, match["center"][1] / scale + top
                    # The refined glyph must occupy the initially found label,
                    # not another copy elsewhere in the surrounding crop.
                    if x0 - 3 <= cx <= x1 + 3 and y0 - 3 <= cy <= y1 + 3:
                        result = choose_control([(mapped_box, match["text"], match["confidence"])], label, image.width, image.height)
                        result["refinement"] = {"method": "unique-exact-local-crop", "scale": scale,
                                                "initialConfidence": float(candidate[2]), "initialBbox": original_box}
        print(json.dumps({"label": label, "result": result}, ensure_ascii=False, allow_nan=False), file=sys.stderr)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("--label", required=True)
    args = parser.parse_args()
    try:
        result = locate_control(args.image, args.label)
    except Exception as exc:
        print(f"find_control: {type(exc).__name__}: {exc}", file=sys.stderr)
        result = {"ok": False, "error": {"code": "OCR_ERROR", "message": "控件文字识别失败，已停止"}}
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    return 0 if result.get("ok") else 2


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())
