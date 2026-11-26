#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT HUP INT TERM
bin_dir="$work_dir/bin"
mkdir -p "$bin_dir"

cat >"$bin_dir/kubectl" <<'STUB'
#!/bin/sh
printf 'kubectl %s\n' "$*" >>"$COMMAND_LOG"
if [ "$1" = "-n" ] && [ "$3" = "rollout" ]; then
    [ "${KUBECTL_READY:-0}" = "1" ]
elif [ "$1" = "-n" ] && [ "$3" = "get" ] && [ "$4" = "deployment" ]; then
    printf '%s' "$KUBECTL_IMAGE"
elif [ "$1" = "-n" ] && [ "$3" = "get" ] && [ "$4" = "secret" ]; then
    exit 0
elif [ "$1" = "apply" ]; then
    mkdir -p "$RENDER_OUT"
    cp "$3"/*.yaml "$RENDER_OUT/"
else
    echo "unexpected kubectl invocation: $*" >&2
    exit 64
fi
STUB

cat >"$bin_dir/docker" <<'STUB'
#!/bin/sh
printf 'docker %s\n' "$*" >>"$COMMAND_LOG"
[ "$1" = "image" ] && [ "$2" = "inspect" ] || exit 64
printf 'PATH=/usr/bin\nADDER_BUILD_VERSION=%s\n' "$DOCKER_BUILD_VERSION"
STUB
chmod +x "$bin_dir/kubectl" "$bin_dir/docker"

new_case() {
    case_dir="$work_dir/$1"
    mkdir -p "$case_dir/rendered"
    export COMMAND_LOG="$case_dir/commands"
    export RENDER_OUT="$case_dir/rendered"
    : >"$COMMAND_LOG"
}

new_case default
PATH="$bin_dir:$PATH" sh "$script_dir/apply-local.sh" test-single
grep -q '^kubectl -n adder get secret adder-secrets$' "$COMMAND_LOG"
grep -q '^kubectl apply -f ' "$COMMAND_LOG"
if grep -q '^docker ' "$COMMAND_LOG"; then echo "default render inspected Docker" >&2; exit 1; fi
test "$(grep -c '^  replicas: 1$' "$RENDER_OUT/app-deployment.yaml")" -eq 1
if grep -q 'NOTIFICATIONS_EMAIL_MAX_PER_RUN' "$RENDER_OUT/app-deployment.yaml"; then
    echo "default render changed the digest budget" >&2
    exit 1
fi
grep -q 'image: adder-app:test-single' "$RENDER_OUT/app-deployment.yaml"

new_case two
export KUBECTL_READY=1 KUBECTL_IMAGE=adder-app:test-two DOCKER_BUILD_VERSION=1.32.0
PATH="$bin_dir:$PATH" sh "$script_dir/apply-local.sh" test-two --replicas=2
grep -q '^kubectl -n adder rollout status deployment/app --timeout=10s$' "$COMMAND_LOG"
grep -q '^kubectl -n adder get deployment app -o jsonpath=' "$COMMAND_LOG"
grep -q '^docker image inspect --format ' "$COMMAND_LOG"
grep -q '^kubectl apply -f ' "$COMMAND_LOG"
test "$(grep -c '^  replicas: 2$' "$RENDER_OUT/app-deployment.yaml")" -eq 1
test "$(grep -c '^    type: Recreate$' "$RENDER_OUT/app-deployment.yaml")" -eq 1
grep -A1 '^            - name: NOTIFICATIONS_EMAIL_MAX_PER_RUN$' "$RENDER_OUT/app-deployment.yaml" | grep -q 'value: "50"'
grep -q 'image: adder-app:test-two' "$RENDER_OUT/app-deployment.yaml"

expect_preflight_failure() {
    name=$1
    shift
    new_case "$name"
    if PATH="$bin_dir:$PATH" sh "$script_dir/apply-local.sh" "$@" >/dev/null 2>&1; then
        echo "$name unexpectedly succeeded" >&2
        exit 1
    fi
    if grep -q '^kubectl apply -f ' "$COMMAND_LOG"; then
        echo "$name reached kubectl apply" >&2
        exit 1
    fi
}

export KUBECTL_READY=0 KUBECTL_IMAGE=adder-app:not-ready DOCKER_BUILD_VERSION=1.32.0
expect_preflight_failure not-ready not-ready --replicas=2
export KUBECTL_READY=1 KUBECTL_IMAGE=adder-app:other DOCKER_BUILD_VERSION=1.32.0
expect_preflight_failure image-mismatch requested --replicas=2
export KUBECTL_READY=1 KUBECTL_IMAGE=adder-app:old DOCKER_BUILD_VERSION=1.31.99
expect_preflight_failure old-version old --replicas=2
export KUBECTL_READY=1 KUBECTL_IMAGE=adder-app:missing DOCKER_BUILD_VERSION=
expect_preflight_failure missing-version missing --replicas=2
expect_preflight_failure invalid-option image --replicas=3

echo "apply-local render checks passed"
