param([string]$Path = (Join-Path $PSScriptRoot '..\.env'))
$resolved = [System.IO.Path]::GetFullPath($Path)
if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
    throw '请复制 .env.example 为 .env 并填写自己的服务配置。'
}
foreach ($line in Get-Content -LiteralPath $resolved -Encoding UTF8) {
    $value = $line.Trim()
    if (-not $value -or $value.StartsWith('#')) { continue }
    if ($value -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { throw '环境配置格式应为 NAME=value' }
    [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
}
Write-Output '环境变量已加载到当前终端。'
