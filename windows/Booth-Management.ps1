function Confirm-IPv4([string] $text) {
    $address = $null
    if ($text -notmatch '^\d{1,3}(\.\d{1,3}){3}$' -or
        -not [Net.IPAddress]::TryParse($text, [ref]$address) -or
        $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
        $address.ToString() -ne $text -or [Net.IPAddress]::IsLoopback($address) -or
        $address.Equals([Net.IPAddress]::Any)) {
        throw 'Enter a local IPv4 address in the form 192.168.1.20.'
    }
}

function Wait-BoothService {
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        try {
            $result = Invoke-RestMethod -Uri 'http://127.0.0.1:8765/status' -TimeoutSec 2
            if ($null -ne $result.connected) { return }
        } catch { Start-Sleep -Milliseconds 400 }
    }
    throw "The service did not answer. Check $env:ProgramData\LifeSWITCH\Logs."
}

function Update-BoothService([string] $target, [string] $payload) {
    $service = 'LifeSWITCHBooth'
    $server = Join-Path $target 'booth\server.js'
    $newServer = Join-Path $payload 'booth\server.js'
    if ((Get-FileHash -LiteralPath $server).Hash -eq (Get-FileHash -LiteralPath $newServer).Hash) {
        if ((Get-Service $service).Status -ne 'Running') { Start-Service $service }
        Wait-BoothService
        return
    }
    $backup = [IO.File]::ReadAllBytes($server)
    Stop-Service $service
    try {
        Copy-Item -LiteralPath $newServer -Destination $server -Force
        Start-Service $service
        Wait-BoothService
    } catch {
        Stop-Service $service -ErrorAction SilentlyContinue
        [IO.File]::WriteAllBytes($server, $backup)
        Start-Service $service
        throw
    }
}

function Set-BoothMode([string] $configPath, [bool] $simulator, [string] $atem) {
    if (-not $simulator) { Confirm-IPv4 $atem }
    $previous = [IO.File]::ReadAllBytes($configPath)
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    if ($config.simulator -eq $simulator -and ($simulator -or $config.atem -eq $atem)) { return }
    $config.simulator = $simulator
    if (-not $simulator) { $config.atem = $atem }
    $service = 'LifeSWITCHBooth'
    Stop-Service $service
    try {
        $config | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
        Start-Service $service
        Wait-BoothService
    } catch {
        Stop-Service $service -ErrorAction SilentlyContinue
        [IO.File]::WriteAllBytes($configPath, $previous)
        Start-Service $service
        throw
    }
}
