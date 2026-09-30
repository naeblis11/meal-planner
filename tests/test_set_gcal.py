"""The setup script's handling of the key-file path.

Small, but this is the first thing anyone types and it is typed over SSH,
where "~/key.json" and a bare filename are both natural and both used to
fail in ways that looked like the file was missing.
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import set_gcal


class ResolveKeyInputTests(unittest.TestCase):
    def test_expands_a_home_directory_shortcut(self):
        resolved = set_gcal.resolve_key_input("~/key.json", "")
        self.assertNotIn("~", str(resolved))
        self.assertEqual(resolved, Path.home() / "key.json")

    def test_keeps_an_absolute_path_as_given(self):
        self.assertEqual(set_gcal.resolve_key_input("/home/pi/key.json", ""),
                         Path("/home/pi/key.json"))

    def test_falls_back_to_the_saved_setting_when_nothing_is_typed(self):
        self.assertEqual(set_gcal.resolve_key_input("", "/home/pi/saved.json"),
                         Path("/home/pi/saved.json"))

    def test_what_is_typed_wins_over_the_saved_setting(self):
        self.assertEqual(set_gcal.resolve_key_input("/new.json", "/old.json"),
                         Path("/new.json"))

    def test_nothing_at_all_is_not_a_path(self):
        self.assertIsNone(set_gcal.resolve_key_input("", ""))


if __name__ == "__main__":
    unittest.main()
