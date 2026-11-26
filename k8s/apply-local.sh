#!/bin/sh
set -eu

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ] || { [ "$#" -eq 2 ] && [ "$2" != "--replicas=2" ]; }; then
    echo "usage: $0 <local-image-tag> [--replicas=2]" >&2
    exit 2
fi

image_tag=$1
replicas=1
if [ "$#" -eq 2 ]; then
    replicas=2
fi
case "$image_tag" in
    "" | [.-]* | *[!A-Za-z0-9_.-]*)
        echo "invalid Docker image tag: $image_tag" >&2
        exit 2
        ;;
esac
if [ "${#image_tag}" -gt 128 ]; then
    echo "Docker image tag is longer than 128 characters" >&2
    exit 2
fi

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
rendered_dir=$(mktemp -d)
trap 'rm -rf "$rendered_dir"' EXIT HUP INT TERM

version_at_least_1_32() (
    version=$1
    major=${version%%.*}
    remainder=${version#*.}
    [ "$remainder" != "$version" ] || return 1
    minor=${remainder%%.*}
    patch=${remainder#*.}
    [ "$patch" != "$remainder" ] || return 1
    case "$patch" in *.*) return 1 ;; esac
    for part in "$major" "$minor" "$patch"; do
        case "$part" in "" | *[!0-9]*) return 1 ;; esac
    done
    [ "$major" -gt 1 ] || { [ "$major" -eq 1 ] && [ "$minor" -ge 32 ]; }
)

if [ "$replicas" -eq 2 ]; then
    expected_image="adder-app:$image_tag"
    kubectl -n adder rollout status deployment/app --timeout=10s >/dev/null
    running_image=$(kubectl -n adder get deployment app -o \
        'jsonpath={.spec.template.spec.containers[?(@.name=="adder-app")].image}')
    if [ "$running_image" != "$expected_image" ]; then
        echo "two replicas require the same ready image to be deployed first: expected $expected_image" >&2
        exit 1
    fi
    image_environment=$(docker image inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$running_image")
    build_version=$(printf '%s\n' "$image_environment" | sed -n 's/^ADDER_BUILD_VERSION=//p')
    if [ "$(printf '%s\n' "$build_version" | sed '/^$/d' | wc -l | tr -d ' ')" -ne 1 ] || \
        ! version_at_least_1_32 "$build_version"; then
        echo "two replicas require an image with one ADDER_BUILD_VERSION of 1.32.0 or newer" >&2
        exit 1
    fi
fi

# The Secret is intentionally excluded: create it out of band using secret.yaml's header.
# The app Deployment carries the `image: adder-app:local-build` render placeholder, which the
# tag substitution below replaces.
for manifest in "$script_dir"/*.yaml; do
    case "$(basename -- "$manifest")" in
        secret.yaml | app-deployment.yaml) continue ;;
    esac
    cp "$manifest" "$rendered_dir/"
done

if [ "$replicas" -eq 1 ]; then
    sed "s|image: adder-app:local-build|image: adder-app:$image_tag|" \
        "$script_dir/app-deployment.yaml" >"$rendered_dir/app-deployment.yaml"
else
    sed "s|image: adder-app:local-build|image: adder-app:$image_tag|" \
        "$script_dir/app-deployment.yaml" | awk '
            $0 == "  replicas: 1" { print "  replicas: 2"; next }
            $0 == "            - name: MAIL_TRANSPORT" {
                print "            - name: NOTIFICATIONS_EMAIL_MAX_PER_RUN"
                print "              value: \"50\""
            }
            { print }
        ' >"$rendered_dir/app-deployment.yaml"
    test "$(grep -c '^  replicas: 2$' "$rendered_dir/app-deployment.yaml")" -eq 1
    test "$(grep -c '^            - name: NOTIFICATIONS_EMAIL_MAX_PER_RUN$' "$rendered_dir/app-deployment.yaml")" -eq 1
fi

kubectl -n adder get secret adder-secrets >/dev/null
kubectl apply -f "$rendered_dir"
