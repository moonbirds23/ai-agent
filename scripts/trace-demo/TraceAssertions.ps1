function Get-SpanTag {
    param(
        [Parameter(Mandatory = $true)] $Span,
        [Parameter(Mandatory = $true)] [string] $Key
    )
    $tag = $Span.tags | Where-Object { $_.key -eq $Key } | Select-Object -First 1
    if ($null -eq $tag) { return $null }
    return $tag.value
}

function Get-TraceSpan {
    param(
        [Parameter(Mandatory = $true)] $Trace,
        [Parameter(Mandatory = $true)] [string] $OperationName,
        [string] $TagKey,
        [string] $TagValue
    )
    return $Trace.spans | Where-Object {
        if ($_.operationName -ne $OperationName) { return $false }
        if (-not $TagKey) { return $true }
        return [string](Get-SpanTag $_ $TagKey) -eq $TagValue
    }
}

function Assert-TraceCondition {
    param(
        [Parameter(Mandatory = $true)] [bool] $Condition,
        [Parameter(Mandatory = $true)] [string] $Message
    )
    if (-not $Condition) {
        throw "Jaeger assertion failed: $Message"
    }
}

function Get-DemoTrace {
    param(
        [Parameter(Mandatory = $true)] [string] $CaseId,
        [Parameter(Mandatory = $true)] [string] $JaegerBaseUrl,
        [long] $StartedAfterEpochMicros = 0,
        [int] $TimeoutSeconds = 20
    )
    $tags = [uri]::EscapeDataString(
        (@{ "agent.demo.case_id" = $CaseId } | ConvertTo-Json -Compress))
    $uri = "$JaegerBaseUrl/api/traces?service=aiagent&tags=$tags&limit=5"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $traces = (Invoke-RestMethod -Uri $uri -TimeoutSec 5).data
            if ($traces) {
                $matching = $traces | Where-Object {
                    $traceStart = ($_.spans |
                        Measure-Object -Property startTime -Minimum).Minimum
                    [long]$traceStart -ge $StartedAfterEpochMicros
                }
                if ($matching) {
                    return $matching |
                        Sort-Object {
                            ($_.spans |
                                Measure-Object -Property startTime -Minimum).Minimum
                        } -Descending |
                        Select-Object -First 1
                }
            }
        } catch {
            if ((Get-Date) -ge $deadline) { throw }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "No Jaeger trace found for agent.demo.case_id=$CaseId"
}

function Assert-LinkedMcpServerTrace {
    param(
        [Parameter(Mandatory = $true)] $ClientSpan,
        [Parameter(Mandatory = $true)] [string] $MainTraceId,
        [Parameter(Mandatory = $true)] [string] $JaegerBaseUrl
    )
    $uri = "$JaegerBaseUrl/api/traces?service=image-retrieval-mcp-server&limit=50"
    $serverTraces = (Invoke-RestMethod -Uri $uri -TimeoutSec 10).data
    $linkedTrace = $serverTraces | Where-Object {
        $candidate = $_
        $candidate.spans | Where-Object {
            $_.references | Where-Object {
                $_.traceID -eq $MainTraceId -and
                $_.spanID -eq $ClientSpan.spanID
            }
        }
    } | Select-Object -First 1

    Assert-TraceCondition ($null -ne $linkedTrace) `
        "MCP Server trace must contain a real Span Link to the client span"
    $serverSpan = Get-TraceSpan $linkedTrace "mcp.server.tools/call"
    $pexelsSpan = Get-TraceSpan $linkedTrace "http.client.pexels"
    Assert-TraceCondition ($null -ne $serverSpan) "MCP Server span is missing"
    Assert-TraceCondition ($null -ne $pexelsSpan) "Pexels HTTP child span is missing"
    Assert-TraceCondition (
        [string](Get-SpanTag $serverSpan "agent.mcp.span_link.created") -eq "True"
    ) "agent.mcp.span_link.created must be true"
    Assert-TraceCondition (
        [string](Get-SpanTag $serverSpan "agent.mcp.trace_context_propagated") -eq "False"
    ) "MCP server must disclose trace_context_propagated=false"

    return [ordered]@{
        serverTraceId = $linkedTrace.traceID
        serverDurationMs = [math]::Round(
            (($linkedTrace.spans | Measure-Object duration -Maximum).Maximum / 1000), 1)
        pexelsHttpMs = [math]::Round(($pexelsSpan.duration / 1000), 1)
        spanLinkCreated = $true
    }
}

function Assert-DemoTrace {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet("rag-hit", "mcp-success", "mcp-timeout")]
        [string] $Scenario,
        [Parameter(Mandatory = $true)] [string] $CaseId,
        [long] $StartedAfterEpochMicros = 0,
        [string] $JaegerBaseUrl = "http://localhost:16686"
    )

    $trace = Get-DemoTrace -CaseId $CaseId -JaegerBaseUrl $JaegerBaseUrl `
        -StartedAfterEpochMicros $StartedAfterEpochMicros
    $turn = Get-TraceSpan $trace "agent.turn"
    $verifier = Get-TraceSpan $trace "agent.verifier"
    $memory = Get-TraceSpan $trace "agent.memory.write"
    Assert-TraceCondition ($null -ne $turn) "agent.turn is missing"
    Assert-TraceCondition ($null -ne $verifier) "agent.verifier is missing"
    Assert-TraceCondition ($null -ne $memory) "agent.memory.write is missing"

    $summary = [ordered]@{
        traceId = $trace.traceID
        spanCount = $trace.spans.Count
        totalMs = [math]::Round(
            (($trace.spans | Measure-Object duration -Maximum).Maximum / 1000), 1)
        verifier = [string](Get-SpanTag $verifier "agent.verify.verdict")
        memoryWrite = [string](Get-SpanTag $memory "agent.memory.write")
    }

    if ($Scenario -eq "rag-hit") {
        $rag = Get-TraceSpan $trace "agent.rag"
        $generation = Get-TraceSpan $trace "agent.tool" `
            "agent.tool.name" "generateImage"
        $candidateCount = [int](Get-SpanTag $rag "agent.rag.candidate_count")
        $selectedCount = [int](Get-SpanTag $rag "agent.rag.selected_count")
        $referenceCount = [int](Get-SpanTag $generation `
            "agent.generation.reference.count")
        Assert-TraceCondition ($candidateCount -gt 0) "RAG candidate count must be > 0"
        Assert-TraceCondition ($selectedCount -gt 0) "RAG selected count must be > 0"
        Assert-TraceCondition ($referenceCount -gt 0) `
            "generateImage reference count must be > 0"
        Assert-TraceCondition (
            [string](Get-SpanTag $generation "agent.tool.outcome") -eq "success"
        ) "generateImage must succeed"
        Assert-TraceCondition ($summary.verifier -eq "pass") "Verifier must pass"
        Assert-TraceCondition (
            [string](Get-SpanTag $verifier "agent.verify.constraint.no_save") -eq "pass"
        ) "no-save constraint must pass"
        Assert-TraceCondition ($summary.memoryWrite -eq "True") `
            "Verified result must be written to trusted memory"
        $summary.candidateCount = $candidateCount
        $summary.selectedCount = $selectedCount
        $summary.generationReferenceCount = $referenceCount
        $summary.generationResultCount =
            [int](Get-SpanTag $generation "agent.tool.result_count")
    }

    if ($Scenario -eq "mcp-success") {
        $clientSpan = Get-TraceSpan $trace "agent.mcp.call"
        Assert-TraceCondition ($null -ne $clientSpan) "agent.mcp.call is missing"
        Assert-TraceCondition (
            [int](Get-SpanTag $clientSpan "agent.mcp.result.count") -eq 3
        ) "MCP result count must equal 3"
        Assert-TraceCondition (
            [string](Get-SpanTag $clientSpan "agent.tool.outcome") -eq "success"
        ) "MCP client call must succeed"
        Assert-TraceCondition (
            [string](Get-SpanTag $clientSpan `
                "agent.mcp.trace_context_propagated") -eq "False"
        ) "SSE transport must disclose trace_context_propagated=false"
        Assert-TraceCondition ($summary.verifier -eq "pass") "Verifier must pass"
        Assert-TraceCondition (
            [string](Get-SpanTag $verifier `
                "agent.verify.constraint.result_count") -eq "pass"
        ) "result-count constraint must pass"
        Assert-TraceCondition (
            [string](Get-SpanTag $verifier "agent.verify.constraint.no_save") -eq "pass"
        ) "no-save constraint must pass"
        Assert-TraceCondition ($summary.memoryWrite -eq "True") `
            "Verified result must be written to trusted memory"
        $summary.mcpResultCount = 3
        $summary.mcpClientMs = [math]::Round(($clientSpan.duration / 1000), 1)
        $summary.linkedServer = Assert-LinkedMcpServerTrace `
            -ClientSpan $clientSpan -MainTraceId $trace.traceID `
            -JaegerBaseUrl $JaegerBaseUrl
    }

    if ($Scenario -eq "mcp-timeout") {
        $attempts = @(Get-TraceSpan $trace "agent.mcp.call")
        Assert-TraceCondition ($attempts.Count -eq 2) `
            "Timeout scenario must contain exactly 2 MCP attempts"
        $attemptNumbers = @($attempts | ForEach-Object {
            [int](Get-SpanTag $_ "agent.tool.attempt")
        } | Sort-Object)
        Assert-TraceCondition (
            $attemptNumbers[0] -eq 1 -and $attemptNumbers[1] -eq 2
        ) "MCP attempts must be 1 and 2"
        foreach ($attempt in $attempts) {
            Assert-TraceCondition (
                [string](Get-SpanTag $attempt "error.type") -eq "mcp_timeout"
            ) "Every MCP attempt must have error.type=mcp_timeout"
            Assert-TraceCondition (
                [string](Get-SpanTag $attempt "agent.tool.outcome") -eq "failed"
            ) "Every MCP attempt must fail"
        }
        Assert-TraceCondition ($summary.verifier -eq "fail") "Verifier must fail"
        Assert-TraceCondition ($summary.memoryWrite -eq "False") `
            "Failed result must not enter trusted memory"
        Assert-TraceCondition (
            [string](Get-SpanTag $memory "agent.memory.write_reason") -eq
                "verification_failed"
        ) "Memory write reason must be verification_failed"
        Assert-TraceCondition (
            [string](Get-SpanTag $turn "agent.task.outcome") -eq "failed"
        ) "Task outcome must be failed"
        $summary.mcpAttempts = $attemptNumbers
        $summary.errorType = "mcp_timeout"
        $summary.memoryWriteReason = "verification_failed"
    }

    return [pscustomobject]$summary
}
