"""The Pi must read a backup the Android app exported exactly as it reads the
recipes that went into it. The zip is produced by the Android unit tests
(PiCompatibilityExportTest), so this skips until they have run."""
import unittest

from tests import check_android_export

EXPORT_ZIP = check_android_export.ROOT / "apps" / "androidApp" / "build" / "compat" / "export.zip"


class TestAndroidExport(unittest.TestCase):
    @unittest.skipUnless(
        EXPORT_ZIP.exists(),
        "run the Android unit tests first to produce apps/androidApp/build/compat/export.zip",
    )
    def test_the_pi_parses_the_phone_backup_like_the_originals(self):
        self.assertEqual(check_android_export.main(str(EXPORT_ZIP)), 0)


if __name__ == "__main__":
    unittest.main()
