import unittest

import set_api_token
import wizard_next as wn


class Render(unittest.TestCase):
    def test_ha_yaml_points_at_the_app(self):
        text = wn.render_ha_yaml("http://kitchen.local:5000")
        self.assertIn("http://kitchen.local:5000/api/ha/shopping-list/sync", text)
        self.assertNotIn("meal-planner.local", text)

    def test_alexa_yaml_points_at_the_app(self):
        text = wn.render_alexa_yaml("http://kitchen.local:5000")
        self.assertEqual(text.count("http://kitchen.local:5000/api/voice/"), 3)


class Checklist(unittest.TestCase):
    def test_only_features_that_are_on(self):
        text = wn.checklist({}, {"ha": False, "alexa": False, "gcal": False, "remote": False},
                            "http://127.0.0.1:5000")
        self.assertIn("http://127.0.0.1:5000", text)
        self.assertNotIn("secrets.yaml", text)
        self.assertNotIn("Tailscale", text)

    def test_ha_includes_the_token_and_yaml(self):
        values = {set_api_token.API_TOKEN_ENV: "abc123"}
        text = wn.checklist(values, {"ha": True}, "http://meal-planner.local:5000", pi_name="meal-planner")
        self.assertIn('meal_planner_auth: "Bearer abc123"', text)
        self.assertIn("rest_command:", text)
        self.assertIn(".venv/bin/python configure.py", text)

    def test_secrets_line_printed_once_with_ha_and_alexa(self):
        values = {set_api_token.API_TOKEN_ENV: "abc123"}
        text = wn.checklist(values, {"ha": True, "alexa": True}, "http://x.local:5000")
        self.assertEqual(text.count('meal_planner_auth: "Bearer abc123"'), 1)
        self.assertIn("http://x.local:5000/api/voice/", text)

    def test_alexa_alone_prints_secrets_line(self):
        values = {set_api_token.API_TOKEN_ENV: "abc123"}
        text = wn.checklist(values, {"alexa": True}, "http://x.local:5000")
        self.assertIn('meal_planner_auth: "Bearer abc123"', text)

    def test_remote_and_gcal(self):
        text = wn.checklist({}, {"remote": True, "gcal": True}, "http://x:5000", pi_name="kitchen")
        self.assertIn("https://kitchen.<your-tailnet>.ts.net", text)
        self.assertIn("Send this week to Google Calendar", text)

    def test_remote_pc_name_fallback(self):
        text = wn.checklist({}, {"remote": True}, "http://x:5000")
        self.assertIn("https://this-pc-name.<your-tailnet>.ts.net", text)

    def test_pi_without_tailscale_key_hint(self):
        text = wn.checklist({}, {"remote": True}, "http://x:5000", pi_name="kitchen",
                            tailscale_key_given=False)
        self.assertIn("ssh <user>@kitchen.local", text)
        self.assertIn("curl -fsSL https://tailscale.com/install.sh | sh", text)
        self.assertIn("sudo tailscale up --ssh", text)
        self.assertIn("sudo ~/Meal_Planner/pi/setup-tailscale.sh", text)

    def test_windows_home_network_mentions_the_firewall_prompt(self):
        text = wn.checklist({"MEAL_PLANNER_HOST": "0.0.0.0"}, {}, "http://x:5000")
        self.assertIn("Windows Firewall", text)
        self.assertIn("Private networks", text)

    def test_firewall_line_only_when_listening_on_the_network_from_windows(self):
        self.assertNotIn("Firewall", wn.checklist({}, {}, "http://x:5000"))
        self.assertNotIn("Firewall", wn.checklist({"MEAL_PLANNER_HOST": "0.0.0.0"}, {},
                                                  "http://x:5000", pi_name="kitchen"))

    def test_steps_are_numbered(self):
        text = wn.checklist({}, {"gcal": True}, "http://x:5000")
        self.assertIn("1. Open http://x:5000", text)
        self.assertIn("2. ", text)


if __name__ == "__main__":
    unittest.main()
