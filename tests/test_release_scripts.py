"""The owner's release scripts (plan 8, tools/release-key.ps1 and tools/release.ps1).

Nothing here runs them, or any part of them: they make the release key and publish to GitHub. These tests only parse
them with Windows PowerShell's parser and read their text for the rules they must keep.
"""
import re
import shutil
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = [ROOT / "tools" / "release-key.ps1", ROOT / "tools" / "release.ps1"]
SECRETS = ("keystore.properties", "google-client.properties", "google-client.json")


def text(path):
    return path.read_bytes().decode("ascii")


def code(path):
    """The script without its comment-based help block or # comments."""
    lines = re.sub(r"(?s)<#.*?#>", "", text(path)).splitlines()
    return [l for l in lines if not l.lstrip().startswith("#")]


def outside_functions(path):
    """code(path) without the bodies of its functions (a function ends at the first line that is just '}')."""
    lines, inside = [], False
    for line in code(path):
        if line.startswith("function "):
            inside = True
        elif inside and line.rstrip() == "}":
            inside = False
        elif not inside:
            lines.append(line)
    return lines


class ReleaseScriptText(unittest.TestCase):
    def test_ascii_only(self):
        # Windows PowerShell 5.1 reads a BOM-less script in the ANSI code page.
        for path in SCRIPTS:
            with self.subTest(path.name):
                data = path.read_bytes()
                bad = [i for i, b in enumerate(data) if b > 0x7E or (b < 0x20 and b not in (0x09, 0x0A, 0x0D))]
                self.assertEqual(bad, [])

    def test_secret_files_are_only_ever_tested_for(self):
        # keystore.properties and google-client.properties are checked to exist (Test-Path) and never read.
        for path in SCRIPTS:
            for line in code(path):
                for name in SECRETS:
                    if name in line:
                        with self.subTest(path.name, line=line):
                            self.assertRegex(line, r"Test-Path '[^']*" + re.escape(name) + "'|throw '")
                            self.assertNotRegex(line, r"Get-Content|ReadAll|\[IO\.File\]|cat |type ")

    def test_every_program_runs_through_the_continue_helpers(self):
        # P8-PF1: no bare & call to a program outside Invoke-Native / Get-NativeLines.
        for path in SCRIPTS:
            calls = [l for l in code(path) if re.search(r"(?:^|[\s{(=;])&\s", l)]
            with self.subTest(path.name):
                self.assertTrue(calls)
                self.assertTrue(all("& $Exe @Arguments" in l for l in calls), calls)

    def test_release_checks_file_names_with_the_apps_own_rule(self):
        # P8-F1: release.ps1's file-name check is ReleaseManifest.FILE_PATTERN, character for character, so a release
        # it lets through is one the apps accept.
        manifest = text(ROOT / "apps" / "shared" / "core" / "src" / "commonMain" / "kotlin" / "com" / "naeblis11"
                        / "mealplanner" / "update" / "ReleaseManifest.kt")
        kotlin = re.findall(r'const val FILE_PATTERN = "([^"]*)"', manifest)
        script = text(ROOT / "tools" / "release.ps1")
        ps = re.findall(r"^\$filePattern = '([^']*)'$", script, re.M)
        self.assertEqual(len(kotlin), 1)
        self.assertEqual(len(ps), 1)
        self.assertEqual(ps[0], kotlin[0])
        # Both staged names are checked against it, whole, with their own extension.
        for entry, ext in (("desktop", "msi"), ("android", "apk")):
            with self.subTest(ext):
                self.assertIn(
                    f"$list.{entry}.file -cnotmatch \"^$filePattern`$\" -or -not $list.{entry}.file.EndsWith('.{ext}')",
                    script,
                )
        self.assertNotIn("[A-Za-z0-9._-]*", script)

    def test_release_rebuilds_and_rejects_and_asks(self):
        script = text(ROOT / "tools" / "release.ps1")
        self.assertIn("':desktopApp:packageMsi', '--rerun'", script)
        self.assertIn(".msi.rejected", script)
        self.assertIn("if ((Test-Path $rejected) -and (Get-Item $rejected).LastWriteTime -ge $buildStart) {", script)
        self.assertIn("Test-Path 'apps\\google-client.properties'", script)
        self.assertIn("Test-Path 'apps\\keystore.properties'", script)
        ask = script.index("Read-Host \"Publish to $($repo)? (y/N)\"")
        self.assertLess(ask, script.index("'release', 'create'"))
        self.assertIn("$answer -ne 'y' -and $answer -ne 'Y'", script)
        self.assertNotIn("git push", script)
        # Where the tag lands is shown, read-only, before the question; the notes name the private commit.
        landing = script.index('Write-Host "  Tag lands on public master $publicMaster"')
        self.assertLess(script.index("@('api', \"repos/$repo/commits/master\", '--jq', '.sha')"), landing)
        self.assertLess(landing, ask)
        self.assertIn("Built from ' + $commit", script)

    def test_no_program_is_called_outside_the_helpers(self):
        # A line that starts with the program is a call; '$tool = ...' only names it.
        bare = re.compile(r"\s*(git |gh |\.\\apps\\gradlew\.bat|keytool|\$keytool\b(?!\s*=)|\$tool\b(?!\s*=))")
        for path in SCRIPTS:
            with self.subTest(path.name):
                self.assertEqual([l for l in outside_functions(path) if bare.match(l)], [])

    def test_both_scripts_use_the_same_jdk_and_branch_check(self):
        for path in SCRIPTS:
            with self.subTest(path.name):
                script = text(path)
                self.assertIn(r"$jbr = 'C:\Program Files\Android\Android Studio\jbr'", script)
                self.assertNotIn("$jbr = $env:JAVA_HOME", script)
                self.assertIn("@('rev-parse', '--abbrev-ref', 'HEAD')", script)
                self.assertRegex(script, r"if \(\$branch -ne 'master'\) \{ throw")
                self.assertIn("@('status', '--porcelain')", script)
                self.assertIn("if ($out.Count -gt 0) { throw 'There are uncommitted changes.", script)

    def test_release_key_refuses_a_second_key(self):
        script = text(ROOT / "tools" / "release-key.ps1")
        self.assertIn("if (Test-Path $store) { throw", script)
        self.assertNotIn("--replace", script)
        self.assertNotIn("-storepass", script)
        self.assertNotIn("-keypass", script)


@unittest.skipUnless(sys.platform == "win32" and shutil.which("powershell"), "needs Windows PowerShell")
class ReleaseScriptsParse(unittest.TestCase):
    def test_no_syntax_errors(self):
        for path in SCRIPTS:
            with self.subTest(path.name):
                cmd = ("$e=$null; [System.Management.Automation.Language.Parser]::ParseFile("
                       f"'{path}', [ref]$null, [ref]$e) | Out-Null; "
                       "if ($e.Count) { $e | ForEach-Object { $_.Message }; exit 1 }")
                result = subprocess.run(["powershell", "-NoProfile", "-Command", cmd], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
