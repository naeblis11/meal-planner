import io
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

import paths
import set_api_token
from set_password import read_env


class TestSetApiToken(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp_dir.name) / "home"
        self.env_patch = mock.patch.dict(os.environ, {paths.HOME_ENV: str(self.home)})
        self.env_patch.start()

    def tearDown(self):
        self.env_patch.stop()
        self.tmp_dir.cleanup()

    def run_main(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = set_api_token.main()
        return code, out.getvalue()

    def test_creates_the_env_with_a_64_hex_token_and_prints_it(self):
        code, printed = self.run_main()
        self.assertEqual(code, 0)
        values = read_env(paths.env_path())
        token = values[set_api_token.API_TOKEN_ENV]
        self.assertRegex(token, r"^[0-9a-f]{64}$")
        self.assertIn(token, printed)
        self.assertIn("Bearer", printed)

    def test_preserves_existing_keys_and_rotates_the_token(self):
        paths.env_path().parent.mkdir(parents=True)
        paths.env_path().write_text("MEAL_PLANNER_SECRET_KEY=abc\nMEAL_PLANNER_API_TOKEN=old\n", encoding="utf-8")
        self.run_main()
        values = read_env(paths.env_path())
        self.assertEqual(values["MEAL_PLANNER_SECRET_KEY"], "abc")
        self.assertNotEqual(values["MEAL_PLANNER_API_TOKEN"], "old")


if __name__ == "__main__":
    unittest.main()
