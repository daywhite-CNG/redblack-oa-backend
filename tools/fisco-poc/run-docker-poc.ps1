# 使用 Docker 专用证书调用既有 Java 21 POC，不复制或修改 Evidence v1。
$ErrorActionPreference = 'Stop'
$sdkCertDir = Join-Path $PSScriptRoot 'docker-runtime\nodes\172.29.44.10\sdk'
& (Join-Path $PSScriptRoot 'run-poc.ps1') -SdkCertDir $sdkCertDir
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
