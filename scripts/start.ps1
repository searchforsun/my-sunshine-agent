# Start core Sunshine services (llm-gateway -> rag -> orchestrator -> auth -> bff -> gateway)
# 配置来源：Nacos（docs/nacos 同步后启动，见 scripts/sync-nacos.ps1）
# Usage: powershell -ExecutionPolicy Bypass -File scripts/start.ps1

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$SkyAgent = Join-Path $Root "docker\skywalking-agent\skywalking-agent.jar"
$JavaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
$Processes = @()

function Get-SkywalkingOpts([string]$ServiceName) {
    if (Test-Path -LiteralPath $SkyAgent) {
        return @(
            "-javaagent:$SkyAgent",
            "-Dskywalking.agent.service_name=sunshine-$ServiceName",
            "-Dskywalking.collector.backend_service=ecs4c16g:11800"
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
    $errFile = Join-Path $logDir "startup.err.log"

    $args = @()
    $args += Get-SkywalkingOpts $Name
    $args += @("-jar", $jar)

    Write-Host "Starting sunshine-$Name ($jar) [Nacos config] ..."
    if (Test-Path -LiteralPath $SkyAgent) {
        Write-Host "  SkyWalking agent enabled"
    }

    $proc = Start-Process -FilePath $JavaBin -ArgumentList $args `
        -RedirectStandardOutput $logFile -RedirectStandardError $errFile `
        -PassThru -WindowStyle Hidden
    $script:Processes += $proc
    Start-Sleep -Seconds 3
}

foreach ($dir in @("llm-gateway", "rag-service", "finance-service", "tool-manager", "desensitize", "prompt-manager", "orchestrator", "auth-center", "bff", "gateway")) {
    $logDir = Join-Path $Root "$dir\logs"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
}

if (-not (Test-Path -LiteralPath $SkyAgent)) {
    Write-Host "[INFO] SkyWalking agent not found — run: scripts/download-skywalking-agent.ps1" -ForegroundColor Yellow
}

Start-Service "llm-gateway" "llm-gateway" "sunshine-llm-gateway"
Start-Service "rag" "rag-service" "sunshine-rag"
Start-Service "finance" "finance-service" "sunshine-finance"
Start-Service "tool-manager" "tool-manager" "sunshine-tool-manager"
Start-Service "desensitize" "desensitize" "sunshine-desensitize"
Start-Service "prompt" "prompt-manager" "sunshine-prompt"
Start-Service "orchestrator" "orchestrator" "sunshine-orchestrator"
Start-Service "auth" "auth-center" "sunshine-auth"
Start-Service "bff" "bff" "sunshine-bff"
Start-Service "gateway" "gateway" "sunshine-gateway"

Write-Host ""
Write-Host "[OK] Core services started (Nacos config)" -ForegroundColor Green
Write-Host "  LLM Gateway  :8300"
Write-Host "  RAG Service  :8400"
Write-Host "  Finance      :8710"
Write-Host "  Tool Manager :8210"
Write-Host "  Desensitize  :8600"
Write-Host "  Prompt Mgr   :8500"
Write-Host "  Orchestrator :8200"
Write-Host "  Auth Center  :8100"
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
