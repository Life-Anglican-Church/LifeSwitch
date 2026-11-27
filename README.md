# LifeSWITCH control beta

LifeSWITCH is a separate local remote for the ATEM Television Studio HD. This beta includes Preview source selection, Cut, Auto and DSK 1 Lower thirds. Watch the physical booth multiview while using it. Android beta.2 includes the original LifeSWITCH wordmark and launcher icon.

## Booth setup

1. Download **LifeSWITCH-Setup.exe** from [Releases](https://github.com/Life-Anglican-Church/LifeSwitch/releases) and double-click it on the Windows 10 or 11 x64 booth PC. Accept the Windows administrator prompt.
2. Select the booth PC's address on the trusted church LAN. Enter the ATEM IPv4 address shown in ATEM Setup, or select **Use Simulator** for a rehearsal without a switcher. Use a stable PC address, such as a router DHCP reservation.
3. The setup window shows the booth address and pairing code. Keep the code private. Install the Android APK, open **Connection**, and enter both values. The local beta uses HTTP. The Android device must be able to reach TCP 8765 on the booth PC's trusted local subnet.
4. Start midweek in Monitor only. Change Preview and Lower thirds from the physical controls and check the app readback. Enable Live control and test Preview, Cut, Auto, and DSK 1 against the booth monitor. Compare behavior with ATEM Software Control connected. Disconnect Wi-Fi and check that the app disarms and requires rearming after it reconnects.
5. To remove the service and firewall rule, uninstall **LifeSWITCH Booth** from Windows Settings → Apps. Service logs are under `%ProgramData%\LifeSWITCH\Logs`.

Reopen the installer to view pairing details for an existing installation. To change between Simulator and ATEM modes, uninstall and run setup again. If you installed the original ZIP beta, running the EXE adds the Windows uninstall entry and keeps the existing pairing code.

The service uses the MIT-licensed atem-connection library. LifeSWITCH changes its outbound retransmit handler to drop the session instead of repeating an unacknowledged command packet. A missing confirmation results in **Outcome unknown**. Check the physical switcher and rearm only after checking the actual state. The production package contains no capture or streaming setup.

## Build

- Android toolchain is Android API 35, build tools 35.0.0, Java, and a signing keystore. Run `android/build.sh` with the environment variables documented in that script.
- For Windows packaging, run `npm ci` in `booth`, then run `windows/package.sh exe` from the project root with NSIS (makensis), a Windows x64 Node ZIP, and a WinSW x64 executable. To build the legacy ZIP, run `windows/package.sh zip`. The current bundle uses Node.js 22.22.2 and WinSW 2.12.0.
