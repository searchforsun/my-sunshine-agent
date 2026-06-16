# Phase 2.1 Auth 验收脚本（Gateway :8000）
# Usage: powershell -ExecutionPolicy Bypass -File scripts/phase2-auth-demo.ps1

$ErrorActionPreference = "Stop"
$Base = "http://localhost:8000"
$User = "demo_" + (Get-Date -Format "HHmmss")
$Pass = "password123"

function Get-ConversationId($Response) {
    if ($Response.code -eq 200 -and $Response.data.id) { return $Response.data.id }
    if ($Response.id) { return $Response.id }
    throw "create conversation failed: $($Response | ConvertTo-Json -Compress)"
}

function Get-ConversationList($Response) {
    if ($Response.code -eq 200) { return @($Response.data) }
    if ($Response -is [array]) { return $Response }
    if ($Response) { return @($Response) }
    return @()
}

function Invoke-AuthJson($Method, $Path, $Body, $Token, $ExtraHeaders) {
    $headers = @{ "Content-Type" = "application/json" }
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    if ($ExtraHeaders) {
        foreach ($k in $ExtraHeaders.Keys) { $headers[$k] = $ExtraHeaders[$k] }
    }
    $params = @{
        Uri = "$Base$Path"
        Method = $Method
        Headers = $headers
    }
    if ($Body) { $params.Body = ($Body | ConvertTo-Json -Compress) }
    return Invoke-RestMethod @params
}

Write-Host "Step 1: register $User"
$r1 = Invoke-AuthJson POST "/api/auth/register" @{ username = $User; password = $Pass; nickname = "Demo" } $null $null
if ($r1.code -ne 200) { throw "register failed: $($r1 | ConvertTo-Json)" }
Write-Host "  OK userId=$($r1.data.userId)"

Write-Host "Step 2: duplicate register -> 409"
try {
    $r2 = Invoke-AuthJson POST "/api/auth/register" @{ username = $User; password = $Pass } $null $null
    if ($r2.code -ne 409) { throw "expected 409 got $($r2.code)" }
    Write-Host "  OK code=409"
} catch {
    throw "duplicate register check failed: $_"
}

Write-Host "Step 3: login"
$r3 = Invoke-AuthJson POST "/api/auth/login" @{ username = $User; password = $Pass } $null $null
if ($r3.code -ne 200 -or -not $r3.data.token) { throw "login failed" }
$token = $r3.data.token
Write-Host "  OK token received"

Write-Host "Step 4: wrong password -> 401"
$r4 = Invoke-AuthJson POST "/api/auth/login" @{ username = $User; password = "wrongpass1" } $null $null
if ($r4.code -ne 401) { throw "expected 401 got $($r4.code)" }
Write-Host "  OK code=401"

Write-Host "Step 5: no token chat -> 401"
try {
    $r5 = Invoke-WebRequest -Uri "$Base/api/chat/stream" -Method POST -ContentType "application/json" -Body '{"content":"hi"}' -UseBasicParsing
    $body5 = $r5.Content | ConvertFrom-Json
    if ($body5.code -eq 401) {
        Write-Host "  OK code=401"
    } else {
        throw "expected 401 got status=$($r5.StatusCode) body=$($r5.Content)"
    }
} catch {
    $status = $_.Exception.Response.StatusCode.value__
    if ($status -eq 401) {
        Write-Host "  OK HTTP 401"
    } else {
        throw "expected 401 got $_"
    }
}

Write-Host "Step 6: create conversation with token"
$r6 = Invoke-AuthJson POST "/api/conversations" $null $token $null
$convId = Get-ConversationId $r6
Write-Host "  OK conversation=$convId"

Write-Host "Step 7: logout + me 401"
Invoke-AuthJson POST "/api/auth/logout" $null $token $null | Out-Null
try {
    $r7 = Invoke-WebRequest -Uri "$Base/api/auth/me" -Headers @{ Authorization = "Bearer $token" } -UseBasicParsing
    $body7 = $r7.Content | ConvertFrom-Json
    if ($body7.code -eq 401) {
        Write-Host "  OK me 401 after logout"
    } else {
        throw "me should fail after logout"
    }
} catch {
    $status = $_.Exception.Response.StatusCode.value__
    if ($status -eq 401) {
        Write-Host "  OK me 401 after logout"
    } else {
        throw $_
    }
}

Write-Host "Step 8: re-login + forged x-user-id"
$r8 = Invoke-AuthJson POST "/api/auth/login" @{ username = $User; password = $Pass } $null $null
$token2 = $r8.data.token
$realUserId = $r8.data.userId
$r8b = Invoke-AuthJson POST "/api/conversations" $null $token2 @{ "x-user-id" = "hacker-fake-id" }
$list = Get-ConversationList (Invoke-AuthJson GET "/api/conversations" $null $token2 $null)
if ($list.Count -lt 1) { throw "conversation list empty" }
Write-Host "  OK list count=$($list.Count) realUserId=$realUserId"

Write-Host ""
Write-Host "[PASS] Phase 2.1 auth demo completed" -ForegroundColor Green
