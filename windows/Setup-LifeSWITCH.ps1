#requires -RunAsAdministrator
param([ValidateSet('Setup', 'Pair', 'Settings')] [string] $Mode = 'Setup')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Booth-Management.ps1')
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()

function Show-SetupError([string] $message) {
    [void] [Windows.Forms.MessageBox]::Show($message, 'LifeSWITCH Setup', 'OK', 'Error')
}

function Show-Pairing($config, [string] $configPath, [string] $target) {
    $node = Join-Path $target 'runtime\node.exe'
    $generator = Join-Path $PSScriptRoot 'booth\pairing-qr.js'
    [string[]] $rows = & $node $generator $configPath | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or -not $rows) { throw 'LifeSWITCH could not generate a pairing QR.' }
    [int] $scale = [Math]::Floor(264 / ($rows.Count + 8))
    $width = ($rows.Count + 8) * $scale
    $image = New-Object Drawing.Bitmap($width, $width)
    $graphics = [Drawing.Graphics]::FromImage($image)
    try {
        $graphics.Clear([Drawing.Color]::White)
        for ($row = 0; $row -lt $rows.Count; $row++) {
            for ($column = 0; $column -lt $rows.Count; $column++) {
                if ($rows[$row][$column] -eq '1') {
                    $graphics.FillRectangle([Drawing.Brushes]::Black, ($column + 4) * $scale,
                        ($row + 4) * $scale, $scale, $scale)
                }
            }
        }
    } finally { $graphics.Dispose() }
    $dialog = New-Object Windows.Forms.Form
    $dialog.Text = 'LifeSWITCH - Pair your Android device'
    $dialog.StartPosition = 'CenterScreen'
    $dialog.FormBorderStyle = 'FixedDialog'
    $dialog.MaximizeBox = $false
    $dialog.MinimizeBox = $false
    $dialog.ClientSize = New-Object Drawing.Size(460, 448)
    $label = New-Object Windows.Forms.Label
    $label.SetBounds(16, 16, 428, 58)
    $label.Text = "On Android, open Connection, choose Scan booth QR, then tap Connect."
    if ($config.simulator) { $label.Text += "`r`nSimulator is active. No commands reach the ATEM." }
    $dialog.Controls.Add($label)
    $picture = New-Object Windows.Forms.PictureBox
    $picture.SetBounds(98, 72, 264, 264)
    $picture.SizeMode = 'CenterImage'
    $picture.Image = $image
    $dialog.Controls.Add($picture)
    $details = New-Object Windows.Forms.TextBox
    $details.SetBounds(16, 348, 428, 49)
    $details.Multiline = $true
    $details.ReadOnly = $true
    $details.Text = "Booth address: $($config.bind)`r`nPairing code: $($config.token)"
    $dialog.Controls.Add($details)
    $done = New-Object Windows.Forms.Button
    $done.SetBounds(354, 407, 90, 29)
    $done.Text = 'Finish'
    $done.DialogResult = 'OK'
    $dialog.AcceptButton = $done
    $dialog.Controls.Add($done)
    try { [void] $dialog.ShowDialog() } finally { $dialog.Dispose(); $image.Dispose() }
}

try {
    $target = Join-Path $env:ProgramFiles 'LifeSWITCH'
    $configPath = Join-Path $target 'config.json'
    $existing = Get-Service -Name LifeSWITCHBooth -ErrorAction SilentlyContinue
    if ($Mode -ne 'Setup' -and -not $existing) { throw 'The LifeSWITCH service is not installed.' }
    $previous = $null
    if ($existing) {
        if (-not (Test-Path -LiteralPath $configPath)) { throw 'The existing LifeSWITCH service is missing its configuration.' }
        $previous = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
        if ($Mode -eq 'Setup') {
            Update-BoothService $target $PSScriptRoot
        }
        if ($Mode -ne 'Settings') {
            Show-Pairing $previous $configPath $target
            exit 0
        }
    }

    if ($Mode -eq 'Settings') {
        $addresses = @([pscustomobject]@{ IPAddress = $previous.bind; InterfaceAlias = 'Installed address' })
    } else {
        $profiles = @(Get-NetConnectionProfile | Where-Object { $_.NetworkCategory -in @('Private', 'DomainAuthenticated') })
        $addresses = @(Get-NetIPAddress -AddressFamily IPv4 |
            Where-Object { $_.InterfaceIndex -in $profiles.InterfaceIndex -and $_.AddressState -eq 'Preferred' -and
                $_.IPAddress -notmatch '^(127\.|169\.254\.)' } |
            Sort-Object InterfaceAlias, IPAddress)
        if (-not $addresses) { throw 'Connect this PC to the trusted booth network using a Private or Domain network profile, then reopen setup.' }
    }

    $dialog = New-Object Windows.Forms.Form
    $dialog.Text = if ($Mode -eq 'Settings') { 'LifeSWITCH Booth Settings' } else { 'LifeSWITCH Booth Setup' }
    $dialog.StartPosition = 'CenterScreen'
    $dialog.FormBorderStyle = 'FixedDialog'
    $dialog.MaximizeBox = $false
    $dialog.MinimizeBox = $false
    $dialog.ClientSize = New-Object Drawing.Size(460, 250)

    $heading = New-Object Windows.Forms.Label
    $heading.SetBounds(16, 16, 428, 33)
    $heading.Text = if ($Mode -eq 'Settings') {
        'Changing the ATEM address or mode restarts the booth service and disarms Android remotes.'
    } else { 'Install the LifeSWITCH booth service for your Android remote.' }
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
    $booth.Enabled = $Mode -ne 'Settings'
    $dialog.Controls.Add($booth)
    $simulatorBox = New-Object Windows.Forms.CheckBox
    $simulatorBox.SetBounds(16, 111, 428, 28)
    $simulatorBox.Text = 'Use Simulator (no ATEM required)'
    $dialog.Controls.Add($simulatorBox)
    $atemLabel = New-Object Windows.Forms.Label
    $atemLabel.SetBounds(16, 148, 400, 18)
    $atemLabel.Text = 'ATEM IPv4 address (shown in ATEM Setup)'
    $dialog.Controls.Add($atemLabel)
    $atem = New-Object Windows.Forms.TextBox
    $atem.SetBounds(16, 169, 428, 24)
    $dialog.Controls.Add($atem)
    if ($previous) {
        $atem.Text = $previous.atem
        $simulatorBox.Checked = $previous.simulator
        $atem.Enabled = -not $simulatorBox.Checked
    }
    $simulatorBox.Add_CheckedChanged({ $atem.Enabled = -not $simulatorBox.Checked })
    $install = New-Object Windows.Forms.Button
    $install.SetBounds(258, 210, 90, 29)
    $install.Text = if ($Mode -eq 'Settings') { 'Save' } else { 'Install' }
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
        if (-not $simulatorBox.Checked -and $atemAddress -notmatch '^\d{1,3}(\.\d{1,3}){3}$') {
            Show-SetupError 'Enter the ATEM IPv4 address, or select Simulator.'
            $atem.Focus()
            return
        }
        $dialog.UseWaitCursor = $true
        $dialog.Enabled = $false
        try {
            $params = @{ BoothAddress = $addresses[$booth.SelectedIndex].IPAddress; Simulator = $simulatorBox.Checked }
            if (-not $simulatorBox.Checked) { $params.AtemAddress = $atemAddress }
            if ($Mode -eq 'Settings') {
                Set-BoothMode $configPath $simulatorBox.Checked $atemAddress
            } else {
                & (Join-Path $PSScriptRoot 'Install-LifeSWITCH.ps1') @params
            }
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
    if ($Mode -eq 'Setup') {
        Show-Pairing (Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json) $configPath $target
    }
    exit 0
} catch {
    Show-SetupError $_.Exception.Message
    exit 1
}
