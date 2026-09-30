"""Tests for wizard_io Console helper."""
import unittest
import wizard_io


class ScriptedConsole(wizard_io.Console):
    """Answers prompts from a list, in order; records everything shown."""
    def __init__(self, answers):
        self.answers = list(answers)
        self.shown = []
        super().__init__(input_fn=self._next, secret_fn=self._next, output_fn=self._show)
    def _next(self, prompt=""):
        self.shown.append(prompt)
        if not self.answers:
            raise AssertionError(f"wizard asked more than scripted: {prompt!r}")
        answer = self.answers.pop(0)
        if answer is KeyboardInterrupt:
            raise KeyboardInterrupt
        return answer
    def _show(self, *parts, **_):
        self.shown.append(" ".join(str(p) for p in parts))
    @property
    def text(self):
        return "\n".join(self.shown)


class ConsoleTests(unittest.TestCase):
    def test_ask_returns_default_on_blank(self):
        self.assertEqual(ScriptedConsole([""]).ask("Name", "meal-planner"), "meal-planner")
    def test_ask_strips(self):
        self.assertEqual(ScriptedConsole(["  x "]).ask("Name"), "x")
    def test_yes_no_default_and_reask(self):
        con = ScriptedConsole(["maybe", "n"])
        self.assertFalse(con.yes_no("Turn on?", True))
        self.assertIn("Please answer y or n.", con.text)
        self.assertTrue(ScriptedConsole([""]).yes_no("Turn on?", True))
    def test_choose(self):
        con = ScriptedConsole(["3", "2"])
        self.assertEqual(con.choose("Where?", ["a", "b"]), 2)
        self.assertIn("  1) a", con.text)
    def test_choose_default(self):
        self.assertEqual(ScriptedConsole([""]).choose("Where?", ["a", "b"], default=2), 2)
    def test_ask_whitespace_only_returns_default(self):
        self.assertEqual(ScriptedConsole(["  "]).ask("Name", "d"), "d")
    def test_ask_secret_keeps_answer_exactly(self):
        self.assertEqual(ScriptedConsole([" pw "]).ask_secret("P: "), " pw ")


if __name__ == "__main__":
    unittest.main()
