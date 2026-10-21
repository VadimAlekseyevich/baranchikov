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
Invoke-Docker @('up','-d','--build','--scale','facade=1','--scale','business=1','--scale','storage=1','facade')
foreach ($layer in @('facade','business','storage')) {
    foreach ($replicas in @(1,2,3)) {
        Invoke-Docker @('up','-d','--scale','facade=1','--scale','business=1','--scale','storage=1','facade')
        Invoke-Docker @('up','-d','--scale',"$layer=$replicas",$layer)
        Start-Sleep -Seconds 4
        Write-Host "Benchmarking $layer replicas=$replicas ..."
        $lines = & docker compose run --rm -T -e "BENCH_REQUESTS=$env:BENCH_REQUESTS" -e "BENCH_THREADS=$env:BENCH_THREADS" benchmark
        if ($LASTEXITCODE -ne 0) { throw 'Benchmark run failed' }
        $result = @($lines | Where-Object { $_ -like 'RESULT,*' } | Select-Object -Last 1)
        if ($result.Count -ne 1) { throw 'Benchmark did not print a RESULT row' }
        "$layer,$replicas,$($result[0].Substring(7))" | Add-Content $output
    }
}
Invoke-Docker @('up','-d','--scale','facade=1','--scale','business=1','--scale','storage=1','facade')
Write-Host "Measured results written to $output"
