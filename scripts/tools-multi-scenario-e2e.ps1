# Multi-scenario orchestrator E2E (requires :8200 :8210 :8300 :8710 :8700)
$ErrorActionPreference = "Stop"
$Base = "http://localhost:8200"
$User = "tools_e2e_" + (Get-Date -Format "HHmmss")
$Utf8 = New-Object System.Text.UTF8Encoding $false
$headers = @{ "Content-Type" = "application/json"; "x-user-id" = $User; "x-tenant-id" = "default" }
$results = New-Object System.Collections.Generic.List[object]

function Invoke-Orch($Method, $Path, $Body) {
    $p = @{ Uri = "$Base$Path"; Method = $Method; Headers = $headers }
    if ($Body) { $p.Body = ($Body | ConvertTo-Json -Compress) }
    return Invoke-RestMethod @p
}

function Invoke-Chat($ConvId, $Content) {
    $tmp = Join-Path $env:TEMP ("e2e-" + [guid]::NewGuid().ToString("N") + ".json")
    [IO.File]::WriteAllText($tmp, (@{ content = $Content; conversationId = $ConvId } | ConvertTo-Json -Compress), $Utf8)
    try {
        return & curl.exe -N -s -m 180 -X POST "$Base/chat/stream" -H "x-user-id: $User" -H "x-tenant-id: default" -H "Content-Type: application/json" --data-binary "@$tmp" 2>&1 | Out-String
    } finally { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
}

function Wait-Assistant($Cid, $MaxSec) {
    $deadline = (Get-Date).AddSeconds($MaxSec)
    do {
        $a = @((Invoke-Orch GET "/conversations/$Cid" $null).messages | Where-Object role -eq assistant | Select-Object -Last 1)
        if ($a -and $a.status -eq "completed") { return $a }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    return $null
}

function Parse-Steps($Raw) {
    $steps = New-Object System.Collections.Generic.List[string]
    foreach ($line in ($Raw -split "`n")) {
        if (-not $line.StartsWith("data:")) { continue }
        try {
            $o = ($line.Substring(5).Trim()) | ConvertFrom-Json
            if ($o.type -eq "step" -and $o.id) { $steps.Add([string]$o.id) }
        } catch {}
    }
    return $steps
}

function Run-Case($Name, $Query, $ExpectIntent, $ExpectPattern) {
    Write-Host "`n=== $Name ===" -ForegroundColor Cyan
    $conv = Invoke-Orch POST "/conversations" @{}
    $raw = Invoke-Chat $conv.id $Query
    $assistant = Wait-Assistant $conv.id 120
    if (-not $assistant) {
        $results.Add([pscustomobject]@{ Case = $Name; Result = "FAIL"; Reason = "timeout" })
        Write-Host "FAIL timeout" -ForegroundColor Red
        return
    }
    $stepIds = Parse-Steps $raw
    $intentOk = if ($ExpectIntent) { $assistant.intent -like $ExpectIntent } else { $true }
    $dataOk = if ($ExpectPattern) { $assistant.content -match $ExpectPattern } else { $assistant.content.Length -gt 10 }
    $toolHit = ($stepIds -match "tool-|rag") -or ($raw -match "list_finance|get_finance|summarize_finance|search_knowledge|list_oa")
    Write-Host "intent=$($assistant.intent) steps=$([string]::Join(',', $stepIds))"
    if ($intentOk -and $dataOk) {
        $results.Add([pscustomobject]@{ Case = $Name; Result = "PASS"; Reason = "intent=$($assistant.intent); tools=$toolHit" })
        Write-Host "PASS" -ForegroundColor Green
    } else {
        $reason = @()
        if (-not $intentOk) { $reason += "intent=$($assistant.intent) expected $ExpectIntent" }
        if (-not $dataOk) { $reason += "content miss $ExpectPattern" }
        $results.Add([pscustomobject]@{ Case = $Name; Result = "FAIL"; Reason = ($reason -join '; ') })
        Write-Host "FAIL $($reason -join '; ')" -ForegroundColor Red
    }
}

Write-Host "User=$User" -ForegroundColor Yellow
Run-Case "simple-llm" "1+1等于几？只回答数字" "simple-llm" '2'
Run-Case "workflow-finance-list" "有哪些待审批报销和付款消息，列出标题" "workflow:finance-list" '1001|1002|报销|付款'
Run-Case "workflow-finance-summary" "pending 财务消息有多少条，总额多少" "workflow:finance-summary" '3|124140|pending'
Run-Case "workflow-knowledge-qa" "公司请假制度是什么" "workflow:knowledge-qa" '.'
Run-Case "react-finance-detail" "请用工具查询财务消息 id=1001 的详情，不要编造" "react" '1001|3280|差旅'
Run-Case "react-multi-tool" "请自主调用工具：先 summarize_finance_by_status 查 pending，再 get_finance_message_detail 查 1002" "react" '1002|860'

Write-Host "`n=== SUMMARY ===" -ForegroundColor Yellow
$results | Format-Table -AutoSize
