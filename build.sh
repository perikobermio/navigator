#!/usr/bin/env bash
# Compila el APK firmado y lo deja en ./navigator-<versión>.apk
set -euo pipefail
cd "$(dirname "$0")"
PROXY=()
if [[ -n "${https_proxy:-}" ]]; then
	# Java no lee https_proxy del entorno: se lo pasamos a Gradle.
	hp=${https_proxy#*://}; hp=${hp%/}; host=${hp%:*}; port=${hp##*:}
	PROXY=(-Dhttp.proxyHost="$host" -Dhttp.proxyPort="$port" -Dhttps.proxyHost="$host" -Dhttps.proxyPort="$port"
		"-Dhttp.nonProxyHosts=localhost|127.0.0.1|*.eitb.eus|*.eitb.lan")
fi
./gradlew "${PROXY[@]}" assembleRelease
ver=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
cp app/build/outputs/apk/release/app-release.apk "navigator-$ver.apk"
echo "APK: $PWD/navigator-$ver.apk"
[[ "${1:-}" == "--install" ]] && adb install -r "navigator-$ver.apk"
