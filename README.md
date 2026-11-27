# LifeSWITCH control beta

LifeSWITCH is a separate local remote for the ATEM Television Studio HD. This beta includes Preview source selection, Cut, Auto and DSK 1 Lower thirds. Watch the physical booth multiview while using it. Android beta.2 includes the original LifeSWITCH wordmark and launcher icon.

## Booth setup

1. Extract the Windows x64 ZIP on a Windows 10 or 11 booth PC. Open Windows PowerShell as Administrator in the extracted folder.
2. Run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Install-LifeSWITCH.ps1`. Enter the booth PC's IPv4 address on the trusted church LAN and the ATEM address shown in ATEM Setup. Use a stable IP for the PC, such as a router DHCP reservation.
3. Keep the displayed pairing code private. The installer registers an automatic Windows service and allows TCP 8765 on that adapter for the local subnet on Private/Domain profiles. The tablet Wi-Fi must permit traffic to the PC.
4. Install the Android APK, open **Connection**, and enter the booth PC address and pairing code. The LAN beta uses HTTP. Use it on a trusted production subnet, with no Internet port forwarding.
5. Start midweek in Monitor only. Change Preview and Lower thirds from the physical controls and check the app readback. Enable Live control and test Preview, Cut, Auto, and DSK 1 against the booth monitor. Compare behavior with ATEM Software Control connected. Disconnect Wi-Fi and check that the app disarms and requires rearming after it reconnects.
6. To remove the service and firewall rule, run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Uninstall-LifeSWITCH.ps1` from the extracted folder as Administrator. Service logs are under `%ProgramData%\LifeSWITCH\Logs`.

Run the installer with `-Simulator` for a rehearsal without connecting to an ATEM. This is a separate install mode. Uninstall and reinstall to change modes.

The service uses the MIT-licensed atem-connection library. LifeSWITCH changes its outbound retransmit handler to drop the session instead of repeating an unacknowledged command packet. A missing confirmation results in **Outcome unknown**. Check the physical switcher and rearm only after checking the actual state. The production package contains no capture or streaming setup.

## Build

- Android toolchain is Android API 35, build tools 35.0.0, Java, and a signing keystore. Run `android/build.sh` with the environment variables documented in that script.
- For Windows packaging, run `npm ci` in `booth`, then `node disable-retransmit.js` there, then `windows/package.sh` from the project root with a Windows x64 Node ZIP and WinSW x64 executable. The current bundle uses Node.js 22.22.2 and WinSW 2.12.0.
