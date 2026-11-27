#requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
$target = Join-Path $env:ProgramFiles 'LifeSWITCH'
$service = 'LifeSWITCHBooth'
if (Get-Service -Name $service -ErrorAction SilentlyContinue) {
    Stop-Service -Name $service -Force
    $wrapper = Join-Path $target 'LifeSWITCH-Service.exe'
    & $wrapper uninstall
    if ($LASTEXITCODE -ne 0) { throw 'Could not unregister the Windows service.' }
}
Remove-NetFirewallRule -Name LifeSWITCHBooth8765 -ErrorAction SilentlyContinue
if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
