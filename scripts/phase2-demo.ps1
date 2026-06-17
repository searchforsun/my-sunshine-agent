# Phase 2 标杆打通验收（2.1 认证 + 2.2/2.3 财务工具 + 2.5 脱敏）
# Usage: powershell -ExecutionPolicy Bypass -File scripts/phase2-demo.ps1

$ErrorActionPreference = "Stop"

Write-Host "=== Phase 2 Demo ===" -ForegroundColor Cyan

Write-Host "`n[2.1] Auth demo"
powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "phase2-auth-demo.ps1")

Write-Host "`n[2.3] Finance messages"
$fin = Invoke-RestMethod "http://localhost:8710/api/finance/messages?status=pending"
if ($fin.code -ne 200 -or $fin.data.Count -lt 1) { throw "finance messages failed" }
Write-Host "  OK pending=$($fin.data.Count)"

Write-Host "`n[2.2] Tool Manager invoke"
$toolBody = @{ name = "list_finance_messages"; params = @{ status = "pending" } } | ConvertTo-Json -Compress
$tool = Invoke-RestMethod -Method POST -Uri "http://localhost:8210/api/tools/invoke" -ContentType "application/json" -Body $toolBody
if ($tool.code -ne 200 -or -not $tool.data) { throw "tool invoke failed" }
Write-Host "  OK tool result length=$($tool.data.Length)"

Write-Host "`n[2.5] Desensitize scrub"
$scrubBody = '{"text":"手机13812345678 身份证110101199001011234"}'
$scrub = Invoke-RestMethod -Method POST -Uri "http://localhost:8600/api/desensitize/scrub" -ContentType "application/json" -Body $scrubBody
if ($scrub.data.text -notmatch '\*\*\*\*') { throw "desensitize failed: $($scrub.data.text)" }
Write-Host "  OK $($scrub.data.text)"

Write-Host "`n[2.7] Audit API"
$audit = Invoke-RestMethod "http://localhost:8200/api/audit/recent"
if ($audit.code -ne 200) { throw "audit api failed" }
Write-Host "  OK recent count=$($audit.data.Count)"

Write-Host "`n[2.7] Elasticsearch sunshine-audit"
try {
    $es = Invoke-RestMethod -Uri "http://ecs4c16g:9200/sunshine-audit/_search?size=1&sort=createdAt:desc" -Method GET
    $esTotal = $es.hits.total.value
    if ($null -eq $esTotal) { $esTotal = $es.hits.total }
    Write-Host "  OK es index total=$esTotal"
} catch {
    if ($_.Exception.Message -match 'index_not_found') {
        Write-Host "  OK es index pending (no audit events yet)"
    } else {
        throw
    }
}

if ($env:PHASE2_SKIP_AGENT -eq "1") {
    Write-Host "`n[2.4] Agent E2E skipped (PHASE2_SKIP_AGENT=1)" -ForegroundColor Yellow
} else {
    Write-Host "`n[2.4] Agent E2E (Gateway SSE -> ReActAgent -> finance tool)"
    powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "phase2-agent-demo.ps1")
}

Write-Host "`n=== Phase 2 Demo PASS ===" -ForegroundColor Green
Write-Host "  2.6 模型降级请运行: mvn test -pl llm-gateway -Dtest=ModelRouterTest" -ForegroundColor DarkGray
