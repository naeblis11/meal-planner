"""The committed parity fixtures must be exactly what the Python modules
answer today. The Android app's domain tests read the same files, so this is
what keeps the two implementations in step: change shared logic here, run
`python -m tests.parity_support`, and the Kotlin tests fail until Android
matches."""
import json
import unittest

from tests import parity_support


class TestParityFixtures(unittest.TestCase):
    def test_committed_fixtures_match_the_python_modules(self):
        for name, content in parity_support.build_fixtures().items():
            with self.subTest(fixture=name):
                path = parity_support.FIXTURE_DIR / name
                self.assertTrue(path.exists(),
                                f"{path} is missing: run python -m tests.parity_support")
                self.assertEqual(
                    json.loads(path.read_text(encoding="utf-8")),
                    json.loads(parity_support.render(content)),
                    f"{name} is stale: run python -m tests.parity_support, commit it, "
                    "and make the Android domain tests pass again",
                )

    def test_every_fixture_has_cases(self):
        for name, content in parity_support.build_fixtures().items():
            with self.subTest(fixture=name):
                self.assertTrue(content)
