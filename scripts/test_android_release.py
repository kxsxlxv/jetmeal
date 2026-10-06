"""Test versioning across reruns, out-of-order runs and restored workflow counters."""
import unittest
from android_release import allocate, BASE_CODE, validate_badging


class VersionTests(unittest.TestCase):
    def test_first_run_exceeds_existing_app(self):
        self.assertEqual(BASE_CODE + 101, allocate(1, 1, []))

    def test_rerun_has_new_code(self):
        first = allocate(37, 1, [])
        self.assertGreater(allocate(37, 2, [f"v1.1.{first}"]), first)

    def test_out_of_order_and_reset_never_regress(self):
        previous = allocate(99, 1, [])
        for run in [98, 1, 100]:
            new = allocate(run, 1, [f"refs/tags/v1.1.{previous}"])
            self.assertGreater(new, previous)
            previous = new

    def test_only_matching_tags_count(self):
        self.assertEqual(BASE_CODE + 101, allocate(1, 1, ["v0.0.99999999", "other", "v1.1.bad"]))

    def test_limits_fail_closed(self):
        for run, attempt, tags in [(0, 1, []), (1, 100, []), (21_000_000, 1, []),
                                    (1, 1, ["v1.1.2100000000"])]:
            with self.assertRaises(ValueError):
                allocate(run, attempt, tags)

    def test_actual_apk_package_and_version_must_match(self):
        valid = "package: name='com.kxsxlxv.jetmeal' versionCode='1000101' versionName='1.1.1000101'"
        validate_badging(valid, 1000101, "1.1.1000101")
        for text in [valid.replace("jetmeal", "other"), valid.replace("1000101", "1000100"), ""]:
            with self.assertRaises(AssertionError):
                validate_badging(text, 1000101, "1.1.1000101")


if __name__ == "__main__":
    unittest.main()
