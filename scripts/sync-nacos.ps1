# 将 docs/nacos/*.yaml 同步到线上 Nacos（唯一配置源）
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/sync-nacos.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/sync-nacos.ps1 -DataId sunshine-gateway.yaml

param(
    [string]$NacosServer = "http://ecs4c16g:8848/nacos",
    [string]$Username = "nacos",
    [string]$Password = "nacos",
    [string]$Group = "DEFAULT_GROUP",
    [string]$DataId = ""
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ConfigDir = Join-Path $Root "docs\nacos"
$Uri = "$NacosServer/v1/cs/configs"

$files = if ($DataId) {
    @($DataId)
} else {
    @(
        "sunshine-gateway.yaml",
        "sunshine-auth.yaml",
        "sunshine-bff.yaml",
        "sunshine-orchestrator.yaml",
        "sunshine-llm-gateway.yaml",
        "sunshine-rag.yaml",
        "sunshine-finance.yaml",
        "sunshine-tool-manager.yaml",
        "sunshine-desensitize.yaml",
        "sunshine-prompt.yaml"
    )
}

foreach ($f in $files) {
    $path = Join-Path $ConfigDir $f
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Config file not found: $path"
    }
    $content = Get-Content -LiteralPath $path -Raw -Encoding UTF8
    $body = @{
        username = $Username
        password = $Password
        dataId   = $f
        group    = $Group
        type     = "yaml"
        content  = $content
    }
    $resp = Invoke-RestMethod -Uri $Uri -Method POST -Body $body -TimeoutSec 30
    if ($resp -ne $true -and "$resp" -ne "true") {
        throw "Upload failed for $f : $resp"
    }
    Write-Host "[OK] $f" -ForegroundColor Green
}

Write-Host ""
Write-Host "Synced $($files.Count) config(s) to Nacos ($NacosServer)." -ForegroundColor Cyan
Write-Host "Restart affected services to pick up changes."
