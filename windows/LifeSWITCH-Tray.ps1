$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()

$mutex = New-Object Threading.Mutex($false, 'Local\LifeSWITCHBoothTray')
if (-not $mutex.WaitOne(0)) { $mutex.Dispose(); exit 0 }
$trayScript = $PSCommandPath
$management = Join-Path $PSScriptRoot 'Setup-LifeSWITCH.ps1'

function Open-BoothDialog([string] $mode) {
    $powerShell = Join-Path $PSHOME 'powershell.exe'
    $arguments = "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$management`" -Mode $mode"
    try { Start-Process -FilePath $powerShell -ArgumentList $arguments -Verb RunAs }
    catch {
        if ($_.Exception.NativeErrorCode -ne 1223) {
            [void] [Windows.Forms.MessageBox]::Show($_.Exception.Message, 'LifeSWITCH Booth', 'OK', 'Error')
        }
    }
}

$icon = New-Object Drawing.Icon((Join-Path $PSScriptRoot 'LifeSWITCH.ico'))
$tray = New-Object Windows.Forms.NotifyIcon
$tray.Icon = $icon
$menu = New-Object Windows.Forms.ContextMenuStrip
$status = $menu.Items.Add('Checking booth service...')
$status.Enabled = $false
[void] $menu.Items.Add((New-Object Windows.Forms.ToolStripSeparator))
$pair = $menu.Items.Add('Pair a device...')
$settings = $menu.Items.Add('Settings...')
$quit = $menu.Items.Add('Exit tray')
$context = New-Object Windows.Forms.ApplicationContext
$tray.ContextMenuStrip = $menu
$tray.Text = 'LifeSWITCH Booth'
$pair.Add_Click({ Open-BoothDialog 'Pair' })
$settings.Add_Click({ Open-BoothDialog 'Settings' })
$quit.Add_Click({ $context.ExitThread() })
$tray.Add_MouseDoubleClick({ if ($_.Button -eq 'Left') { Open-BoothDialog 'Pair' } })

function Refresh-BoothStatus {
    if (-not (Test-Path -LiteralPath $trayScript)) { $context.ExitThread(); return }
    try {
        $state = Invoke-RestMethod -Uri 'http://127.0.0.1:8765/status' -TimeoutSec 1
        if ($state.simulator) { $status.Text = 'Service running | Simulator' }
        elseif ($state.connected) { $status.Text = 'Service running | ATEM connected' }
        else { $status.Text = 'Service running | ATEM disconnected' }
    } catch {
        $status.Text = 'Booth service unavailable'
    }
    $tray.Text = "LifeSWITCH: $($status.Text)"
}

$timer = New-Object Windows.Forms.Timer
$timer.Interval = 4000
$timer.Add_Tick({ Refresh-BoothStatus })
$menu.Add_Opening({ Refresh-BoothStatus })
try {
    $tray.Visible = $true
    Refresh-BoothStatus
    $timer.Start()
    [Windows.Forms.Application]::Run($context)
} finally {
    $timer.Dispose()
    $tray.Dispose()
    $menu.Dispose()
    $icon.Dispose()
    $context.Dispose()
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}
