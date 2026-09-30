import json
import shutil
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

import wizard_pi
import wizard_steps as ws
from tests.test_wizard_io import ScriptedConsole


class PiName(unittest.TestCase):
    def test_default_and_validation(self):
        self.assertEqual(wizard_pi.ask_pi_name(ScriptedConsole([""])), "meal-planner")
        con = ScriptedConsole(["My Pi!", "Kitchen-Pi"])
        self.assertEqual(wizard_pi.ask_pi_name(con), "kitchen-pi")


class WriteCard(unittest.TestCase):
    def test_passes_a_hash_not_the_password(self):
        seen = {}
        def fake_run(argv):
            secrets = Path(argv[argv.index("-SecretsFile") + 1])
            seen["text"] = secrets.read_text()
            seen["argv"] = argv
            return mock.Mock(returncode=0)
        ctx = ws.Context(target=ws.TARGET_PI, tailscale_key="tskey-auth-x",
                         gcal_key_source=Path("C:/k.json"))
        wizard_pi.write_card({"MEAL_PLANNER_PASSWORD_HASH": "scrypt:abc"}, ctx, run=fake_run)
        self.assertIn("MEAL_PLANNER_PASSWORD_HASH=scrypt:abc", seen["text"])
        self.assertIn("-TailscaleAuthKey", seen["argv"])
        self.assertIn("-GcalKeyFile", seen["argv"])
        self.assertNotIn("-Password", seen["argv"])
        self.assertFalse(Path(seen["argv"][seen["argv"].index("-SecretsFile") + 1]).exists(),
                         "the temp secrets file must be deleted afterwards")

    def test_drive_is_passed(self):
        seen = {}
        def fake_run(argv):
            seen["argv"] = argv
            return mock.Mock(returncode=0)
        wizard_pi.write_card({}, ws.Context(target=ws.TARGET_PI), run=fake_run, drive="E:")
        self.assertEqual(seen["argv"][seen["argv"].index("-Drive") + 1], "E:")

    def test_failure_raises_and_cleans_up(self):
        seen = {}
        def fake_run(argv):
            seen["path"] = Path(argv[argv.index("-SecretsFile") + 1])
            return mock.Mock(returncode=1)
        with self.assertRaises(wizard_pi.CardError):
            wizard_pi.write_card({}, ws.Context(target=ws.TARGET_PI), run=fake_run)
        self.assertFalse(seen["path"].exists())


class WaitForApp(unittest.TestCase):
    def fake_clock(self):
        t = {"now": 0.0}
        return (lambda: t["now"]), (lambda s: t.__setitem__("now", t["now"] + s))

    def test_succeeds_once_healthz_answers(self):
        clock, sleep = self.fake_clock()
        answers = [urllib.error.URLError("no"), urllib.error.URLError("no"),
                   mock.MagicMock(read=lambda: json.dumps({"ok": True}).encode())]
        def opener(url, timeout):
            a = answers.pop(0)
            if isinstance(a, Exception): raise a
            return a
        self.assertTrue(wizard_pi.wait_for_app("http://p.local:5000", opener=opener,
                                               clock=clock, sleep=sleep))

    def test_times_out(self):
        clock, sleep = self.fake_clock()
        def opener(url, timeout): raise urllib.error.URLError("no")
        self.assertFalse(wizard_pi.wait_for_app("http://p.local:5000", timeout=60, opener=opener,
                                                clock=clock, sleep=sleep))

    def test_bad_body_is_not_up_yet(self):
        clock, sleep = self.fake_clock()
        bad = mock.MagicMock(read=lambda: b"<html>")
        self.assertFalse(wizard_pi.wait_for_app("http://p.local:5000", timeout=10,
                                                opener=lambda url, timeout: bad,
                                                clock=clock, sleep=sleep))


class SaveChecklist(unittest.TestCase):
    def test_writes_to_the_desktop_with_a_warning(self):
        tmp = Path(tempfile.mkdtemp()); self.addCleanup(shutil.rmtree, tmp)
        path = wizard_pi.save_checklist("steps", desktop=tmp)
        self.assertTrue(path.read_text().startswith("This file contains your Meal Planner API token"))


    def test_uses_the_real_desktop(self):
        tmp = Path(tempfile.mkdtemp()); self.addCleanup(shutil.rmtree, tmp)
        desk = tmp / "OneDrive" / "Desktop"; desk.mkdir(parents=True)
        with mock.patch.object(wizard_pi.paths, "desktop_dir", return_value=desk):
            path = wizard_pi.save_checklist("steps")
        self.assertEqual(path.parent, desk)

    def test_a_missing_desktop_is_not_invented(self):
        tmp = Path(tempfile.mkdtemp()); self.addCleanup(shutil.rmtree, tmp)
        home = tmp / "home"; home.mkdir()
        with mock.patch.object(wizard_pi.paths, "desktop_dir", return_value=home / "Desktop"), \
                mock.patch.object(wizard_pi.Path, "home", return_value=home):
            path = wizard_pi.save_checklist("steps")
        self.assertEqual(path.parent, home)
        self.assertFalse((home / "Desktop").exists())


class ImagerSteps(unittest.TestCase):
    def test_mentions_the_name_and_pauses(self):
        con = ScriptedConsole([""])
        wizard_pi.show_imager_steps(con, "kitchen-pi")
        self.assertIn("kitchen-pi", con.text)
        self.assertIn("Raspberry Pi OS Lite (64-bit)", con.text)
        self.assertEqual(con.answers, [])


class Run(unittest.TestCase):
    def test_end_to_end(self):
        ctx = ws.Context(target=ws.TARGET_PI)
        # name, imager pause, steps are patched, write yes, eject pause, (wait patched)
        con = ScriptedConsole(["", "", "", "", ""])
        with mock.patch.object(wizard_pi.wizard_steps, "STEPS", ()), \
             mock.patch.object(wizard_pi, "write_card") as wc, \
             mock.patch.object(wizard_pi, "wait_for_app", return_value=True), \
             mock.patch.object(wizard_pi, "save_checklist", return_value=Path("C:/x.txt")) as sc:
            self.assertEqual(wizard_pi.run(con, ctx), 0)
        wc.assert_called_once()
        sc.assert_called_once()
        self.assertIn("meal-planner.local:5000", con.text)
        self.assertIn("Saved a copy to", con.text)

    def test_a_failed_checklist_save_does_not_fail_the_run(self):
        ctx = ws.Context(target=ws.TARGET_PI)
        con = ScriptedConsole(["", "", "", "", ""])
        with mock.patch.object(wizard_pi.wizard_steps, "STEPS", ()), \
                mock.patch.object(wizard_pi, "write_card"), \
                mock.patch.object(wizard_pi, "wait_for_app", return_value=True), \
                mock.patch.object(wizard_pi, "save_checklist", side_effect=PermissionError("denied")):
            self.assertEqual(wizard_pi.run(con, ctx), 0)
        self.assertIn("Couldn't save a copy", con.text)
        self.assertNotIn("Saved a copy to", con.text)

    def test_declining_writes_nothing(self):
        con = ScriptedConsole(["", "", "n"])
        with mock.patch.object(wizard_pi.wizard_steps, "STEPS", ()), \
             mock.patch.object(wizard_pi, "write_card") as wc:
            self.assertEqual(wizard_pi.run(con, ws.Context(target=ws.TARGET_PI)), 1)
        wc.assert_not_called()
        self.assertIn("Nothing written.", con.text)
