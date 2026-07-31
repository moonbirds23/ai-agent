[CmdletBinding()]
param(
    [string]$BaseUrl = $(if ($env:AGENT_BASE_URL) { $env:AGENT_BASE_URL } else { "http://localhost:8231/api" }),
    [string]$JaegerBaseUrl = $(if ($env:JAEGER_BASE_URL) { $env:JAEGER_BASE_URL } else { "http://localhost:16686" })
)

$ErrorActionPreference = "Stop"
$caseId = "TRACE_RAG_HIT_SNOW_FINAL_20260730"
$body = @{
    message = "参考图库中的雪景照片，帮我生成一张新的雪景照片。保留安静、清透的冬日氛围与自然摄影质感，画面包含覆雪树林、开阔雪地和柔和晨光，不要保存到图库。"
    chatId = "github-trace-a-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
    mode = "image_generation"
    useGalleryRag = $true
    referenceMode = "style"
    saveGeneratedToGallery = $false
} | ConvertTo-Json -Depth 6

$requestStartedEpochMicros =
    [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 1000
$response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/chat" `
    -Headers @{ "X-Demo-Case-Id" = $caseId } `
    -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 180

if ($response.code -ne 0 -or $response.data.type -ne "image_generated" -or
    [string]::IsNullOrWhiteSpace([string]$response.data.imageUrl) -or
    $null -ne $response.data.pictureId) {
    throw "RAG image demo did not return a generated image"
}

. "$PSScriptRoot\TraceAssertions.ps1"
$traceEvidence = Assert-DemoTrace -Scenario "rag-hit" -CaseId $caseId `
    -JaegerBaseUrl $JaegerBaseUrl `
    -StartedAfterEpochMicros $requestStartedEpochMicros

[ordered]@{
    httpStatus = 200
    businessCode = $response.code
    responseType = $response.data.type
    imageReturned = $true
    galleryPictureId = $response.data.pictureId
    demoCaseId = $caseId
    traceEvidence = $traceEvidence
} | ConvertTo-Json -Depth 6
Write-Host "Jaeger assertions passed for agent.demo.case_id=$caseId"
