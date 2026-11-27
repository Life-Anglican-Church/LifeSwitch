#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${LIFESWITCH_NODE_ZIP:?Set the Windows x64 Node.js ZIP path}"
: "${LIFESWITCH_WINSW_EXE:?Set the WinSW x64 executable path}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
bundle="$work/LifeSWITCH-Booth"
mkdir -p "$bundle/booth" "$bundle/runtime" releases
cp windows/*.ps1 windows/*.xml windows/WinSW-LICENSE.txt "$bundle/"
cp README.md "$bundle/"
cp "$LIFESWITCH_WINSW_EXE" "$bundle/LifeSWITCH-Service.exe"
for asset in node.exe LICENSE; do
    unzip -p "$LIFESWITCH_NODE_ZIP" "node-v*-win-x64/$asset" >"$bundle/runtime/$asset"
done
node booth/disable-retransmit.js
cp booth/server.js booth/package*.json "$bundle/booth/"
cp -R booth/node_modules "$bundle/booth/"
rm -f "$bundle/booth/node_modules/.bin"/*
(
    cd "$work"
    zip -qr LifeSWITCH-Booth-Windows-x64-0.1.0-beta.zip LifeSWITCH-Booth
)
mv "$work/LifeSWITCH-Booth-Windows-x64-0.1.0-beta.zip" releases/
