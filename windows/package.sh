#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
format=${1:-zip}
case "$format" in
    zip|exe) ;;
    *) echo 'Usage: windows/package.sh [zip|exe]' >&2; exit 2 ;;
esac
: "${LIFESWITCH_NODE_ZIP:?Set the Windows x64 Node.js ZIP path}"
: "${LIFESWITCH_WINSW_EXE:?Set the WinSW x64 executable path}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
bundle="$work/LifeSWITCH-Booth"
mkdir -p "$bundle/booth" "$bundle/runtime" releases
cp windows/Install-LifeSWITCH.ps1 windows/Uninstall-LifeSWITCH.ps1 windows/*.xml windows/WinSW-LICENSE.txt "$bundle/"
cp README.md "$bundle/"
cp "$LIFESWITCH_WINSW_EXE" "$bundle/LifeSWITCH-Service.exe"
for asset in node.exe LICENSE; do
    unzip -p "$LIFESWITCH_NODE_ZIP" "node-v*-win-x64/$asset" >"$bundle/runtime/$asset"
done
node booth/disable-retransmit.js
cp booth/server.js booth/package*.json "$bundle/booth/"
cp -R booth/node_modules "$bundle/booth/"
rm -f "$bundle/booth/node_modules/.bin"/*
if [[ "$format" == exe ]]; then
    cp windows/Setup-LifeSWITCH.ps1 "$bundle/"
    "${MAKENSIS:-makensis}" -WX "-DPAYLOAD_DIR=$bundle" \
        "-DSETUP_OUTPUT=$work/LifeSWITCH-Setup.exe" windows/LifeSWITCH-Setup.nsi
    mv "$work/LifeSWITCH-Setup.exe" releases/
    exit
fi
(
    cd "$work"
    zip -qr LifeSWITCH-Booth-Windows-x64-0.1.0-beta.zip LifeSWITCH-Booth
)
mv "$work/LifeSWITCH-Booth-Windows-x64-0.1.0-beta.zip" releases/
