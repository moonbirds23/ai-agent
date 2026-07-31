[CmdletBinding()]
param(
    [string]$BaseUrl = $(if ($env:AGENT_BASE_URL) { $env:AGENT_BASE_URL } else { "http://localhost:8231/api" }),
    [string]$JaegerBaseUrl = $(if ($env:JAEGER_BASE_URL) { $env:JAEGER_BASE_URL } else { "http://localhost:16686" })
)

$ErrorActionPreference = "Stop"
$caseId = "TRACE_MCP_PEXELS_FINAL_20260730"
$body = @{
    message = "在 Pexels 找 3 张适合极简科技产品落地页的城市夜景素材，只返回候选，不保存。"
    chatId = "github-trace-b-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
    mode = "auto"
} | ConvertTo-Json -Depth 6

$requestStartedEpochMicros =
    [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 1000
$response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/chat" `
    -Headers @{ "X-Demo-Case-Id" = $caseId } `
    -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 120

$message = [string]$response.data.message
if ($response.code -ne 0 -or -not $message.Contains("Pexels")) {
    throw "MCP Pexels demo did not return a verified Pexels response"
}

. "$PSScriptRoot\TraceAssertions.ps1"
$traceEvidence = Assert-DemoTrace -Scenario "mcp-success" -CaseId $caseId `
    -JaegerBaseUrl $JaegerBaseUrl `
    -StartedAfterEpochMicros $requestStartedEpochMicros

[ordered]@{
    httpStatus = 200
    businessCode = $response.code
    responseType = $response.data.type
    responseLength = $message.Length
    contentPrinted = $false
    demoCaseId = $caseId
    traceEvidence = $traceEvidence
} | ConvertTo-Json -Depth 6
Write-Host "Jaeger and linked MCP Server assertions passed for $caseId"
