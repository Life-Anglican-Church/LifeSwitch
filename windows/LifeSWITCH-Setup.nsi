Unicode true
!include "x64.nsh"

Name "LifeSWITCH Booth"
OutFile "${SETUP_OUTPUT}"
RequestExecutionLevel admin
SilentInstall silent
InstallDir "$PROGRAMFILES64\LifeSWITCH"
Icon "${PAYLOAD_DIR}\LifeSWITCH.ico"
VIProductVersion "0.1.0.4"
VIAddVersionKey "ProductName" "LifeSWITCH Booth"
VIAddVersionKey "FileDescription" "LifeSWITCH Booth Setup"
VIAddVersionKey "FileVersion" "0.1.0 beta.4"
VIAddVersionKey "ProductVersion" "0.1.0 beta.4"
VIAddVersionKey "CompanyName" "Life Production Suite"
VIAddVersionKey "LegalCopyright" "Life Production Suite"
SetCompressor /SOLID lzma

!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\LifeSWITCHBooth"

Function .onInit
    ${IfNot} ${RunningX64}
        MessageBox MB_ICONSTOP "LifeSWITCH Booth requires 64-bit Windows."
        Abort
    ${EndIf}
    SetRegView 64
FunctionEnd

Section Install
    InitPluginsDir
    SetOutPath "$PLUGINSDIR"
    File /r "${PAYLOAD_DIR}\*"
    ExecWait '"$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "$PLUGINSDIR\Setup-LifeSWITCH.ps1"' $0
    IfErrors launch_failed
    StrCmp $0 0 installed
    StrCmp $0 2 cancelled
    SetErrorLevel 1
    Goto cancelled

    launch_failed:
    MessageBox MB_ICONSTOP "Windows could not start LifeSWITCH Setup."
    SetErrorLevel 1
    Goto cancelled

    installed:
    SetOutPath "$INSTDIR"
    File "${PAYLOAD_DIR}\Uninstall-LifeSWITCH.ps1"
    File "${PAYLOAD_DIR}\Setup-LifeSWITCH.ps1"
    File "${PAYLOAD_DIR}\Booth-Management.ps1"
    File "${PAYLOAD_DIR}\LifeSWITCH-Tray.ps1"
    File "${PAYLOAD_DIR}\LifeSWITCH.ico"
    SetOutPath "$INSTDIR\booth"
    File "${PAYLOAD_DIR}\booth\pairing-qr.js"
    SetOutPath "$INSTDIR\booth\node_modules\qrcode-generator"
    File /r "${PAYLOAD_DIR}\booth\node_modules\qrcode-generator\*"
    SetOutPath "$INSTDIR"
    WriteUninstaller "$INSTDIR\Uninstall-LifeSWITCH.exe"
    WriteRegStr HKLM "${UNINSTALL_KEY}" "DisplayName" "LifeSWITCH Booth"
    WriteRegStr HKLM "${UNINSTALL_KEY}" "UninstallString" '"$INSTDIR\Uninstall-LifeSWITCH.exe"'
    WriteRegStr HKLM "${UNINSTALL_KEY}" "DisplayVersion" "0.1.0 beta.4"
    WriteRegStr HKLM "${UNINSTALL_KEY}" "Publisher" "Life Production Suite"
    WriteRegDWORD HKLM "${UNINSTALL_KEY}" "NoModify" 1
    WriteRegDWORD HKLM "${UNINSTALL_KEY}" "NoRepair" 1

    SetShellVarContext all
    CreateShortcut "$SMSTARTUP\LifeSWITCH Booth.lnk" "$WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "$INSTDIR\LifeSWITCH-Tray.ps1"' "$INSTDIR\LifeSWITCH.ico"
    CreateShortcut "$SMPROGRAMS\LifeSWITCH Booth.lnk" "$WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "$INSTDIR\LifeSWITCH-Tray.ps1"' "$INSTDIR\LifeSWITCH.ico"
    ExecShell "open" "$WINDIR\explorer.exe" '"$SMPROGRAMS\LifeSWITCH Booth.lnk"'

    cancelled:
SectionEnd

UninstPage uninstConfirm
UninstPage instfiles

Section Uninstall
    SetRegView 64
    SetOutPath "$TEMP"
    nsExec::ExecToStack '"$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\Uninstall-LifeSWITCH.ps1"'
    Pop $0
    Pop $1
    StrCmp $0 0 done
    MessageBox MB_ICONSTOP "LifeSWITCH could not be removed.$\r$\n$1"
    Abort

    done:
    SetShellVarContext all
    Delete "$SMSTARTUP\LifeSWITCH Booth.lnk"
    Delete "$SMPROGRAMS\LifeSWITCH Booth.lnk"
    DeleteRegKey HKLM "${UNINSTALL_KEY}"
SectionEnd
