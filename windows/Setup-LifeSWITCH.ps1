#requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()

function Show-SetupError([string] $message) {
    [void] [Windows.Forms.MessageBox]::Show($message, 'LifeSWITCH Setup', 'OK', 'Error')
}

function Show-Pairing($config) {
    $message = "Open Connection in LifeSWITCH on your Android device and enter these details."
    if ($config.simulator) { $message = "Simulator mode is active. No commands reach the ATEM.`r`n`r`n$message" }
    $dialog = New-Object Windows.Forms.Form
    $dialog.Text = 'LifeSWITCH - Pair your Android device'
    $dialog.StartPosition = 'CenterScreen'
    $dialog.FormBorderStyle = 'FixedDialog'
    $dialog.MaximizeBox = $false
    $dialog.MinimizeBox = $false
    $dialog.ClientSize = New-Object Drawing.Size(460, 184)
    $label = New-Object Windows.Forms.Label
    $label.SetBounds(16, 16, 428, 66)
    $label.Text = $message
    $dialog.Controls.Add($label)
    $details = New-Object Windows.Forms.TextBox
    $details.SetBounds(16, 84, 428, 49)
    $details.Multiline = $true
    $details.ReadOnly = $true
    $details.Text = "Booth address: $($config.bind)`r`nPairing code: $($config.token)"
    $dialog.Controls.Add($details)
    $done = New-Object Windows.Forms.Button
    $done.SetBounds(354, 145, 90, 29)
    $done.Text = 'Finish'
    $done.DialogResult = 'OK'
    $dialog.AcceptButton = $done
    $dialog.Controls.Add($done)
    [void] $dialog.ShowDialog()
    $dialog.Dispose()
}

try {
    $configPath = Join-Path $env:ProgramFiles 'LifeSWITCH\config.json'
    $existing = Get-Service -Name LifeSWITCHBooth -ErrorAction SilentlyContinue
    if ($existing) {
        if (-not (Test-Path -LiteralPath $configPath)) { throw 'The existing LifeSWITCH service is missing its configuration.' }
        if ($existing.Status -ne 'Running') { Start-Service -Name LifeSWITCHBooth }
        Show-Pairing (Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json)
        exit 0
    }

    $profiles = @(Get-NetConnectionProfile | Where-Object { $_.NetworkCategory -in @('Private', 'DomainAuthenticated') })
    $addresses = @(Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.InterfaceIndex -in $profiles.InterfaceIndex -and $_.AddressState -eq 'Preferred' -and
            $_.IPAddress -notmatch '^(127\.|169\.254\.)' } |
        Sort-Object InterfaceMetric, InterfaceAlias, IPAddress)
    if (-not $addresses) { throw 'Connect this PC to the trusted booth network using a Private or Domain network profile, then reopen setup.' }

    $dialog = New-Object Windows.Forms.Form
    $dialog.Text = 'LifeSWITCH Booth Setup'
    $dialog.StartPosition = 'CenterScreen'
    $dialog.FormBorderStyle = 'FixedDialog'
    $dialog.MaximizeBox = $false
    $dialog.MinimizeBox = $false
    $dialog.ClientSize = New-Object Drawing.Size(460, 250)

    $heading = New-Object Windows.Forms.Label
    $heading.SetBounds(16, 16, 428, 33)
    $heading.Text = 'Install the LifeSWITCH booth service for your Android remote.'
    $dialog.Controls.Add($heading)
    $addressLabel = New-Object Windows.Forms.Label
    $addressLabel.SetBounds(16, 57, 428, 18)
    $addressLabel.Text = 'Booth network address'
    $dialog.Controls.Add($addressLabel)
    $booth = New-Object Windows.Forms.ComboBox
    $booth.SetBounds(16, 77, 428, 28)
    $booth.DropDownStyle = 'DropDownList'
    foreach ($address in $addresses) { [void] $booth.Items.Add("$($address.IPAddress)  -  $($address.InterfaceAlias)") }
    $booth.SelectedIndex = 0
    $dialog.Controls.Add($booth)
    $mode = New-Object Windows.Forms.CheckBox
    $mode.SetBounds(16, 111, 428, 28)
    $mode.Text = 'Use Simulator (no ATEM required)'
    $dialog.Controls.Add($mode)
    $atemLabel = New-Object Windows.Forms.Label
    $atemLabel.SetBounds(16, 148, 400, 18)
    $atemLabel.Text = 'ATEM IPv4 address (shown in ATEM Setup)'
    $dialog.Controls.Add($atemLabel)
    $atem = New-Object Windows.Forms.TextBox
    $atem.SetBounds(16, 169, 428, 24)
    $dialog.Controls.Add($atem)
    $mode.Add_CheckedChanged({ $atem.Enabled = -not $mode.Checked })
    $install = New-Object Windows.Forms.Button
    $install.SetBounds(258, 210, 90, 29)
    $install.Text = 'Install'
    $dialog.AcceptButton = $install
    $dialog.Controls.Add($install)
    $cancel = New-Object Windows.Forms.Button
    $cancel.SetBounds(354, 210, 90, 29)
    $cancel.Text = 'Cancel'
    $cancel.DialogResult = 'Cancel'
    $dialog.CancelButton = $cancel
    $dialog.Controls.Add($cancel)

    $install.Add_Click({
        $atemAddress = $atem.Text.Trim()
        if (-not $mode.Checked -and $atemAddress -notmatch '^\d{1,3}(\.\d{1,3}){3}$') {
            Show-SetupError 'Enter the ATEM IPv4 address, or select Simulator.'
            $atem.Focus()
            return
        }
        $dialog.UseWaitCursor = $true
        $dialog.Enabled = $false
        try {
            $params = @{ BoothAddress = $addresses[$booth.SelectedIndex].IPAddress; Simulator = $mode.Checked }
            if (-not $mode.Checked) { $params.AtemAddress = $atemAddress }
            & (Join-Path $PSScriptRoot 'Install-LifeSWITCH.ps1') @params
            $dialog.DialogResult = 'OK'
        } catch {
            Show-SetupError $_.Exception.Message
        } finally {
            $dialog.Enabled = $true
            $dialog.UseWaitCursor = $false
        }
    })

    $result = $dialog.ShowDialog()
    $dialog.Dispose()
    if ($result -ne 'OK') { exit 2 }
    Show-Pairing (Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json)
    exit 0
} catch {
    Show-SetupError $_.Exception.Message
    exit 1
}
