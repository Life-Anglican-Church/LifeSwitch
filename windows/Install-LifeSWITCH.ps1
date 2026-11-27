#requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string] $BoothAddress,
    [string] $AtemAddress,
    [switch] $Simulator
)

$ErrorActionPreference = 'Stop'
$service = 'LifeSWITCHBooth'
$target = Join-Path $env:ProgramFiles 'LifeSWITCH'
$logs = Join-Path $env:ProgramData 'LifeSWITCH\Logs'
$rule = 'LifeSWITCHBooth8765'

. (Join-Path $PSScriptRoot 'Booth-Management.ps1')

if (-not [Environment]::Is64BitProcess -or $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') {
    throw 'Use 64-bit Windows PowerShell on an x64 Windows PC.'
}
if ((Test-Path $target) -or (Get-Service -Name $service -ErrorAction SilentlyContinue)) {
    throw "LifeSWITCH is already installed. Run Uninstall-LifeSWITCH.ps1 before reinstalling."
}
if (Get-NetFirewallRule -Name $rule -ErrorAction SilentlyContinue) {
    throw "A LifeSWITCH firewall rule already exists. Run Uninstall-LifeSWITCH.ps1 first."
}

if (-not $BoothAddress) {
    Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' } |
        Format-Table InterfaceAlias,IPAddress,PrefixLength -AutoSize | Out-Host
    $BoothAddress = Read-Host 'Booth PC IPv4 address'
}
Confirm-IPv4 $BoothAddress
$localInterface = Get-NetIPAddress -AddressFamily IPv4 -IPAddress $BoothAddress
if (@($localInterface).Count -ne 1) { throw 'Choose an IPv4 address assigned to one booth network adapter.' }
if (-not $Simulator) {
    if (-not $AtemAddress) { $AtemAddress = Read-Host 'ATEM IPv4 address (shown in ATEM Setup)' }
    Confirm-IPv4 $AtemAddress
}
$profiles = @(Get-NetConnectionProfile -InterfaceIndex $localInterface.InterfaceIndex)
if (-not ($profiles | Where-Object { $_.NetworkCategory -in @('Private', 'DomainAuthenticated') })) {
    throw 'Choose an adapter connected to a trusted Private or Domain Windows network.'
}

$key = New-Object byte[] 16
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($key) } finally { $rng.Dispose() }
$token = [BitConverter]::ToString($key).Replace('-', '').ToLowerInvariant()
$config = @{ bind = $BoothAddress; atem = $AtemAddress; token = $token; simulator = [bool]$Simulator }
$registered = $false
$firewall = $false
try {
    New-Item -ItemType Directory -Path $target, $logs -Force | Out-Null
    foreach ($item in 'LifeSWITCH-Service.exe','LifeSWITCH-Service.xml','runtime','booth') {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $item) -Destination $target -Recurse
    }
    $configuration = Join-Path $target 'config.json'
    $config | ConvertTo-Json | Set-Content -LiteralPath $configuration -Encoding UTF8
    & icacls.exe $configuration /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' '*S-1-5-19:R' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not protect LifeSWITCH configuration.' }
    & icacls.exe $logs /grant '*S-1-5-19:(OI)(CI)M' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not configure LifeSWITCH logging.' }
    $wrapper = Join-Path $target 'LifeSWITCH-Service.exe'
    & $wrapper install
    if ($LASTEXITCODE -ne 0) { throw 'Windows service registration failed.' }
    $registered = $true
    New-NetFirewallRule -Name $rule -DisplayName 'LifeSWITCH booth (local subnet)' `
        -Direction Inbound -Action Allow -Profile Private,Domain -Protocol TCP `
        -LocalAddress $BoothAddress -LocalPort 8765 -RemoteAddress LocalSubnet `
        -Program (Join-Path $target 'runtime\node.exe') | Out-Null
    $firewall = $true
    Start-Service -Name $service
    Wait-BoothService
} catch {
    if ($firewall) { Remove-NetFirewallRule -Name $rule -ErrorAction SilentlyContinue }
    if ($registered) {
        Stop-Service -Name $service -Force -ErrorAction SilentlyContinue
        & $wrapper uninstall
    }
    Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction SilentlyContinue
    throw
}

Write-Host "Booth address: $BoothAddress"
Write-Host "Pairing code:  $token"
if ($Simulator) { Write-Host 'Simulator mode is active. No commands reach the ATEM.' }
