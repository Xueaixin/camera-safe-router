param(
    [string]$BaseUrl = 'http://localhost:8080',
    [ValidateRange(1, 10000)]
    [int]$Iterations = 50,
    [ValidateRange(0, 1000)]
    [int]$Warmup = 5,
    [int]$JavaProcessId = 0
)

$ErrorActionPreference = 'Stop'
$requestBody = @{
    start = @{
        lng = 116.3975
        lat = 39.9087
        coordinateSystem = 'WGS84'
        source = 'CURRENT_LOCATION'
    }
    end = @{
        lng = 116.4700
        lat = 39.9920
        coordinateSystem = 'WGS84'
        source = 'MAP_PICK'
    }
    vehicle = 'CAR'
} | ConvertTo-Json -Depth 4

function Invoke-Route {
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/routes" `
        -ContentType 'application/json; charset=utf-8' `
        -Body $requestBody
    $stopwatch.Stop()
    if ($response.cameraConflictCount -ne 0) {
        throw "Unsafe successful response: cameraConflictCount=$($response.cameraConflictCount)"
    }
    return $stopwatch.Elapsed.TotalMilliseconds
}

for ($index = 0; $index -lt $Warmup; $index++) {
    [void](Invoke-Route)
}

if ($JavaProcessId -gt 0) {
    & jcmd $JavaProcessId GC.heap_info
}

$samples = [System.Collections.Generic.List[double]]::new()
$total = [System.Diagnostics.Stopwatch]::StartNew()
for ($index = 0; $index -lt $Iterations; $index++) {
    $samples.Add((Invoke-Route))
}
$total.Stop()

$sorted = $samples | Sort-Object
$p50Index = [Math]::Max(0, [Math]::Ceiling($sorted.Count * 0.50) - 1)
$p95Index = [Math]::Max(0, [Math]::Ceiling($sorted.Count * 0.95) - 1)
$result = [PSCustomObject]@{
    iterations = $Iterations
    p50Milliseconds = [Math]::Round($sorted[$p50Index], 2)
    p95Milliseconds = [Math]::Round($sorted[$p95Index], 2)
    maxMilliseconds = [Math]::Round(($sorted | Measure-Object -Maximum).Maximum, 2)
    throughputPerSecond = [Math]::Round($Iterations / $total.Elapsed.TotalSeconds, 2)
}
$result | Format-List

if ($JavaProcessId -gt 0) {
    & jcmd $JavaProcessId GC.heap_info
}
