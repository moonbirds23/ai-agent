[CmdletBinding()]
param(
    [string]$BaseUrl = $(if ($env:AGENT_BASE_URL) { $env:AGENT_BASE_URL } else { "http://localhost:8231/api" }),
    [string]$JaegerBaseUrl = $(if ($env:JAEGER_BASE_URL) { $env:JAEGER_BASE_URL } else { "http://localhost:16686" })
)

$ErrorActionPreference = "Stop"
$caseId = "TRACE_MCP_TIMEOUT_FINAL_20260730"
$body = @{
    message = "在 Pexels 找 3 张城市夜景图片，只返回候选，不保存。"
    chatId = "github-trace-c-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
    mode = "auto"
} | ConvertTo-Json -Depth 6

$requestStartedEpochMicros =
    [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 1000
$response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/chat" `
    -Headers @{ "X-Demo-Case-Id" = $caseId } `
    -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 120

$message = [string]$response.data.message
if ($response.code -ne 0 -or $message.Contains("http://") -or $message.Contains("https://")) {
    throw "Timeout demo leaked or fabricated candidate URLs"
}

. "$PSScriptRoot\TraceAssertions.ps1"
$traceEvidence = Assert-DemoTrace -Scenario "mcp-timeout" -CaseId $caseId `
    -JaegerBaseUrl $JaegerBaseUrl `
    -StartedAfterEpochMicros $requestStartedEpochMicros

[ordered]@{
    httpStatus = 200
    businessCode = $response.code
    responseType = $response.data.type
    responseLength = $message.Length
    candidateUrlReturned = $false
    demoCaseId = $caseId
    traceEvidence = $traceEvidence
} | ConvertTo-Json -Depth 6
Write-Host "Prerequisite: run against the documented controlled delayed-Pexels setup."
Write-Host "Jaeger timeout/retry/verifier/memory assertions passed for $caseId"
