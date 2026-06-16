# Phase 1 acceptance demo (Windows PowerShell)
# Usage: powershell -ExecutionPolicy Bypass -File scripts/phase1-demo.ps1

$ErrorActionPreference = "Continue"

$LLM_URL = if ($env:LLM_GATEWAY_URL) { $env:LLM_GATEWAY_URL } else { "http://localhost:8300" }
$BFF_URL = if ($env:BFF_URL) { $env:BFF_URL } else { "http://localhost:8001" }
$RAG_URL = if ($env:RAG_URL) { $env:RAG_URL } else { "http://localhost:8400" }
$GATEWAY = if ($env:GATEWAY_URL) { $env:GATEWAY_URL } else { "http://localhost:8000" }
$USER_ID = if ($env:DEMO_USER_ID) { $env:DEMO_USER_ID } else { "phase1-demo" }
$ROOT = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$DOC_DIR = Join-Path $ROOT "docs\knowledge"
$Utf8NoBom = New-Object System.Text.UTF8Encoding $false

function Write-Step([string]$Title) {
    Write-Host ""
    Write-Host "======== $Title ========" -ForegroundColor Cyan
}

function Write-TempJson([string]$Json) {
    $path = Join-Path $env:TEMP ("sunshine-demo-" + [guid]::NewGuid().ToString("N") + ".json")
    [System.IO.File]::WriteAllText($path, $Json, $Utf8NoBom)
    return $path
}

function Test-Port([string]$Name, [int]$Port) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($conn) {
        Write-Host "[OK] $Name port $Port listening (PID $($conn.OwningProcess))" -ForegroundColor Green
        return $true
    }
    Write-Host "[SKIP] $Name port $Port not listening" -ForegroundColor Yellow
    return $false
}

function Invoke-CurlSseSample([string]$Uri, [string]$JsonBody, [int]$Seconds, [int]$Lines) {
    if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
        Write-Host "[SKIP] curl.exe not found" -ForegroundColor Yellow
        return
    }
    $tmp = Write-TempJson $JsonBody
    try {
        curl.exe -N -s -m $Seconds -X POST $Uri `
            -H "Content-Type: application/json" `
            -H "x-user-id: $USER_ID" `
            -H "x-tenant-id: default" `
            --data-binary "@$tmp" 2>&1 | Select-Object -First $Lines | ForEach-Object { Write-Host $_ }
    } finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

Write-Step "0. Service ports"
Test-Port "LLM Gateway" 8300 | Out-Null
Test-Port "Orchestrator" 8200 | Out-Null
Test-Port "BFF" 8001 | Out-Null
Test-Port "RAG" 8400 | Out-Null

Write-Step "1. LLM Gateway DeepSeek"
$llmBody = '{"model":"deepseek-v4-pro","messages":[{"role":"user","content":"introduce yourself in one sentence"}]}'
try {
    $llmResp = Invoke-RestMethod -Uri ($LLM_URL + "/v1/chat/completions") -Method Post -ContentType "application/json" -Body $llmBody
    $text = [string]$llmResp.choices[0].message.content
    if ($text.Length -gt 120) { $text = $text.Substring(0, 120) + "..." }
    Write-Host "[OK] DeepSeek: $text" -ForegroundColor Green
} catch {
    Write-Host "[FAIL] LLM Gateway: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Step "2. RAG ingest and search"
$docFile = Get-ChildItem -LiteralPath $DOC_DIR -Filter "*.md" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $docFile) {
    Write-Host "[FAIL] No sample markdown under $DOC_DIR" -ForegroundColor Red
} elseif (Get-Command python -ErrorAction SilentlyContinue) {
    $py = @"
import json, pathlib, urllib.request
doc = next(pathlib.Path(r'$DOC_DIR').glob('*.md'))
content = doc.read_text(encoding='utf-8')
payload = json.dumps({'docName': doc.stem, 'content': content}, ensure_ascii=False).encode('utf-8')
req = urllib.request.Request('$RAG_URL/api/rag/documents', data=payload, headers={'Content-Type': 'application/json; charset=utf-8'})
print(urllib.request.urlopen(req, timeout=120).read().decode('utf-8'))
"@
    try {
        $ingestOut = $py | python -
        Write-Host "[OK] Ingest: $ingestOut" -ForegroundColor Green
        $searchCmd = "import json,urllib.request; p=json.dumps({{'query':'\u75c5\u5047\u9700\u8981\u54ea\u4e9b\u6750\u6599','topK':3}}).encode('utf-8'); r=urllib.request.Request('{0}/api/rag/search', data=p, headers={{'Content-Type':'application/json; charset=utf-8'}}); print(urllib.request.urlopen(r, timeout=30).read().decode('utf-8'))" -f $RAG_URL
        $searchOut = python -c $searchCmd
        if ($searchOut) {
            $preview = if ($searchOut.Length -gt 200) { $searchOut.Substring(0, 200) + "..." } else { $searchOut }
            Write-Host "[OK] Search: $preview" -ForegroundColor Green
        } else {
            Write-Host "[FAIL] RAG search returned empty" -ForegroundColor Red
        }
    } catch {
        Write-Host "[FAIL] RAG: $($_.Exception.Message)" -ForegroundColor Red
    }
} else {
    Write-Host "[WARN] python not found; using minimal ingest sample" -ForegroundColor Yellow
    $small = Write-TempJson '{"docName":"demo","content":"# Demo\n\nSick leave requires medical certificate."}'
    try {
        $ingest = Invoke-RestMethod -Uri ($RAG_URL + "/api/rag/documents") -Method Post -ContentType "application/json" -InFile $small
        Write-Host "[OK] Ingest: $($ingest.msg) chunks=$($ingest.chunks)" -ForegroundColor Green
    } catch {
        Write-Host "[FAIL] RAG: $($_.Exception.Message)" -ForegroundColor Red
    } finally {
        Remove-Item -LiteralPath $small -Force -ErrorAction SilentlyContinue
    }
}

Write-Step "3. BFF SSE stream"
$sseUri = $BFF_URL + "/api/chat/stream"
Invoke-CurlSseSample -Uri $sseUri -JsonBody '{"content":"hello, introduce Sunshine AI in one sentence"}' -Seconds 8 -Lines 10
Write-Host "[OK] SSE sample done" -ForegroundColor Green

Write-Step "4. Knowledge stream via BFF"
$knowledgeCmd = "import json,pathlib,tempfile; p=pathlib.Path(tempfile.gettempdir())/'sunshine-knowledge-chat.json'; p.write_text(json.dumps({{'content':'\u516c\u53f8\u75c5\u5047\u9700\u8981\u63d0\u4ea4\u4ec0\u4e48\u6750\u6599\uff1f'}}, ensure_ascii=False), encoding='utf-8'); print(p)"
$knowledgeFile = (python -c $knowledgeCmd).Trim()
try {
    if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
        curl.exe -N -s -m 20 -X POST $sseUri `
            -H "Content-Type: application/json" `
            -H "x-user-id: $USER_ID" `
            -H "x-tenant-id: default" `
            --data-binary "@$knowledgeFile" 2>&1 | Select-Object -First 15 | ForEach-Object { Write-Host $_ }
    }
} finally {
    Remove-Item -LiteralPath $knowledgeFile -Force -ErrorAction SilentlyContinue
}
Write-Host "[OK] Knowledge stream sample done" -ForegroundColor Green

Write-Step "5. Gateway route"
$gwUri = $GATEWAY + "/api/chat/stream"
if (Test-Port "Gateway" 8000) {
    if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
        $gwTmp = Write-TempJson '{"content":"hello via gateway"}'
        try {
            $gwOut = curl.exe -N -s -m 8 -X POST $gwUri `
                -H "Content-Type: application/json" `
                -H "x-user-id: $USER_ID" `
                -H "x-tenant-id: default" `
                --data-binary "@$gwTmp" 2>&1 | Select-Object -First 8
            if ($gwOut) {
                Write-Host "[OK] Gateway SSE sample" -ForegroundColor Green
                $gwOut | ForEach-Object { Write-Host $_ }
            } else {
                Write-Host "[FAIL] Gateway returned empty SSE" -ForegroundColor Red
            }
        } catch {
            Write-Host "[FAIL] Gateway: $($_.Exception.Message)" -ForegroundColor Red
        } finally {
            Remove-Item -LiteralPath $gwTmp -Force -ErrorAction SilentlyContinue
        }
    } else {
        Write-Host "[SKIP] curl.exe not found" -ForegroundColor Yellow
    }
}

Write-Step "6. Nacos prompt hot-reload manual"
Write-Host "(MANUAL) Edit agent.system-prompt in Nacos sunshine-orchestrator.yaml" -ForegroundColor Yellow
Write-Host "  http://ecs4c16g:8848/nacos  nacos/nacos" -ForegroundColor Yellow

Write-Step "7. Cross-browser history manual"
Write-Host "(MANUAL) Same x-user-id=$USER_ID in two browsers on /chat" -ForegroundColor Yellow

Write-Host ""
Write-Host "Done. Update docs/implementation-plan.md check gates when verified." -ForegroundColor Green
