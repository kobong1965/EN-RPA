import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location("daily_account_locator", Path(__file__).parents[1] / "ocr" / "find_account.py")
account_locator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(account_locator)


def row(left, text="示例账号:店铺", confidence=.96):
    return ([[left, 15], [left + 100, 15], [left + 100, 30], [left, 30]], text, confidence)


class AccountLocatorTests(unittest.TestCase):
    def test_tiles_cover_full_header_with_overlap(self):
        for width in (100, 480, 481, 2292, 7680):
            offsets = account_locator.tile_offsets(width)
            self.assertEqual(offsets[0], 0)
            self.assertGreaterEqual(offsets[-1] + account_locator.TILE_WIDTH, width)
            self.assertTrue(all(b - a <= 240 for a, b in zip(offsets, offsets[1:])))

    def test_only_same_position_merge_and_distinct_duplicate_rejected(self):
        merged = account_locator.merge_tile_rows([row(30, confidence=.92), row(32, confidence=.96)])
        self.assertEqual(len(merged), 1)
        self.assertEqual(account_locator.choose_account(merged, "示例账号:店铺")[2], .96)
        duplicate = account_locator.merge_tile_rows([row(30), row(300, confidence=.80)])
        with self.assertRaisesRegex(account_locator.AccountError, "多个相同账号"):
            account_locator.choose_account(duplicate, "示例账号:店铺")

    def test_complete_name_required_and_fullwidth_colon_normalized(self):
        with self.assertRaises(account_locator.AccountError):
            account_locator.choose_account([row(30, "示例账号:其他")], "示例账号:店铺")
        with self.assertRaises(account_locator.AccountError):
            account_locator.choose_account([row(30, "示例账号")], "示例账号:店铺")
        match = account_locator.choose_account([row(30, "示例账号：店铺")], "示例账号:店铺")
        self.assertEqual(match[1], "示例账号:店铺")

    def test_low_confidence_exact_candidate_is_refined_in_original_position(self):
        from PIL import Image
        image = Image.new("RGB", (200, 55), "blue")
        local_box = [[15, 15], [315, 15], [315, 60], [15, 60]]
        engine = Mock(return_value=([(local_box, "示例账号：店铺", .97)], None))
        candidate = row(30, confidence=.89)
        chosen, detail = account_locator.choose_account_with_refinement(image, [candidate], "示例账号:店铺", engine)
        self.assertEqual(chosen[0], candidate[0])
        self.assertEqual(chosen[1:], ("示例账号:店铺", .97))
        self.assertEqual(detail["method"], "grayscale-local")
        self.assertEqual(engine.call_args.args[0].ndim, 2)

    def test_missing_or_ambiguous_account_never_refines(self):
        from PIL import Image
        image = Image.new("RGB", (600, 55))
        for rows in ([row(30, "示例账号:其他", .99)], [row(30), row(300, confidence=.80)]):
            engine = Mock()
            with self.assertRaises(account_locator.AccountError):
                account_locator.choose_account_with_refinement(image, rows, "示例账号:店铺", engine)
            engine.assert_not_called()

    def test_refined_wrong_suffix_low_confidence_or_displaced_text_is_rejected(self):
        from PIL import Image
        image = Image.new("RGB", (200, 55))
        normal_box = [[15, 15], [315, 15], [315, 60], [15, 60]]
        for box, text, confidence in ((normal_box, "示例账号:其他", .99),
                                       (normal_box, "示例账号:店铺", .89),
                                       ([[60, 15], [360, 15], [360, 60], [60, 60]], "示例账号:店铺", .99)):
            engine = Mock(return_value=([(box, text, confidence)], None))
            with self.assertRaises(account_locator.AccountError):
                account_locator.choose_account_with_refinement(image, [row(30, confidence=.88)], "示例账号:店铺", engine)

    def test_high_confidence_original_candidate_does_not_refine(self):
        from PIL import Image
        engine = Mock()
        chosen, detail = account_locator.choose_account_with_refinement(Image.new("RGB", (200, 55)),
            [row(30)], "示例账号:店铺", engine)
        self.assertEqual(chosen[2], .96)
        self.assertIsNone(detail)
        engine.assert_not_called()


if __name__ == "__main__":
    unittest.main()
