# 运行隔离链 POC，生成证书副本与完整日志；不会连接 OA 的 Kafka/MySQL。
param([string]$SdkCertDir = (Join-Path $PSScriptRoot 'runtime\nodes\127.0.0.1\sdk'))
$ErrorActionPreference = 'Stop'
$pocRoot = $PSScriptRoot
$jdk = 'C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot'
$sdkCerts = $SdkCertDir
$conf = Join-Path $pocRoot 'conf'
$logs = Join-Path $pocRoot 'logs'
if (-not (Test-Path -LiteralPath (Join-Path $sdkCerts 'sdk.key'))) {
    throw "未找到实验链 SDK 证书：$sdkCerts；请先生成并启动链。"
}
New-Item -ItemType Directory -Path $conf, $logs -Force | Out-Null
foreach ($name in @('ca.crt', 'sdk.crt', 'sdk.key')) {
    Copy-Item -LiteralPath (Join-Path $sdkCerts $name) -Destination (Join-Path $conf $name) -Force
}
Copy-Item -LiteralPath (Join-Path $pocRoot 'config.toml.example') -Destination (Join-Path $pocRoot 'config.toml') -Force
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
$log = Join-Path $logs ("poc-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
Push-Location $pocRoot
try {
    "STARTED_AT=$(Get-Date -Format o)" | Tee-Object -FilePath $log
    (& mvn.cmd -version 2>&1) | Tee-Object -FilePath $log -Append
    $config = Join-Path $pocRoot 'config.toml'
    (& mvn.cmd -B compile exec:java "-Dexec.args=$config" 2>&1) | Tee-Object -FilePath $log -Append
    if ($LASTEXITCODE -ne 0) { throw "Maven POC 失败（exit=$LASTEXITCODE），完整日志：$log" }
    Write-Output "LOG=$log"
} finally {
    Pop-Location
}
