import os
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import configure
import set_password
import wizard_steps as ws
from tests.test_ha_sync import FakeHA
from tests.test_wizard_io import ScriptedConsole


class ConfigureLocal(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        patch = mock.patch.dict(os.environ, {"MEAL_PLANNER_HOME": str(self.tmp / "cfg"),
                                             "MEAL_PLANNER_DATA_DIR": str(self.tmp / "data")})
        patch.start(); self.addCleanup(patch.stop)
        self.env = self.tmp / "cfg" / ".env"

    def run_wizard(self, answers, platform="win32", argv=()):
        con = ScriptedConsole(answers)
        ctx = lambda target: ws.Context(target=target, make_ha_link=lambda *a: FakeHA())
        code = configure.main(list(argv), console=con, platform=platform,
                              make_context=ctx, run_command=mock.Mock())
        return code, con

    def test_everything_off(self):
        # target, pw, pw, access, remote, ha, alexa, gcal, save
        code, con = self.run_wizard(["1", "pw", "pw", "", "n", "n", "n", "n", ""])
        self.assertEqual(code, 0)
        values = set_password.read_env(self.env)
        self.assertIn("MEAL_PLANNER_PASSWORD_HASH", values)
        self.assertNotIn("MEAL_PLANNER_HOST", values)

    def test_declining_the_summary_writes_nothing(self):
        code, _ = self.run_wizard(["1", "pw", "pw", "", "n", "n", "n", "n", "n"])
        self.assertEqual(code, 1)
        self.assertFalse(self.env.exists())

    def test_ctrl_c_writes_nothing(self):
        code, con = self.run_wizard(["1", "pw", KeyboardInterrupt])
        self.assertEqual(code, 1)
        self.assertFalse(self.env.exists())
        self.assertIn("Nothing was saved", con.text)

    def test_rerun_defaults_reflect_current_state(self):
        self.run_wizard(["1", "pw", "pw", "2", "y", "n", "n", "n", ""])
        code, _ = self.run_wizard(["1", "", "", "", "", "", "", ""])  # keep everything
        values = set_password.read_env(self.env)
        self.assertEqual(values["MEAL_PLANNER_HOST"], "0.0.0.0")
        self.assertEqual(values["MEAL_PLANNER_BEHIND_PROXY"], "1")

    def test_report_file_names_the_target(self):
        report = self.tmp / "target.txt"
        self.run_wizard(["1", "pw", "pw", "", "n", "n", "n", "n", ""], argv=["--report", str(report)])
        self.assertEqual(report.read_text().strip(), "windows")

    def test_linux_skips_the_target_question(self):
        code, con = self.run_wizard(["pw", "pw", "", "n", "n", "n", "n", ""], platform="linux",
                                    argv=["--no-restart"])
        self.assertEqual(code, 0)
        self.assertNotIn("Where will the app run?", con.text)

    def test_summary_lines(self):
        ctx = ws.Context(target=ws.TARGET_WINDOWS)
        ctx.features.update(remote=True, ha=False)
        lines = configure.summarize({"MEAL_PLANNER_PASSWORD_HASH": "x",
                                     "MEAL_PLANNER_HOST": "0.0.0.0"}, ctx)
        self.assertIn("Password: set", lines)
        self.assertIn("Reachable from: your home network", lines)
        self.assertIn("Remote access (Tailscale): on", lines)
        self.assertIn("Home Assistant shopping list: off", lines)
        self.assertIn("Alexa voice skill: off", lines)
        self.assertIn("Google Calendar: off", lines)
        self.assertTrue(lines[-1].startswith("Settings file: "))

    def test_linux_runs_tailscale_and_restart_through_run_command(self):
        con = ScriptedConsole(["pw", "pw", "", "y", "KEY", "n", "n", "n", ""])
        run = mock.Mock()
        ctx = lambda target: ws.Context(target=target, make_ha_link=lambda *a: FakeHA())
        unit = self.tmp / "meal-planner.service"
        unit.write_text("")
        with mock.patch.object(configure, "SERVICE_UNIT", unit):
            code = configure.main([], console=con, platform="linux", make_context=ctx, run_command=run)
        self.assertEqual(code, 0)
        cmds = [c.args[0] for c in run.call_args_list]
        self.assertEqual(cmds[0][:2], ["sudo", "bash"])
        self.assertTrue(cmds[0][2].endswith("setup-tailscale.sh"))
        self.assertEqual(cmds[0][3], "KEY")
        self.assertEqual(cmds[1], ["sudo", "systemctl", "restart", "meal-planner"])


if __name__ == "__main__":
    unittest.main()
