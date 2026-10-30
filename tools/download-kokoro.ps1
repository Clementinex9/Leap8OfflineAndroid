param([int]$Connections = 24)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
$downloadDirectory = Join-Path $projectDirectory 'work/kokoro-int8-chunks'
New-Item -ItemType Directory -Force -Path $downloadDirectory | Out-Null
$uri = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2'
# Size and digest were read from the GitHub release API, not an untrusted mirror.
$size = [long]132353410
$partSize = [long]1048576
$partCount = [int][Math]::Ceiling($size / $partSize)
0..($partCount - 1) | ForEach-Object -ThrottleLimit $Connections -Parallel {
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    $partIndex = $_
    $start = [long]$partIndex * $using:partSize
    $end = [Math]::Min($using:size - 1, $start + $using:partSize - 1)
    $partPath = Join-Path $using:downloadDirectory ('part-{0:d3}' -f $partIndex)
    $expected = $end - $start + 1
    if (!(Test-Path $partPath) -or (Get-Item $partPath).Length -ne $expected) {
        $partUri = $using:uri + '?part=' + $partIndex
        & curl.exe -4 -sSL --fail --resolve release-assets.githubusercontent.com:443:185.199.108.133 --connect-timeout 45 --max-time 180 --retry 8 --retry-all-errors --retry-delay 1 -r "$start-$end" -o $partPath $partUri 2>$null
        if ($LASTEXITCODE -ne 0) { throw "Model download failed for part $partIndex" }
    }
    if ((Get-Item $partPath).Length -ne $expected) { throw "Invalid part $partIndex length" }
    Write-Output "Downloaded part $partIndex"
}
$archive = Join-Path $projectDirectory 'work/kokoro-int8-multi-lang-v1_0-complete.tar.bz2'
$outputStream = [IO.File]::Create($archive)
try {
    foreach ($index in 0..($partCount - 1)) {
        $inputStream = [IO.File]::OpenRead((Join-Path $downloadDirectory ('part-{0:d3}' -f $index)))
        try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose() }
    }
} finally { $outputStream.Dispose() }
if ((Get-Item $archive).Length -ne $size) { throw 'Model archive length mismatch' }
$archiveHash = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($archiveHash -ne '72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653') {
    throw "Official release digest mismatch: $archiveHash"
}
Write-Output "Official release digest verified: $archiveHash"
& tar.exe -xf $archive -C (Join-Path $projectDirectory 'work')
if ($LASTEXITCODE -ne 0) { throw 'Model archive extraction failed' }
