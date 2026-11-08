#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "$(uname -s)" != Darwin ]]; then
    echo "This setup script is for macOS. Other hosts need JDK 17 and Android SDK 35."
    exit 1
fi
command -v conda >/dev/null || { echo "Install Miniforge or Miniconda first."; exit 1; }
mkdir -p .tools/downloads .tools/android-sdk/cmdline-tools
export CONDA_PKGS_DIRS="$PWD/.tools/conda-pkgs"
if [[ ! -x .tools/env/lib/jvm/bin/java ]]; then
    conda env create --prefix "$PWD/.tools/env" --file environment.yml
fi
if [[ ! -x .tools/android-sdk/cmdline-tools/19.0/bin/sdkmanager ]]; then
    archive="$PWD/.tools/downloads/commandline-tools.zip"
    curl --fail --location --retry 3 \
        https://dl.google.com/android/repository/commandlinetools-mac-13114758_latest.zip \
        --output "$archive"
    expected=5673201e6f3869f418eeed3b5cb6c4be7401502bd0aae1b12a29d164d647a54e
    actual="$(shasum -a 256 "$archive" | cut -d ' ' -f 1)"
    [[ "$actual" == "$expected" ]] || { echo "SDK archive checksum mismatch."; exit 1; }
    staging="$(mktemp -d "$PWD/.tools/downloads/sdk.XXXXXX")"
    unzip -q "$archive" -d "$staging"
    mv "$staging/cmdline-tools" .tools/android-sdk/cmdline-tools/19.0
    rmdir "$staging"
fi
source scripts/env.sh
sdkmanager --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
echo "Setup complete. Run: source scripts/env.sh"
echo "Then: ./gradlew --no-daemon clean assembleDebug test lint"
