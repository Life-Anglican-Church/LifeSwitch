Unicode true
Name "LifeSWITCH Booth"
OutFile "${TRAY_OUTPUT}"
Icon "${TRAY_ICON}"
RequestExecutionLevel user
SilentInstall silent
AutoCloseWindow true

Section
    SetOutPath "$EXEDIR"
    nsExec::Exec '"$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$EXEDIR\LifeSWITCH-Tray.ps1"'
    Pop $0
    StrCmp $0 0 done
    MessageBox MB_ICONSTOP "LifeSWITCH could not start the booth tray."
    done:
SectionEnd
