"""The Alexa-hosted half of the "chef" skill: a forwarder, nothing more.

Home Assistant's /api/alexa endpoint sits behind HA's own auth
middleware, and Alexa never sends an HA bearer token, so the skill's
endpoint cannot be the HA URL directly. This function receives every
request from Alexa, answers the launch/session-end housekeeping itself,
and forwards each intent request to Home Assistant as-is with an
`Authorization: Bearer <long-lived access token>` header, handing HA's
reply straight back to Alexa. All the actual voice logic lives in HA's
intent_script and the Meal Planner.

Paste this file into the skill's Code tab as `lambda_function.py`, and
`config.py` (filled in) next to it. Standard library only -- Alexa-hosted
skills have no build step.
"""
import json
import urllib.request

try:
    from config import BASE_URL, LONG_LIVED_ACCESS_TOKEN
except ImportError:  # tests inject the values through handle()
    BASE_URL = ""
    LONG_LIVED_ACCESS_TOKEN = ""

GREETING = (
    "What should I add? Say, add milk to the cart, add olive oil to the pantry, "
    "or plan tacos for Thursday."
)
REPROMPT = "You can say, add milk to the cart, add olive oil to the pantry, or plan tacos for Thursday."
UNREACHABLE = "I couldn't reach the meal planner."
TIMEOUT_SECONDS = 6


def _speak(text, end_session, reprompt=None):
    response = {
        "outputSpeech": {"type": "PlainText", "text": text},
        "shouldEndSession": end_session,
    }
    if reprompt is not None:
        response["reprompt"] = {"outputSpeech": {"type": "PlainText", "text": reprompt}}
    return {"version": "1.0", "response": response}


def _post_json(url, body, headers, timeout):
    """POSTs `body` as JSON and returns the parsed JSON reply. The only
    function here that touches the network."""
    data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as reply:
        return json.loads(reply.read().decode("utf-8"))


def handle(event, base_url, token, post=None):
    """Pure request logic: given the Alexa request envelope, return the
    Alexa response envelope. `post` is injectable so tests never hit the
    network (it defaults to _post_json, looked up at call time). Never
    raises -- any transport or parse failure becomes a spoken sentence."""
    if post is None:
        post = _post_json
    request_type = (event.get("request") or {}).get("type")
    if request_type == "LaunchRequest":
        return _speak(GREETING, end_session=False, reprompt=REPROMPT)
    if request_type == "SessionEndedRequest":
        return {"version": "1.0", "response": {"shouldEndSession": True}}

    url = base_url.rstrip("/") + "/api/alexa"
    headers = {
        "Authorization": "Bearer " + token,
        "Content-Type": "application/json",
    }
    try:
        reply = post(url, event, headers, TIMEOUT_SECONDS)
    except Exception as exc:
        # Deliberately broad: the contract is "never raise". URLError /
        # HTTPError / socket timeout are OSErrors, a non-JSON body is a
        # ValueError, but http.client exceptions (IncompleteRead when the
        # connection drops mid-body, BadStatusLine, InvalidURL) are none
        # of those -- and every one of them means the same thing to the
        # user: Home Assistant couldn't be reached properly. The print
        # lands in the skill's CloudWatch log (Code tab -> Logs) so the
        # reason is visible; it names the exception class and any HTTP
        # status, never the token or the URL.
        status = getattr(exc, "code", "")
        print(f"chef forwarder: Home Assistant call failed: {type(exc).__name__} {status}".rstrip())
        return _speak(UNREACHABLE, end_session=True)
    if not isinstance(reply, dict):
        print("chef forwarder: Home Assistant replied with something other than a JSON object")
        return _speak(UNREACHABLE, end_session=True)
    return reply


def lambda_handler(event, context):
    """Entry point named in the Alexa-hosted skill's runtime settings."""
    return handle(event, BASE_URL, LONG_LIVED_ACCESS_TOKEN)
