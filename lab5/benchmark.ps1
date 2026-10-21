$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not $env:BENCH_REQUESTS) { $env:BENCH_REQUESTS = '500' }
if (-not $env:BENCH_THREADS) { $env:BENCH_THREADS = '8' }
$output = 'benchmark-results.csv'
'layer,replicas,threads,requests,completed,errors,rps,p50_ms,p95_ms' | Set-Content $output
function Invoke-Docker([string[]] $Params) {
    & docker compose @Params
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed: $($Params -join ' ')" }
}
Invoke-Docker -Params @('up','-d','--build','--scale','facade=1','--scale','business=1','--scale','storage=1','facade')
foreach ($layer in @('facade','business','storage')) {
    foreach ($replicas in @(1,2,3)) {
        Invoke-Docker -Params @('up','-d','--scale','facade=1','--scale','business=1','--scale','storage=1','facade')
        Invoke-Docker -Params @('up','-d','--scale',"$layer=$replicas",$layer)
        Start-Sleep -Seconds 4
        Write-Host "Benchmarking $layer replicas=$replicas ..."
        $lines = & docker compose run --rm -T -e "BENCH_REQUESTS=$env:BENCH_REQUESTS" -e "BENCH_THREADS=$env:BENCH_THREADS" benchmark
        if ($LASTEXITCODE -ne 0) { throw 'Benchmark run failed' }
        $result = @($lines | Where-Object { $_ -like 'RESULT,*' } | Select-Object -Last 1)
        if ($result.Count -ne 1) { throw 'Benchmark did not print a RESULT row' }
        "$layer,$replicas,$($result[0].Substring(7))" | Add-Content $output
    }
}
Invoke-Docker -Params @('up','-d','--scale','facade=1','--scale','business=1','--scale','storage=1','facade')
Write-Host "Measured results written to $output"
$results = Import-Csv $output
$report = @(
    '# Comparison of measured throughput',
    '',
    '| Scaled layer | 1 replica, rps | 2 replicas, rps | 3 replicas, rps | Speedup x2 | Speedup x3 |',
    '|---|---:|---:|---:|---:|---:|'
)
foreach ($layer in @('facade','business','storage')) {
    $one = [double](($results | Where-Object { $_.layer -eq $layer -and $_.replicas -eq '1' } | Select-Object -First 1).rps)
    $two = [double](($results | Where-Object { $_.layer -eq $layer -and $_.replicas -eq '2' } | Select-Object -First 1).rps)
    $three = [double](($results | Where-Object { $_.layer -eq $layer -and $_.replicas -eq '3' } | Select-Object -First 1).rps)
    if ($one -gt 0) {
        $report += ('| {0} | {1:N2} | {2:N2} | {3:N2} | {4:N2}x | {5:N2}x |' -f $layer,$one,$two,$three,($two/$one),($three/$one))
    }
}
$report += ''
$report += 'Workload: independent UDP GET_OPEN_ORDERS requests. Comparison is one measured run; results depend on host resources.'
$report | Set-Content 'benchmark-comparison.md'
$report | ForEach-Object { Write-Host $_ }
