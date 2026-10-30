#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }),
    [string]$GradleExecutable,
    [string]$RuntimeDirectory,
    [string[]]$Tasks = @(':app:assemblePerformance', ':app:testPerformanceUnitTest', ':npu-qairt:testDebugUnitTest'),
    [switch]$Offline,
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
if (-not $RuntimeDirectory) { $RuntimeDirectory = Join-Path $projectRoot 'third_party/llama-b11982-android-snapdragon' }
$runtimeRoot = (Resolve-Path -LiteralPath $RuntimeDirectory -ErrorAction Stop).Path
$manifestPath = Join-Path $runtimeRoot 'manifest.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.release -ne 'b11982' -or $manifest.upstream_archive_sha256 -ne '7f6b330ad008011af27e30f668b9ee283aa15bd5caf08983cd8f06c5919b8ca7') {
    throw 'Expected the pinned official b11982 Snapdragon archive provenance.'
}
$requiredPaths = @('LICENSE', 'include/llama.h', 'include/ggml.h', 'include/ggml-backend.h',
    'lib/libggml-base.so', 'lib/libggml-cpu.so', 'lib/libggml-rpc.so', 'lib/libggml-opencl.so',
    'lib/libggml-hexagon.so', 'lib/libggml.so', 'lib/libllama.so', 'lib/libggml-htp-v79.so')
$listedPaths = @($manifest.files | ForEach-Object { $_.path })
foreach ($requiredPath in $requiredPaths) {
    if ($requiredPath -notin $listedPaths) { throw "Runtime manifest is missing $requiredPath" }
}
if (($listedPaths | Select-Object -Unique).Count -ne $listedPaths.Count) { throw 'Duplicate runtime manifest paths.' }
foreach ($runtimeEntry in $manifest.files) {
    $relativePath = [string]$runtimeEntry.path
    if ([IO.Path]::IsPathRooted($relativePath) -or $relativePath.Contains(':') -or [regex]::IsMatch($relativePath, '(^|[\\/])\.\.([\\/]|$)')) {
        throw "Unsafe runtime manifest path: $relativePath"
    }
    $runtimeFile = Join-Path $runtimeRoot $relativePath
    $item = Get-Item -LiteralPath $runtimeFile -ErrorAction Stop
    if ($item.PSIsContainer -or $item.Length -ne $runtimeEntry.bytes) { throw "Runtime size mismatch: $relativePath" }
    if ((Get-FileHash -LiteralPath $runtimeFile -Algorithm SHA256).Hash -ine $runtimeEntry.sha256) {
        throw "Runtime SHA-256 mismatch: $relativePath"
    }
}
Write-Host "Verified $($listedPaths.Count) pinned Hexagon runtime files in $runtimeRoot"
if ($VerifyOnly) { return }

if (-not $JavaHome) { throw 'Set JAVA_HOME or pass -JavaHome pointing to JDK 21.' }
$resolvedJavaHome = (Resolve-Path -LiteralPath $JavaHome -ErrorAction Stop).Path
$java = Join-Path $resolvedJavaHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw "Missing JDK Java executable: $java" }
$javaVersion = (& $java -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "21[\."]') { throw "Use JDK 21 with this build. Found: $javaVersion" }
$resolvedSdk = (Resolve-Path -LiteralPath $AndroidSdk -ErrorAction Stop).Path
foreach ($sdkRequiredPath in @('platforms/android-35/android.jar', 'ndk/29.0.13113456/build/cmake/android.toolchain.cmake', 'cmake/3.31.6/bin/cmake.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $resolvedSdk $sdkRequiredPath) -PathType Leaf)) { throw "Android SDK component missing: $sdkRequiredPath" }
}
if (-not $GradleExecutable) { $GradleExecutable = Join-Path $projectRoot 'gradlew.bat' }
$gradle = (Get-Command $GradleExecutable -ErrorAction Stop).Source
if (Test-Path -LiteralPath (Join-Path $projectRoot 'local.properties')) {
    Write-Warning 'local.properties exists. Android Gradle Plugin gives sdk.dir priority; ensure it points to the SDK supplied above.'
}
$previousEnvironment = @{}
foreach ($environmentName in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'HEXAGON_RUNTIME', 'QAIRT_PATH', 'LEAP8_SKIP_NATIVE_BUILD')) {
    $previousEnvironment[$environmentName] = [Environment]::GetEnvironmentVariable($environmentName, 'Process')
}
Push-Location -LiteralPath $projectRoot
try {
    $env:JAVA_HOME = $resolvedJavaHome
    $env:ANDROID_HOME = $resolvedSdk
    $env:ANDROID_SDK_ROOT = $resolvedSdk
    $env:HEXAGON_RUNTIME = $runtimeRoot
    Remove-Item Env:QAIRT_PATH -ErrorAction SilentlyContinue
    Remove-Item Env:LEAP8_SKIP_NATIVE_BUILD -ErrorAction SilentlyContinue
    $gradleArguments = @($Tasks) + @('-Pkotlin.incremental=false', '--no-daemon')
    if ($Offline) { $gradleArguments += '--offline' }
    & $gradle @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    Write-Host "SM8750 build completed. APK directory: $(Join-Path $projectRoot 'app/build/outputs/apk/performance')"
} finally {
    Pop-Location
    foreach ($environmentName in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($environmentName, $previousEnvironment[$environmentName], 'Process')
    }
}
