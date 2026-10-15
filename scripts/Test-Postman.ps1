param(
    [switch]$Interactive,
    [switch]$SimulateFailure
)

$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'postman/run.mjs'
$arguments = @($runner)
if ($Interactive) { $arguments += '--interactive' }
if ($SimulateFailure) { $arguments += '--simulate-failure' }
& node @arguments
if ($LASTEXITCODE -ne 0) { throw "Postman validation failed (exit $LASTEXITCODE). See target/postman/latest/summary.md." }
