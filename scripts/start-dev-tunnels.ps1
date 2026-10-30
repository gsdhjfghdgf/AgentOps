param(
    [string]$VmHost = '127.0.0.1',
    [int]$SshPort = 2222,
    [string]$VmUser = 'zww',
    [string]$KeyPath = 'D:\VM\scripts\vm_key',
    [string]$RedisContainer = 'agent-redis',
    [int]$PostgresLocalPort = 5432,
    [int]$RedisLocalPort = 16380
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $KeyPath -PathType Leaf)) { throw "SSH key not found: $KeyPath" }
if ($RedisContainer -notmatch '^[a-zA-Z0-9_.-]+$') { throw 'Invalid container name' }
if ($PostgresLocalPort -notin 1..65535 -or $RedisLocalPort -notin 1..65535) { throw 'Invalid local port' }
$ssh = (Get-Command ssh -ErrorAction Stop).Source
$common = @('-i', $KeyPath, '-p', [string]$SshPort, '-o', 'BatchMode=yes',
    '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=8')
$target = "$VmUser@$VmHost"
$root = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $root 'agent-server\.runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null

function Test-LocalPort([int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.ConnectAsync('127.0.0.1', $Port)
        return $connect.Wait(800) -and $client.Connected
    } catch { return $false }
    finally { $client.Dispose() }
}

# Discover the current container address; never read or print its credentials.
$inspect = 'docker inspect -f "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}" ' + $RedisContainer
$redisAddress = ((& $ssh @common $target $inspect) -join '').Trim()
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect Redis container through SSH' }
$parsedAddress = $null
if (-not [System.Net.IPAddress]::TryParse($redisAddress, [ref]$parsedAddress)) {
    throw 'Redis must have exactly one reachable container address'
}
$ping = (& $ssh @common $target ("docker exec $RedisContainer redis-cli -p 6379 PING") 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $ping -notmatch 'PONG|NOAUTH') {
    throw 'Redis is not listening on container port 6379'
}

function Start-Forward([int]$LocalPort, [string]$RemoteAddress, [int]$RemotePort, [string]$Name) {
    if (Test-LocalPort $LocalPort) {
        Write-Host "$Name local port $LocalPort is already listening; existing tunnel kept."
        return
    }
    $errorLog = Join-Path $runtime "$Name-tunnel.stderr.log"
    $forward = "127.0.0.1:${LocalPort}:${RemoteAddress}:${RemotePort}"
    $backgroundArgs = @('-i', ('"' + $KeyPath + '"'), '-p', [string]$SshPort, '-N',
        '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ExitOnForwardFailure=yes',
        '-o', 'ServerAliveInterval=30', '-o', 'ServerAliveCountMax=6', '-L', $forward, $target)
    $process = Start-Process -FilePath $ssh -ArgumentList $backgroundArgs -WindowStyle Hidden -PassThru -RedirectStandardError $errorLog
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        if (Test-LocalPort $LocalPort) {
            Write-Host "$Name tunnel ready on 127.0.0.1:$LocalPort (PID $($process.Id))."
            return
        }
        if ($process.HasExited) { throw "$Name tunnel failed; see $errorLog" }
        Start-Sleep -Milliseconds 200
    }
    throw "$Name tunnel did not become ready; see $errorLog"
}

Start-Forward $PostgresLocalPort '127.0.0.1' 5432 'postgres'
Start-Forward $RedisLocalPort $redisAddress 6379 'redis'
# This environment change remains in the calling PowerShell process.
$env:SPRING_DATA_REDIS_PORT = [string]$RedisLocalPort
Write-Host "SPRING_DATA_REDIS_PORT=$RedisLocalPort is set for this PowerShell process."
Write-Host 'Start the backend here, then run: node scripts/verify-live.mjs'
