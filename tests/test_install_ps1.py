import shutil
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


@unittest.skipUnless(sys.platform == "win32" and shutil.which("powershell"), "needs Windows PowerShell")
class InstallScriptParses(unittest.TestCase):
    def test_no_syntax_errors(self):
        cmd = ("$e=$null; [System.Management.Automation.Language.Parser]::ParseFile("
               f"'{ROOT / 'install.ps1'}', [ref]$null, [ref]$e) | Out-Null; "
               "if ($e.Count) { $e | ForEach-Object { $_.Message }; exit 1 }")
        result = subprocess.run(["powershell", "-NoProfile", "-Command", cmd],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


@unittest.skipUnless(sys.platform == "win32" and shutil.which("powershell"), "needs Windows PowerShell")
class TestPythonProbe(unittest.TestCase):
    """Test-Python must survive a native command that writes to stderr under
    $ErrorActionPreference = "Stop" (the Microsoft Store python alias does)."""

    def run_probe(self, candidate_ps: str, path_dir: Path) -> str:
        script = (
            '$ErrorActionPreference = "Stop"\n'
            f"$src = Get-Content -Raw '{ROOT / 'install.ps1'}'\n"
            r"$m = [regex]::Match($src, '(?s)function Test-Python.*?\n}\r?\n')" "\n"
            "Invoke-Expression $m.Value\n"
            f"$env:PATH = '{path_dir};' + $env:PATH\n"
            f"Write-Output (Test-Python {candidate_ps})\n"
        )
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            ps1 = Path(tmp) / "probe.ps1"
            ps1.write_text(script, encoding="ascii")
            result = subprocess.run(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ps1)],
                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout.strip()

    def test_a_probe_that_writes_to_stderr_is_just_false(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "fakepy.cmd").write_text("@echo off\r\necho boom 1>&2\r\nexit /b 9\r\n")
            self.assertEqual(self.run_probe('@("fakepy")', Path(tmp)), "False")

    def test_winget_is_guarded(self):
        text = (ROOT / "install.ps1").read_text(encoding="ascii")
        self.assertIn("Get-Command winget", text)
        self.assertIn("python.org/downloads", text)

    def test_start_now_polls_healthz(self):
        text = (ROOT / "install.ps1").read_text(encoding="ascii")
        self.assertNotIn("Start-Sleep -Seconds 3", text)
        self.assertIn(r".venv\Scripts\python app.py", text)
