# Start core Sunshine services (llm-gateway -> rag -> orchestrator -> bff -> gateway)
# Default: Nacos 配置（无 --spring.profiles.active=dev，需 ecs4c16g Nacos 可达且配置已上传）
# 本地直连: -Profile dev
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/start.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/start.ps1 -Profile dev

param(
    [string]$Profile = ""
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$SkyAgent = Join-Path $Root "docker\skywalking-agent\skywalking-agent.jar"
$JavaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
$Processes = @()

function Get-SkywalkingOpts([string]$ServiceName) {
    if (Test-Path -LiteralPath $SkyAgent) {
        return @(
            "-javaagent:$SkyAgent",
            "-DSW_AGENT_NAME=sunshine-$ServiceName",
            "-DSW_AGENT_COLLECTOR_BACKEND_SERVICES=ecs4c16g:11800"
        )
    }
    return @()
}

function Find-Jar([string]$Module, [string]$Artifact) {
    $pattern = Join-Path $Root "$Module\target\$Artifact-*.jar"
    $jar = Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*.original.jar" } |
        Select-Object -First 1
    if (-not $jar) {
        throw "JAR not found: $Module/target/${Artifact}-*.jar — run: mvn package -DskipTests"
    }
    return $jar.FullName
}

function Start-Service([string]$Name, [string]$Module, [string]$Artifact) {
    $jar = Find-Jar $Module $Artifact
    $logDir = Join-Path $Root "$Module\logs"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $logFile = Join-Path $logDir "startup.log"

    $args = @()
    $args += Get-SkywalkingOpts $Name
    $args += @("-jar", $jar)
    if ($Profile) {
        $args += "--spring.profiles.active=$Profile"
    }

    Write-Host "Starting sunshine-$Name ($jar) profile=$(if ($Profile) { $Profile } else { 'nacos' }) ..."
    if (Test-Path -LiteralPath $SkyAgent) {
        Write-Host "  SkyWalking agent enabled"
    }

    $proc = Start-Process -FilePath $JavaBin -ArgumentList $args `
        -RedirectStandardOutput $logFile -RedirectStandardError $logFile `
        -PassThru -WindowStyle Hidden
    $script:Processes += $proc
    Start-Sleep -Seconds 3
}

foreach ($dir in @("llm-gateway", "rag-service", "orchestrator", "bff", "gateway")) {
    $logDir = Join-Path $Root "$dir\logs"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
}

if (-not (Test-Path -LiteralPath $SkyAgent)) {
    Write-Host "[INFO] SkyWalking agent not found — run: scripts/download-skywalking-agent.ps1" -ForegroundColor Yellow
}

Start-Service "llm-gateway" "llm-gateway" "sunshine-llm-gateway"
Start-Service "rag" "rag-service" "sunshine-rag"
Start-Service "orchestrator" "orchestrator" "sunshine-orchestrator"
Start-Service "bff" "bff" "sunshine-bff"
Start-Service "gateway" "gateway" "sunshine-gateway"

Write-Host ""
Write-Host "[OK] Core services started (mode: $(if ($Profile) { 'dev profile' } else { 'Nacos config' }))" -ForegroundColor Green
Write-Host "  LLM Gateway  :8300"
Write-Host "  RAG Service  :8400"
Write-Host "  Orchestrator :8200"
Write-Host "  BFF          :8001"
Write-Host "  Gateway      :8000"
Write-Host "Live SkyWalking trace requires OAP at ecs4c16g:11800"
Write-Host "Press Ctrl+C or close window — child PIDs: $($Processes.Id -join ', ')"

try {
    Wait-Process -Id ($Processes.Id)
} finally {
    foreach ($p in $Processes) {
        if (-not $p.HasExited) {
            Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
        }
    }
}
