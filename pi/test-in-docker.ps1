# Smoke-test the Raspberry Pi install inside an emulated arm64 Debian
# container, from a Windows PC with Docker Desktop running.
#
#   .\pi\test-in-docker.ps1
#
# Builds an image from the repo, runs pi/install.sh in it (minus the
# systemd part, which a container doesn't have), starts the app, logs in,
# imports a fixture recipe and checks the pages. Prints PASS/FAIL per step.
# Nothing on this PC is touched; the container is removed afterwards.
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$image = "meal-planner-pi-test"

Write-Host "==> building arm64 test image (first run pulls ~50 MB and emulates ARM, so give it a few minutes)"
docker build --platform linux/arm64 -t $image -f "$repo\pi\Dockerfile.test" $repo
if ($LASTEXITCODE -ne 0) { throw "docker build failed" }

Write-Host "==> running the install + app smoke test inside the container"
docker run --rm --platform linux/arm64 $image
if ($LASTEXITCODE -ne 0) { throw "smoke test FAILED (see output above)" }
Write-Host "==> all steps passed on linux/arm64"
