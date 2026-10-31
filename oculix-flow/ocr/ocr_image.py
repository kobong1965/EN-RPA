"""Recognize local screenshot text; write raw OCR lines as UTF-8 JSON."""

from __future__ import annotations

import argparse
import contextlib
import json
from pathlib import Path
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path, help="Path to a local screenshot")
    parser.add_argument("--output", type=Path, help="Also save the JSON to this path")
    parser.add_argument("--expected-rows", type=int)
    parser.add_argument("--min-confidence", type=float, default=0.0)
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    try:
        if not args.image.is_file():
            raise FileNotFoundError(f"Image not found: {args.image}")
        # Keep any library status messages away from the JSON output stream.
        with contextlib.redirect_stdout(sys.stderr):
            from PIL import Image
            import numpy as np
            from rapidocr_onnxruntime import RapidOCR

            image = Image.open(args.image).convert("RGB")
            width, height = image.size
            engine = RapidOCR()
            # RapidOCR's ndarray input convention is BGR, like OpenCV.
            result, elapsed = engine(np.asarray(image)[:, :, ::-1].copy())
        lines = []
        for box, text, confidence in result or []:
            points = [[float(x), float(y)] for x, y in box]
            lines.append({
                "text": text,
                "confidence": float(confidence),
                "bbox": points,
            })
        lines.sort(key=lambda row: (
            sum(point[1] for point in row["bbox"]) / len(row["bbox"]),
            min(point[0] for point in row["bbox"]),
        ))
        payload = {
            "image": str(args.image.resolve()),
            "width": width,
            "height": height,
            "engine": "rapidocr-onnxruntime",
            "postprocessing": "reading-order sort only; no vocabulary correction",
            "lines": lines,
            "validation": {
                "row_count_matches": args.expected_rows is None or len(lines) == args.expected_rows,
                "confidence_passes": bool(lines) and all(row["confidence"] >= args.min_confidence for row in lines),
                "note": "These are quality checks, not proof of text accuracy; retain the screenshot for review.",
            },
        }
        output = json.dumps(payload, ensure_ascii=False, indent=2)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(output + "\n", encoding="utf-8")
        print(output)
        if not payload["validation"]["row_count_matches"] or not payload["validation"]["confidence_passes"]:
            print("OCR requires review: unexpected row count or low confidence", file=sys.stderr)
            return 2
        return 0
    except Exception as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
