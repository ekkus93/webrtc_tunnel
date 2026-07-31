#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
android_dir="$repo_root/android"
keystore=${ANDROID_RELEASE_KEYSTORE_PATH:?ANDROID_RELEASE_KEYSTORE_PATH is required}
store_password=${ANDROID_RELEASE_STORE_PASSWORD:?ANDROID_RELEASE_STORE_PASSWORD is required}
key_alias=${ANDROID_RELEASE_KEY_ALIAS:?ANDROID_RELEASE_KEY_ALIAS is required}
key_password=${ANDROID_RELEASE_KEY_PASSWORD:?ANDROID_RELEASE_KEY_PASSWORD is required}
fingerprint=${ANDROID_RELEASE_CERT_SHA256:?ANDROID_RELEASE_CERT_SHA256 is required}

log_dir=$(mktemp -d)
trap 'rm -rf "$log_dir"' EXIT

expect_failure() {
    local label=$1
    shift
    local log="$log_dir/${label}.log"
    if "$@" >"$log" 2>&1; then
        printf 'negative release check unexpectedly passed: %s\n' "$label" >&2
        exit 1
    fi
    for secret in "$store_password" "$key_password"; do
        if grep -Fq -- "$secret" "$log"; then
            printf 'negative release check leaked a signing password: %s\n' "$label" >&2
            exit 1
        fi
    done
    printf 'PASS negative release contract: %s\n' "$label"
}

gradle_validation=(
    "$android_dir/gradlew"
    --no-daemon
    -p "$android_dir"
    -PskipRustBuild=true
    -PproductionRelease=true
    ":app:validateProductionReleaseSigning"
)

expect_failure missing-password \
    env -u ANDROID_RELEASE_STORE_PASSWORD \
    ANDROID_RELEASE_KEYSTORE_PATH="$keystore" \
    ANDROID_RELEASE_KEY_ALIAS="$key_alias" \
    ANDROID_RELEASE_KEY_PASSWORD="$key_password" \
    "${gradle_validation[@]}" -PreleaseCertificateSha256="$fingerprint"

expect_failure missing-keystore \
    env ANDROID_RELEASE_KEYSTORE_PATH="$log_dir/does-not-exist.p12" \
    ANDROID_RELEASE_STORE_PASSWORD="$store_password" \
    ANDROID_RELEASE_KEY_ALIAS="$key_alias" \
    ANDROID_RELEASE_KEY_PASSWORD="$key_password" \
    "${gradle_validation[@]}" -PreleaseCertificateSha256="$fingerprint"

printf 'not a keystore\n' >"$log_dir/invalid.p12"
chmod 600 "$log_dir/invalid.p12"
expect_failure invalid-keystore \
    env ANDROID_RELEASE_KEYSTORE_PATH="$log_dir/invalid.p12" \
    ANDROID_RELEASE_STORE_PASSWORD="$store_password" \
    ANDROID_RELEASE_KEY_ALIAS="$key_alias" \
    ANDROID_RELEASE_KEY_PASSWORD="$key_password" \
    "${gradle_validation[@]}" -PreleaseCertificateSha256="$fingerprint"

expect_failure wrong-alias \
    env ANDROID_RELEASE_KEYSTORE_PATH="$keystore" \
    ANDROID_RELEASE_STORE_PASSWORD="$store_password" \
    ANDROID_RELEASE_KEY_ALIAS="definitely-not-the-release-alias" \
    ANDROID_RELEASE_KEY_PASSWORD="$key_password" \
    "${gradle_validation[@]}" -PreleaseCertificateSha256="$fingerprint"

wrong_fingerprint=$(printf '00%.0s' {1..32})
if [[ "$wrong_fingerprint" == "$fingerprint" ]]; then
    wrong_fingerprint=$(printf 'ff%.0s' {1..32})
fi
expect_failure fingerprint-mismatch \
    env ANDROID_RELEASE_KEYSTORE_PATH="$keystore" \
    ANDROID_RELEASE_STORE_PASSWORD="$store_password" \
    ANDROID_RELEASE_KEY_ALIAS="$key_alias" \
    ANDROID_RELEASE_KEY_PASSWORD="$key_password" \
    "${gradle_validation[@]}" -PreleaseCertificateSha256="$wrong_fingerprint"

expect_failure tag-version-mismatch \
    python3 "$repo_root/scripts/android_release.py" validate-tag \
    --properties "$repo_root/android/version.properties" \
    --repo-root "$repo_root" \
    --tag v999.0.0 \
    --sha "${GITHUB_SHA:-0000000000000000000000000000000000000000}"

printf 'All negative Android release contracts failed closed as expected.\n'
