from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from PIL import Image
from find_control import locate_control


def candidate(confidence=.8, text="指标名称", left=10, scale=1):
    return ([[left * scale, 20 * scale], [(left + 100) * scale, 20 * scale],
             [(left + 100) * scale, 40 * scale], [left * scale, 40 * scale]], text, confidence)


class ControlLocatorTests(unittest.TestCase):
    def run_with_ocr(self, outputs):
        engine = Mock(side_effect=[(rows, None) for rows in outputs])
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "blank.png"
            Image.new("RGB", (1280, 720), "white").save(path)
            with patch("rapidocr_onnxruntime.RapidOCR", return_value=engine):
                result = locate_control(path, "指标名称")
        return result, engine.call_count

    def test_unique_complete_low_confidence_gets_local_confirmation(self):
        result, calls = self.run_with_ocr([[candidate()], [candidate(.99, scale=3)]])
        self.assertTrue(result["ok"])
        self.assertEqual(calls, 2)
        self.assertEqual(result["match"]["center"], [60, 30])
        self.assertGreaterEqual(result["match"]["confidence"], .90)

    def test_ambiguous_complete_labels_are_not_refined(self):
        result, calls = self.run_with_ocr([[candidate(), candidate(left=300)]])
        self.assertEqual(result["error"]["code"], "AMBIGUOUS")
        self.assertEqual(calls, 1)

    def test_partial_text_is_not_refined(self):
        result, calls = self.run_with_ocr([[candidate(text="指标名")]])
        self.assertEqual(result["error"]["code"], "NO_MATCH")
        self.assertEqual(calls, 1)

    def test_refinement_cannot_lower_confidence_threshold(self):
        result, calls = self.run_with_ocr([[candidate()], [candidate(.89, scale=3)]])
        self.assertEqual(result["error"]["code"], "LOW_CONFIDENCE")
        self.assertEqual(calls, 2)


if __name__ == "__main__":
    unittest.main()
