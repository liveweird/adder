#!/bin/sh
set -eu

if [ "$#" -gt 1 ]; then
    echo "usage: $0 [render-output-root]" >&2
    exit 2
fi

render_output_root=${1:-}
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
elif [ "$1" = "-n" ] && [ "$3" = "get" ] && [ "$4" = "pods" ]; then
    printf '%s\n' "$KUBECTL_PODS"
elif [ "$1" = "-n" ] && [ "$3" = "exec" ]; then
    [ "${KUBECTL_EXEC_FAIL_POD:-}" != "$4" ] || exit 1
    version=$KUBECTL_RUNTIME_VERSION
    commit=$KUBECTL_RUNTIME_COMMIT
    if [ "$4" = "app-two" ]; then
        version=${KUBECTL_RUNTIME_VERSION_TWO:-$version}
        commit=${KUBECTL_RUNTIME_COMMIT_TWO:-$commit}
    fi
    printf '%s\n%s\n' "$version" "$commit"
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
printf 'PATH=/usr/bin\nADDER_BUILD_VERSION=%s\nADDER_BUILD_COMMIT=%s\n' "$DOCKER_BUILD_VERSION" "$DOCKER_BUILD_COMMIT"
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
single_rendered=$RENDER_OUT
grep -q '^kubectl -n adder get secret adder-secrets$' "$COMMAND_LOG"
grep -q '^kubectl apply -f ' "$COMMAND_LOG"
if grep -q '^docker ' "$COMMAND_LOG"; then echo "default render inspected Docker" >&2; exit 1; fi
if grep -qE '^kubectl -n adder (get pods|exec) ' "$COMMAND_LOG"; then
    echo "default render inspected running app pods" >&2
    exit 1
fi
test "$(grep -c '^  replicas: 1$' "$RENDER_OUT/app-deployment.yaml")" -eq 1
if grep -q 'NOTIFICATIONS_EMAIL_MAX_PER_RUN' "$RENDER_OUT/app-deployment.yaml"; then
    echo "default render changed the digest budget" >&2
    exit 1
fi
grep -q 'image: adder-app:test-single' "$RENDER_OUT/app-deployment.yaml"

new_case two
export KUBECTL_READY=1 KUBECTL_IMAGE=adder-app:test-two KUBECTL_PODS='app-one
app-two'
export DOCKER_BUILD_VERSION=1.32.0 DOCKER_BUILD_COMMIT=0123456789abcdef
export KUBECTL_RUNTIME_VERSION=1.32.0 KUBECTL_RUNTIME_COMMIT=0123456789abcdef
unset KUBECTL_RUNTIME_VERSION_TWO KUBECTL_RUNTIME_COMMIT_TWO KUBECTL_EXEC_FAIL_POD
PATH="$bin_dir:$PATH" sh "$script_dir/apply-local.sh" test-two --replicas=2
two_rendered=$RENDER_OUT
grep -q '^kubectl -n adder rollout status deployment/app --timeout=10s$' "$COMMAND_LOG"
grep -q '^kubectl -n adder get deployment app -o jsonpath=' "$COMMAND_LOG"
grep -q '^docker image inspect --format ' "$COMMAND_LOG"
test "$(grep -c '^kubectl -n adder exec app-' "$COMMAND_LOG")" -eq 2
grep -q '^kubectl -n adder exec app-one -c adder-app -- printenv ADDER_BUILD_VERSION ADDER_BUILD_COMMIT$' "$COMMAND_LOG"
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

export KUBECTL_READY=0 KUBECTL_IMAGE=adder-app:not-ready DOCKER_BUILD_VERSION=1.32.0 DOCKER_BUILD_COMMIT=0123456789abcdef
expect_preflight_failure not-ready not-ready --replicas=2
export KUBECTL_READY=1 KUBECTL_IMAGE=adder-app:other DOCKER_BUILD_VERSION=1.32.0
expect_preflight_failure image-mismatch requested --replicas=2
export KUBECTL_READY=1 KUBECTL_IMAGE=adder-app:old DOCKER_BUILD_VERSION=1.31.99 DOCKER_BUILD_COMMIT=0123456789abcdef
expect_preflight_failure old-version old --replicas=2
export KUBECTL_READY=1 KUBECTL_IMAGE=adder-app:missing DOCKER_BUILD_VERSION='' DOCKER_BUILD_COMMIT=0123456789abcdef
expect_preflight_failure missing-version missing --replicas=2
export DOCKER_BUILD_VERSION=1.32.0 DOCKER_BUILD_COMMIT=''
expect_preflight_failure missing-commit missing --replicas=2

export KUBECTL_READY=1 KUBECTL_IMAGE=adder-app:retagged DOCKER_BUILD_VERSION=1.32.0 DOCKER_BUILD_COMMIT=new-build
export KUBECTL_PODS=app-one KUBECTL_RUNTIME_VERSION=1.31.0 KUBECTL_RUNTIME_COMMIT=old-build
expect_preflight_failure reused-tag retagged --replicas=2

export KUBECTL_RUNTIME_VERSION=1.32.0 KUBECTL_RUNTIME_COMMIT=new-build KUBECTL_PODS='app-one
app-two' KUBECTL_RUNTIME_COMMIT_TWO=other-build
expect_preflight_failure one-pod-mismatches retagged --replicas=2
unset KUBECTL_RUNTIME_COMMIT_TWO

export KUBECTL_RUNTIME_COMMIT=''
expect_preflight_failure missing-runtime-stamp retagged --replicas=2

export KUBECTL_RUNTIME_COMMIT=new-build KUBECTL_EXEC_FAIL_POD=app-one
expect_preflight_failure runtime-inspection-failed retagged --replicas=2
unset KUBECTL_EXEC_FAIL_POD
expect_preflight_failure invalid-option image --replicas=3

if [ -n "$render_output_root" ]; then
    single_destination="$render_output_root/rendered-single"
    two_destination="$render_output_root/rendered-two"
    if [ -e "$single_destination" ] || [ -e "$two_destination" ]; then
        echo "render output directories already exist under $render_output_root" >&2
        exit 1
    fi
    mkdir -p "$single_destination" "$two_destination"
    cp "$single_rendered"/*.yaml "$single_destination/"
    cp "$two_rendered"/*.yaml "$two_destination/"
fi

echo "apply-local render checks passed"
