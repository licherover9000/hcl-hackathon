# RecoverPlus evaluation run (Windows PowerShell). No database or Spring needed.
# Run from the repo root:  powershell -ExecutionPolicy Bypass -File scripts\run_evaluation.ps1 -AsOf 2026-04-15 -Eval
param(
  [string]$AsOf = "2026-03-31",
  [string]$Data = "data\input",
  [string]$Out = "output",
  [switch]$Eval
)
$ErrorActionPreference = "Stop"
$src = Get-ChildItem -Recurse "backend\src\main\java" -Filter *.java |
  Where-Object { $_.FullName -match '\\(engine|io)\\' -or $_.Name -eq 'OfflineEvaluation.java' } |
  ForEach-Object { $_.FullName }
New-Item -ItemType Directory -Force -Path "build\offline" | Out-Null
javac -d build\offline $src
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
$runArgs = @("--data", $Data, "--as-of", $AsOf, "--out", $Out)
if ($Eval) { $runArgs += "--eval" }
if (Test-Path "$Out\ai_scores.csv") { $runArgs += @("--scores", "$Out\ai_scores.csv") }
java -cp build\offline com.threepointers.recoverplus.batch.OfflineEvaluation @runArgs
if ($LASTEXITCODE -ne 0) { throw "evaluation run failed" }
if (-not $Eval) {
  $exp = Get-ChildItem "$Data\*expected_collections*" | Select-Object -First 1
  if ($exp) { python scripts\compare_expected.py "$Out\collections_output.csv" $exp.FullName }
  $req = Get-ChildItem "$Data\*settlement_requests_sample*" | Select-Object -First 1
  if ($req) { python scripts\compare_expected.py "$Out\settlement_decisions.csv" $req.FullName --key request_id --prefix expected_ }
}
