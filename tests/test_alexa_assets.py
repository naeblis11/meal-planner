import ast
import json
import re
import unittest
from pathlib import Path

import yaml

import grocery_categories
import meal_calendar
import voice

ALEXA_DIR = Path(__file__).parent.parent / "alexa"
CUSTOM_INTENTS = {"AddShoppingItemIntent", "AddPantryItemIntent", "AddMealIntent"}


def load_model() -> dict:
    return json.loads((ALEXA_DIR / "interaction-model.json").read_text(encoding="utf-8"))


def slot_type(model: dict, name: str) -> dict:
    return next(t for t in model["interactionModel"]["languageModel"]["types"] if t["name"] == name)


class TestInteractionModel(unittest.TestCase):
    def test_invocation_name_is_my_chef(self):
        self.assertEqual(load_model()["interactionModel"]["languageModel"]["invocationName"], "my chef")

    def test_declares_the_three_custom_intents_and_the_required_builtins(self):
        names = {i["name"] for i in load_model()["interactionModel"]["languageModel"]["intents"]}
        self.assertTrue(CUSTOM_INTENTS <= names, names)
        for builtin in ("AMAZON.HelpIntent", "AMAZON.CancelIntent", "AMAZON.StopIntent",
                        "AMAZON.FallbackIntent", "AMAZON.NavigateHomeIntent"):
            self.assertIn(builtin, names)

    def test_aisle_type_canonical_values_are_the_apps_aisles_plus_skip(self):
        # The Alexa values are spoken-safe ("and" instead of "&"), so
        # compare through the same folding.
        values = [v["name"]["value"] for v in slot_type(load_model(), "GroceryAisle")["values"]]
        expected = [v.replace(" & ", " and ") for v in grocery_categories.AISLE_ORDER] + ["skip"]
        self.assertEqual(values, expected)

    def test_meal_type_values_are_the_calendar_slots(self):
        values = [v["name"]["value"] for v in slot_type(load_model(), "MealSlot")["values"]]
        self.assertEqual(values, list(meal_calendar.SLOTS))

    def test_quantity_and_aisle_are_elicited_and_dialog_is_delegated(self):
        model = load_model()
        dialog = model["interactionModel"]["dialog"]
        self.assertEqual(dialog["delegationStrategy"], "ALWAYS")
        shopping = next(i for i in dialog["intents"] if i["name"] == "AddShoppingItemIntent")
        elicited = {s["name"] for s in shopping["slots"] if s["elicitationRequired"]}
        self.assertEqual(elicited, {"item", "quantity", "aisle"})

    def test_every_elicitation_prompt_id_exists(self):
        model = load_model()
        prompt_ids = {p["id"] for p in model["interactionModel"]["prompts"]}
        for intent in model["interactionModel"]["dialog"]["intents"]:
            for slot in intent["slots"]:
                for prompt_id in slot.get("prompts", {}).values():
                    self.assertIn(prompt_id, prompt_ids)

    def test_every_utterance_slot_is_declared_on_its_intent(self):
        for intent in load_model()["interactionModel"]["languageModel"]["intents"]:
            declared = {s["name"] for s in intent.get("slots", [])}
            for sample in intent.get("samples", []):
                for used in re.findall(r"\{(\w+)\}", sample):
                    self.assertIn(used, declared, f"{intent['name']}: {sample}")

    def test_no_synonym_repeats_its_own_value(self):
        # The console accepts these, but they are dead weight in the model.
        for slot_type_ in load_model()["interactionModel"]["languageModel"]["types"]:
            for value in slot_type_["values"]:
                canonical = value["name"]["value"].lower()
                for synonym in value["name"].get("synonyms", []):
                    self.assertNotEqual(synonym.lower(), canonical, f"{slot_type_['name']}: {canonical}")


class TestHostedSkillCode(unittest.TestCase):
    def test_lambda_function_compiles(self):
        # The forwarder is pasted into the Alexa-hosted skill's Code tab
        # by hand; make sure what ships at least parses.
        source = (ALEXA_DIR / "lambda_function.py").read_text(encoding="utf-8")
        ast.parse(source, filename="lambda_function.py")

    def test_config_template_compiles_and_has_both_settings(self):
        source = (ALEXA_DIR / "config.py").read_text(encoding="utf-8")
        names = {
            node.targets[0].id
            for node in ast.parse(source, filename="config.py").body
            if isinstance(node, ast.Assign)
        }
        self.assertEqual(names, {"BASE_URL", "LONG_LIVED_ACCESS_TOKEN"})


class _SecretLoader(yaml.SafeLoader):
    """Home Assistant's `!secret name` tag, read back as the string 'secret:name'."""


_SecretLoader.add_constructor("!secret", lambda loader, node: f"secret:{node.value}")


def load_ha_yaml() -> dict:
    return yaml.load((ALEXA_DIR / "home-assistant.yaml").read_text(encoding="utf-8"), Loader=_SecretLoader)


INTENT_COMMANDS = {
    "AddShoppingItemIntent": ("chef_shopping_add", "/api/voice/shopping-list"),
    "AddPantryItemIntent": ("chef_pantry_add", "/api/voice/pantry"),
    "AddMealIntent": ("chef_meal_add", "/api/voice/meal"),
}


class TestHomeAssistantYaml(unittest.TestCase):
    def test_the_alexa_key_is_present_and_intents_are_handled_by_intent_script(self):
        config = load_ha_yaml()
        # `alexa:` only enables the /api/alexa endpoint; the actual
        # intent handlers are dispatched to top-level intent_script:.
        self.assertIn("alexa", config)
        handled = set(config["intent_script"])
        model_intents = {i["name"] for i in load_model()["interactionModel"]["languageModel"]["intents"]}
        self.assertTrue(model_intents <= handled, model_intents - handled)

    def test_custom_intents_call_a_rest_command_for_the_right_endpoint(self):
        config = load_ha_yaml()
        for intent_name, (command, path) in INTENT_COMMANDS.items():
            first_action = config["intent_script"][intent_name]["action"][0]
            self.assertEqual(first_action["action"], f"rest_command.{command}")
            self.assertEqual(first_action["response_variable"], "http")
            self.assertTrue(first_action["continue_on_error"])
            rest = config["rest_command"][command]
            self.assertTrue(rest["url"].endswith(path), rest["url"])
            self.assertEqual(rest["method"], "POST")
            self.assertEqual(rest["headers"]["Authorization"], "secret:meal_planner_auth")
            self.assertEqual(rest["content_type"], "application/json")
            self.assertEqual(rest["timeout"], 5)

    def test_speech_reads_the_action_response_with_the_unreachable_fallback(self):
        config = load_ha_yaml()
        for intent_name in CUSTOM_INTENTS:
            intent = config["intent_script"][intent_name]
            self.assertEqual(intent["speech"]["text"], "{{ action_response.speech }}")
            stop = intent["action"][-1]
            self.assertEqual(stop["response_variable"], "result")
            fallback = intent["action"][-2]["variables"]["result"]["speech"]
            self.assertIn(voice.SPEECH_UNREACHABLE, fallback)

    def test_intent_model_slots_match_data_keys_and_payload_keys(self):
        model = load_model()
        config = load_ha_yaml()
        model_slots_by_intent = {
            i["name"]: {s["name"] for s in i.get("slots", [])}
            for i in model["interactionModel"]["languageModel"]["intents"]
        }
        for intent_name, (command, _path) in INTENT_COMMANDS.items():
            model_slots = model_slots_by_intent[intent_name]
            data_keys = set(config["intent_script"][intent_name]["action"][0]["data"])
            payload = config["rest_command"][command]["payload"]
            payload_keys = set(re.findall(r'"(\w+)":', payload))
            self.assertEqual(model_slots, data_keys, intent_name)
            self.assertEqual(model_slots, payload_keys, intent_name)


if __name__ == "__main__":
    unittest.main()
