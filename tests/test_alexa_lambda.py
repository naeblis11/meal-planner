"""The Alexa-hosted forwarder in alexa/lambda_function.py, exercised
without a network: `handle()` takes an injected `post`."""
import http.client
import importlib.util
import unittest
import urllib.error
from pathlib import Path

ALEXA_DIR = Path(__file__).parent.parent / "alexa"


def load_lambda():
    # alexa/ is not a package (the file is pasted into the Alexa console
    # by hand), so load it by path. alexa/ is not on sys.path either, so
    # its `from config import ...` fails and the ImportError fallback
    # (empty strings) is what loads; handle() takes the values explicitly.
    spec = importlib.util.spec_from_file_location("alexa_lambda_function", ALEXA_DIR / "lambda_function.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


lambda_function = load_lambda()

BASE_URL = "https://example.ui.nabu.casa/"
TOKEN = "ha-long-lived-token"


def alexa_request(request_type, **request_fields):
    request = {"type": request_type, "requestId": "r1", "locale": "en-US"}
    request.update(request_fields)
    return {"version": "1.0", "session": {"new": True}, "request": request}


class FakePost:
    def __init__(self, reply=None, error=None):
        self.reply = reply
        self.error = error
        self.calls = []

    def __call__(self, url, body, headers, timeout):
        self.calls.append((url, body, headers, timeout))
        if self.error is not None:
            raise self.error
        return self.reply


class TestHandle(unittest.TestCase):
    def test_launch_request_greets_and_keeps_the_session_open(self):
        post = FakePost()
        result = lambda_function.handle(alexa_request("LaunchRequest"), BASE_URL, TOKEN, post=post)
        response = result["response"]
        self.assertEqual(result["version"], "1.0")
        self.assertEqual(response["outputSpeech"]["type"], "PlainText")
        self.assertEqual(
            response["outputSpeech"]["text"],
            "What should I add? Say, add milk to the cart, add olive oil to the pantry, "
            "or plan tacos for Thursday.",
        )
        self.assertEqual(response["reprompt"]["outputSpeech"]["type"], "PlainText")
        self.assertTrue(response["reprompt"]["outputSpeech"]["text"])
        self.assertFalse(response["shouldEndSession"])
        self.assertEqual(post.calls, [])

    def test_session_ended_request_ends_silently(self):
        post = FakePost()
        result = lambda_function.handle(alexa_request("SessionEndedRequest", reason="USER_INITIATED"), BASE_URL, TOKEN, post=post)
        self.assertEqual(result, {"version": "1.0", "response": {"shouldEndSession": True}})
        self.assertEqual(post.calls, [])

    def test_intent_request_is_forwarded_with_the_bearer_token_and_ha_reply_returned_verbatim(self):
        ha_reply = {
            "version": "1.0",
            "response": {
                "outputSpeech": {"type": "PlainText", "text": "Added milk to your shopping list, under Dairy & Eggs."},
                "shouldEndSession": True,
            },
        }
        post = FakePost(reply=ha_reply)
        event = alexa_request(
            "IntentRequest",
            intent={"name": "AddShoppingItemIntent", "slots": {"item": {"name": "item", "value": "milk"}}},
        )

        result = lambda_function.handle(event, BASE_URL, TOKEN, post=post)

        self.assertIs(result, ha_reply)
        self.assertEqual(len(post.calls), 1)
        url, body, headers, timeout = post.calls[0]
        self.assertEqual(url, "https://example.ui.nabu.casa/api/alexa")  # trailing slash folded
        self.assertIs(body, event)
        self.assertEqual(headers, {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
        self.assertEqual(timeout, 6)

    def test_unreachable_home_assistant_is_spoken_not_raised(self):
        post = FakePost(error=urllib.error.URLError("down"))
        result = lambda_function.handle(alexa_request("IntentRequest", intent={"name": "AMAZON.HelpIntent"}), BASE_URL, TOKEN, post=post)
        self.assertEqual(result["response"]["outputSpeech"]["text"], "I couldn't reach the meal planner.")
        self.assertTrue(result["response"]["shouldEndSession"])

    def test_http_error_from_home_assistant_is_spoken(self):
        # A 401 (wrong token) or 500 (intent_script error) is an HTTPError.
        error = urllib.error.HTTPError("https://example.ui.nabu.casa/api/alexa", 401, "Unauthorized", {}, None)
        post = FakePost(error=error)
        result = lambda_function.handle(alexa_request("IntentRequest", intent={"name": "AMAZON.HelpIntent"}), BASE_URL, TOKEN, post=post)
        self.assertEqual(result["response"]["outputSpeech"]["text"], "I couldn't reach the meal planner.")

    def test_failure_reason_is_logged_without_the_token(self):
        # CloudWatch captures stdout, so a one-line print is how the Code
        # tab's Logs explain a fallback. Status code and exception type
        # only -- never the token, never the URL.
        import contextlib, io
        error = urllib.error.HTTPError("https://example.ui.nabu.casa/api/alexa", 401, "Unauthorized", {}, None)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            lambda_function.handle(alexa_request("IntentRequest", intent={"name": "AMAZON.HelpIntent"}), BASE_URL, TOKEN, post=FakePost(error=error))
            lambda_function.handle(alexa_request("IntentRequest", intent={"name": "AMAZON.HelpIntent"}), BASE_URL, TOKEN, post=FakePost(error=urllib.error.URLError("down")))
        logged = out.getvalue()
        self.assertIn("HTTPError", logged)
        self.assertIn("401", logged)
        self.assertIn("URLError", logged)
        self.assertNotIn(TOKEN, logged)
        self.assertNotIn("nabu.casa", logged)

    def test_connection_dropped_mid_body_is_spoken_not_raised(self):
        # http.client exceptions are not OSErrors; the contract is still
        # "never raise".
        post = FakePost(error=http.client.IncompleteRead(b""))
        result = lambda_function.handle(alexa_request("IntentRequest", intent={"name": "AMAZON.HelpIntent"}), BASE_URL, TOKEN, post=post)
        self.assertEqual(result["response"]["outputSpeech"]["text"], "I couldn't reach the meal planner.")
        self.assertTrue(result["response"]["shouldEndSession"])

    def test_non_json_reply_is_spoken(self):
        post = FakePost(error=ValueError("not json"))
        result = lambda_function.handle(alexa_request("IntentRequest", intent={"name": "AMAZON.HelpIntent"}), BASE_URL, TOKEN, post=post)
        self.assertEqual(result["response"]["outputSpeech"]["text"], "I couldn't reach the meal planner.")

    def test_lambda_handler_forwards_with_the_config_values(self):
        reply = {"version": "1.0", "response": {"shouldEndSession": True}}
        post = FakePost(reply=reply)
        saved = (lambda_function._post_json, lambda_function.BASE_URL, lambda_function.LONG_LIVED_ACCESS_TOKEN)
        lambda_function._post_json = post
        lambda_function.BASE_URL = "https://h.example"
        lambda_function.LONG_LIVED_ACCESS_TOKEN = "cfg-token"
        try:
            result = lambda_function.lambda_handler(
                alexa_request("IntentRequest", intent={"name": "AMAZON.HelpIntent"}), context=None
            )
        finally:
            lambda_function._post_json, lambda_function.BASE_URL, lambda_function.LONG_LIVED_ACCESS_TOKEN = saved
        self.assertIs(result, reply)
        url, _body, headers, _timeout = post.calls[0]
        self.assertEqual(url, "https://h.example/api/alexa")
        self.assertEqual(headers["Authorization"], "Bearer cfg-token")


if __name__ == "__main__":
    unittest.main()
