# LifeSWITCH control beta

LifeSWITCH is a separate local remote for the ATEM Television Studio HD. This beta includes Preview source selection, Cut, Auto and DSK 1 Lower thirds. Watch the physical booth multiview while using it. The Android app includes the original LifeSWITCH wordmark and launcher icon.

## Booth setup

1. Download **LifeSWITCH-Setup.exe** from [Releases](https://github.com/Life-Anglican-Church/LifeSwitch/releases) and double-click it on the Windows 10 or 11 x64 booth PC. Accept the Windows administrator prompt.
2. Select the booth PC's address on the trusted church LAN. Enter the ATEM IPv4 address shown in ATEM Setup, or select **Use Simulator** for a rehearsal without a switcher. Use a stable PC address, such as a router DHCP reservation.
3. The setup window shows a pairing QR. In the Android APK, choose **Connection → Scan booth QR**, grant camera access, scan, then tap **Connect**. The booth address and code are also available for manual entry. Keep the QR and code private. The local beta uses HTTP. The Android device must be able to reach TCP 8765 on the booth PC's trusted local subnet.
4. Start midweek in Monitor only. Change Preview and Lower thirds from the physical controls and check the app readback. Enable Live control and test Preview, Cut, Auto, and DSK 1 against the booth monitor. Compare behavior with ATEM Software Control connected. Disconnect Wi-Fi and check that the app disarms and requires rearming after it reconnects.
5. To remove the service and firewall rule, uninstall **LifeSWITCH Booth** from Windows Settings → Apps. Service logs are under `%ProgramData%\LifeSWITCH\Logs`.

Find **LifeSWITCH Booth** by the Windows clock or under the notification area overflow arrow. You can also open it from Start. The tray shows booth status. Choose **Pair a device** to show the QR again or **Settings** to change the ATEM address or Simulator mode. Windows requests administrator approval for pairing and settings. Changing settings restarts the service and disarms remotes. **Exit tray** closes the icon and leaves the service running. Installing over a previous beta preserves the pairing code.

The service uses the MIT-licensed atem-connection library. LifeSWITCH changes its outbound retransmit handler to drop the session instead of repeating an unacknowledged command packet. A missing confirmation results in **Outcome unknown**. Check the physical switcher and rearm only after checking the actual state. The production package contains no capture or streaming setup.

## Build

- Android toolchain is Android API 35, build tools 35.0.0, Java, the ZXing Core 3.5.3 JAR, and a signing keystore. Run `android/build.sh` with the environment variables documented in that script.
- For Windows packaging, run `npm ci` in `booth`, then run `windows/package.sh exe` from the project root with NSIS (makensis), Python with Pillow, a Windows x64 Node ZIP, and a WinSW x64 executable. To build the legacy ZIP, run `windows/package.sh zip`. The current bundle uses Node.js 22.22.2 and WinSW 2.12.0.

### Publish an Android update

The Android app checks `releases/android-update.json` on the public `beta-downloads` branch at startup. Publish the signed `LifeSWITCH-Android-<versionName>.apk` and update this JSON in the same commit. Set `versionCode` and `versionName` to the values in the APK manifest. Increase `versionCode` and sign with the existing Android signing key so devices can update in place. The app offers the download when the published version code is higher.
