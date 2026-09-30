#!/usr/bin/env bash
# Exercise the Linux install end to end, without systemd. Run by
# pi/Dockerfile.test, but works on any Linux box with the repo checked out:
#   bash pi/smoke.sh
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

pass=0; fail=0
ok()   { pass=$((pass+1)); printf '  PASS  %s\n' "$*"; }
bad()  { fail=$((fail+1)); printf '  FAIL  %s\n' "$*"; }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }

echo "==> platform"
echo "  $(uname -srm); $(python3 --version)"

echo "==> pi/install.sh (venv + packages; no service in a container)"
# Feed set_password.py a password twice; SUDO_USER unset; systemctl absent.
if printf 'test-pw\ntest-pw\n' | ./pi/install.sh > /tmp/install.log 2>&1; then
  ok "install.sh completed"
else
  bad "install.sh exited $? -- log:"; tail -40 /tmp/install.log; exit 1
fi
grep -q "no systemd here" /tmp/install.log && ok "install.sh noticed there is no systemd" || bad "install.sh did not fall back cleanly"
check "secrets file written to ~/.config/meal-planner/.env" 'grep -q MEAL_PLANNER_PASSWORD_HASH ~/.config/meal-planner/.env'
check "MEAL_PLANNER_HOST=0.0.0.0 added" 'grep -q "^MEAL_PLANNER_HOST=0.0.0.0" ~/.config/meal-planner/.env'

echo "==> unit tests on this platform"
if .venv/bin/python -m unittest discover -s tests > /tmp/tests.log 2>&1; then
  ok "$(grep -E '^Ran' /tmp/tests.log)"
else
  bad "unit tests failed:"; grep -E '^(FAIL|ERROR):' /tmp/tests.log | head; tail -5 /tmp/tests.log
fi

echo "==> start the app the way the service does"
.venv/bin/python app.py > /tmp/app.log 2>&1 &
APP=$!
for i in $(seq 1 30); do curl -fs http://127.0.0.1:5000/healthz > /dev/null 2>&1 && break; sleep 1; done
check "app answers /healthz" 'curl -fs http://127.0.0.1:5000/healthz | grep -q "\"ok\": *true"'
check "data folder created at ~/meal-planner" 'test -d ~/meal-planner/recipes && test -f ~/meal-planner/mealplanner.db'
check "startup line names the data folder" 'grep -q "data: /home/pi/meal-planner" /tmp/app.log'

echo "==> log in, import a recipe with a photo, view it"
JAR=/tmp/cookies
check "login with the household password" 'curl -fs -c $JAR -o /dev/null -w "%{http_code}" -d "password=test-pw" http://127.0.0.1:5000/login | grep -q 302'
check "wrong password refused" 'curl -s -o /dev/null -w "%{http_code}" -d "password=nope" http://127.0.0.1:5000/login | grep -q 401'
check "recipes page renders" 'curl -fs -b $JAR http://127.0.0.1:5000/recipes | grep -q "Recipes"'
check "upload a recipe YAML" 'curl -fs -b $JAR -o /dev/null -w "%{http_code}" -F "recipe_file=@tests/fixtures/banana-bread.yaml" http://127.0.0.1:5000/recipes/import | grep -q 302'
check "review screen shows it" 'curl -fs -b $JAR http://127.0.0.1:5000/recipes/import/review | grep -q "Banana Bread"'
check "confirm import" 'curl -fs -b $JAR -L -d "category_0=Breads%20%26%20Baking" http://127.0.0.1:5000/recipes/import/confirm | grep -q "Imported 1 recipe"'
check "recipe YAML written to the data folder" 'test -f ~/meal-planner/recipes/banana-bread.yaml'
ID=$(curl -fs -b $JAR http://127.0.0.1:5000/recipes | grep -o 'href="/recipes/[0-9]*">Banana Bread' | grep -o '[0-9]*' | head -1)
check "recipe page renders" "curl -fs -b $JAR http://127.0.0.1:5000/recipes/$ID | grep -q 'Banana Bread'"
.venv/bin/python -c "from PIL import Image; Image.new('RGB',(300,200),(200,80,60)).save('/tmp/photo.jpg','JPEG')"
check "photo upload (Pillow + libjpeg)" "curl -fs -b $JAR -L -F 'image=@/tmp/photo.jpg' http://127.0.0.1:5000/recipes/$ID/image | grep -q 'recipe-images/'"
check "photo served back" "curl -fs -b $JAR http://127.0.0.1:5000/recipes/$ID | grep -o '/recipe-images/[^\"]*' | head -1 | xargs -I{} curl -fs -o /dev/null -w '%{content_type}' -b $JAR http://127.0.0.1:5000{} | grep -q image/jpeg"
check "shopping list page renders" 'curl -fs -b $JAR http://127.0.0.1:5000/shopping-list | grep -q "Shopping List"'
check "add to shopping list" 'curl -fs -b $JAR -L -d "name=eggs&amount=12" http://127.0.0.1:5000/shopping-list/add | grep -q "12 eggs"'

kill $APP 2>/dev/null; wait $APP 2>/dev/null
echo
echo "==> $pass passed, $fail failed"
[ $fail -eq 0 ]
