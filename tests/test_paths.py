import os
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import paths


class TestConfigDir(unittest.TestCase):
    def test_home_override_wins_everywhere(self):
        with mock.patch.dict(os.environ, {paths.HOME_ENV: r"D:\portable\planner"}):
            self.assertEqual(paths.config_dir(), Path(r"D:\portable\planner"))
            self.assertEqual(paths.env_path(), Path(r"D:\portable\planner") / ".env")

    def test_windows_uses_local_appdata(self):
        env = {"LOCALAPPDATA": r"C:\Users\someone\AppData\Local"}
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(paths.sys, "platform", "win32"):
            self.assertEqual(
                paths.config_dir(), Path(r"C:\Users\someone\AppData\Local") / "Meal Planner"
            )

    def test_windows_falls_back_to_home_when_localappdata_missing(self):
        with mock.patch.dict(os.environ, {}, clear=True), \
                mock.patch.object(paths.sys, "platform", "win32"), \
                mock.patch.object(paths.Path, "home", return_value=Path(r"C:\Users\someone")):
            self.assertEqual(
                paths.config_dir(), Path(r"C:\Users\someone") / "AppData" / "Local" / "Meal Planner"
            )

    def test_non_windows_uses_xdg_config_home(self):
        with mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": "/tmp/xdg"}, clear=True), \
                mock.patch.object(paths.sys, "platform", "linux"):
            self.assertEqual(paths.config_dir(), Path("/tmp/xdg") / "meal-planner")

    def test_linux_data_dir_is_a_visible_folder_in_home(self):
        with mock.patch.dict(os.environ, {"XDG_DATA_HOME": "/home/pi/.local/share"}, clear=True), \
                mock.patch.object(paths.sys, "platform", "linux"), \
                mock.patch.object(paths.Path, "home", return_value=Path("/home/pi")):
            self.assertEqual(paths.data_dir(), Path("/home/pi/meal-planner"))
            self.assertEqual(paths.recipes_dir(), Path("/home/pi/meal-planner/recipes"))
            self.assertEqual(paths.config_dir(), Path("/home/pi/.config/meal-planner"))

    def test_data_dir_override_wins_on_linux(self):
        with mock.patch.dict(os.environ, {paths.DATA_DIR_ENV: "/srv/meals"}, clear=True), \
                mock.patch.object(paths.sys, "platform", "linux"):
            self.assertEqual(paths.db_path(), Path("/srv/meals/mealplanner.db"))

    def test_nothing_in_config_dir_points_at_the_project(self):
        self.assertNotIn("Meal Planning", str(paths.config_dir()))
        self.assertNotIn("OneDrive", str(paths.config_dir()))


class TestLegacyFolders(unittest.TestCase):
    """Installs made before the rename keep their folders; nothing is moved."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def _linux(self):
        return mock.patch.object(paths.sys, "platform", "linux"),             mock.patch.object(paths.Path, "home", return_value=self.tmp)

    def test_new_install_uses_new_names(self):
        p, h = self._linux()
        with mock.patch.dict(os.environ, {}, clear=True), p, h:
            self.assertEqual(paths.config_dir(), self.tmp / ".config" / "meal-planner")
            self.assertEqual(paths.data_dir(), self.tmp / "meal-planner")

    def test_legacy_folders_are_used_when_only_they_exist(self):
        (self.tmp / ".config" / "mac-meal-planner").mkdir(parents=True)
        (self.tmp / "mac-meal-planner").mkdir()
        p, h = self._linux()
        with mock.patch.dict(os.environ, {}, clear=True), p, h:
            self.assertEqual(paths.config_dir(), self.tmp / ".config" / "mac-meal-planner")
            self.assertEqual(paths.data_dir(), self.tmp / "mac-meal-planner")

    def test_new_folders_win_when_both_exist(self):
        for d in (".config/mac-meal-planner", ".config/meal-planner", "mac-meal-planner", "meal-planner"):
            (self.tmp / d).mkdir(parents=True)
        p, h = self._linux()
        with mock.patch.dict(os.environ, {}, clear=True), p, h:
            self.assertEqual(paths.config_dir(), self.tmp / ".config" / "meal-planner")
            self.assertEqual(paths.data_dir(), self.tmp / "meal-planner")

    def test_windows_legacy_config_folder(self):
        local = self.tmp / "Local"
        (local / "MAC Meal Planner").mkdir(parents=True)
        with mock.patch.dict(os.environ, {"LOCALAPPDATA": str(local)}, clear=True),                 mock.patch.object(paths.sys, "platform", "win32"):
            self.assertEqual(paths.config_dir(), local / "MAC Meal Planner")

    def test_windows_legacy_data_folder(self):
        docs = self.tmp / "Documents"
        (docs / "MAC Meal Planner").mkdir(parents=True)
        with mock.patch.dict(os.environ, {}, clear=True),                 mock.patch.object(paths.sys, "platform", "win32"),                 mock.patch.object(paths, "_windows_documents_dir", return_value=docs):
            self.assertEqual(paths.data_dir(), docs / "MAC Meal Planner")

    def test_override_beats_legacy(self):
        (self.tmp / "mac-meal-planner").mkdir()
        p, h = self._linux()
        with mock.patch.dict(os.environ, {paths.DATA_DIR_ENV: str(self.tmp / "x")}, clear=True), p, h:
            self.assertEqual(paths.data_dir(), self.tmp / "x")


    def test_desktop_dir_uses_the_known_folder_on_windows(self):
        real = self.tmp / "OneDrive" / "Desktop"
        with mock.patch.object(paths.sys, "platform", "win32"), \
                mock.patch.object(paths, "_windows_known_folder", return_value=real) as kf:
            self.assertEqual(paths.desktop_dir(), real)
        self.assertIs(kf.call_args[0][0], paths._FOLDERID_DESKTOP)

    def test_desktop_dir_falls_back_to_home_desktop(self):
        with mock.patch.object(paths.sys, "platform", "win32"), \
                mock.patch.object(paths, "_windows_known_folder", return_value=None), \
                mock.patch.object(paths.Path, "home", return_value=self.tmp):
            self.assertEqual(paths.desktop_dir(), self.tmp / "Desktop")
        with mock.patch.object(paths.sys, "platform", "linux"), \
                mock.patch.object(paths.Path, "home", return_value=self.tmp):
            self.assertEqual(paths.desktop_dir(), self.tmp / "Desktop")

    def test_documents_dir_falls_back_when_the_api_says_no(self):
        with mock.patch.object(paths, "_windows_known_folder", return_value=None), \
                mock.patch.object(paths.Path, "home", return_value=self.tmp):
            self.assertEqual(paths._windows_documents_dir(), self.tmp / "Documents")


if __name__ == "__main__":
    unittest.main()
