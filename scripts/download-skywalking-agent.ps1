# Download SkyWalking Java Agent 9.7.0 to docker/skywalking-agent/
# Usage: powershell -ExecutionPolicy Bypass -File scripts/download-skywalking-agent.ps1

$ErrorActionPreference = "Stop"

$Version = "9.7.0"
$Url = "https://dlcdn.apache.org/skywalking/java-agent/$Version/apache-skywalking-java-agent-$Version.tgz"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$DestDir = Join-Path $Root "docker\skywalking-agent"
$JarPath = Join-Path $DestDir "skywalking-agent.jar"
$TmpTgz = Join-Path $env:TEMP "apache-skywalking-java-agent-$Version.tgz"
$TmpExtract = Join-Path $env:TEMP "skywalking-agent-$Version"

New-Item -ItemType Directory -Force -Path $DestDir | Out-Null

Write-Host "Downloading SkyWalking Java Agent $Version ..."
Invoke-WebRequest -Uri $Url -OutFile $TmpTgz -UseBasicParsing

if (Test-Path $TmpExtract) {
    Remove-Item -Recurse -Force $TmpExtract
}
New-Item -ItemType Directory -Force -Path $TmpExtract | Out-Null

Write-Host "Extracting ..."
tar -xzf $TmpTgz -C $TmpExtract

$ExtractedJar = Get-ChildItem -Path $TmpExtract -Recurse -Filter "skywalking-agent.jar" | Select-Object -First 1
if (-not $ExtractedJar) {
    throw "skywalking-agent.jar not found in archive"
}

Copy-Item -LiteralPath $ExtractedJar.FullName -Destination $JarPath -Force

Remove-Item -LiteralPath $TmpTgz -Force -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force $TmpExtract -ErrorAction SilentlyContinue

$sizeMb = [math]::Round((Get-Item $JarPath).Length / 1MB, 2)
Write-Host "[OK] $JarPath ($sizeMb MB)" -ForegroundColor Green
Write-Host "Live trace requires OAP at ecs4c16g:11800 (see docker/skywalking-agent/README.md)"
