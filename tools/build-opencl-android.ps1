#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$AndroidSdk = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$NdkVersion = '29.0.13113456',
    [string]$CmakeVersion = '3.31.6',
    [string]$BuildDirectory = (Join-Path $env:TEMP 'leap8-opencl-b10333'),
    [int]$Parallel = 4
)
$ErrorActionPreference = 'Stop'
# Upstream embed_kernel.py uses the locale-default text encoding. Chinese Windows
# defaults to GBK, but kernel comments are UTF-8; force UTF-8 in child Python only.
$env:PYTHONUTF8 = '1'
$projectRoot = Split-Path $PSScriptRoot -Parent
$sourceDirectory = Join-Path $projectRoot 'work/third-party/llama.cpp-b10333'
$dependencyDirectory = Join-Path $projectRoot 'work/OpenCL'
$headersDirectory = Join-Path $dependencyDirectory 'OpenCL-Headers'
$clhppDirectory = Join-Path $dependencyDirectory 'OpenCL-CLHPP'
$linkDirectory = Join-Path $dependencyDirectory 'link-only'
$outputDirectory = Join-Path $dependencyDirectory 'build-output/arm64-v8a'
$cmake = Join-Path $AndroidSdk "cmake/$CmakeVersion/bin/cmake.exe"
$ninja = Join-Path $AndroidSdk "cmake/$CmakeVersion/bin/ninja.exe"
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
$toolchain = Join-Path $AndroidSdk "ndk/$NdkVersion/build/cmake/android.toolchain.cmake"
$python = (Get-Command python.exe -ErrorAction Stop).Source

function Invoke-Checked {
    param([string]$FilePath, [string[]]$Arguments)
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$FilePath failed with exit code $LASTEXITCODE" }
}

foreach ($requiredPath in @($cmake, $ninja, $adb, $toolchain, "$sourceDirectory/CMakeLists.txt")) {
    if (-not (Test-Path -LiteralPath $requiredPath)) { throw "Missing: $requiredPath" }
}
New-Item -ItemType Directory -Path $dependencyDirectory, $linkDirectory, $outputDirectory, $BuildDirectory -Force | Out-Null
if (-not (Test-Path -LiteralPath "$headersDirectory/CL/cl.h")) {
    Invoke-Checked git @('clone', '--depth', '1', 'https://github.com/KhronosGroup/OpenCL-Headers.git', $headersDirectory)
}
if (-not (Test-Path -LiteralPath "$clhppDirectory/include/CL/opencl.hpp")) {
    Invoke-Checked git @('clone', '--depth', '1', 'https://github.com/KhronosGroup/OpenCL-CLHPP.git', $clhppDirectory)
}
Write-Host 'Khronos OpenCL-Headers commit:'
Invoke-Checked git @('-C', $headersDirectory, 'rev-parse', 'HEAD')
Write-Host 'Khronos OpenCL-CLHPP commit (cached for completeness; C backend does not require it):'
Invoke-Checked git @('-C', $clhppDirectory, 'rev-parse', 'HEAD')

# This device-owned vendor binary is a LINK INPUT ONLY. Never copy it into jniLibs/APK.
$vendorLinkLibrary = Join-Path $linkDirectory 'libOpenCL.so'
if (-not (Test-Path -LiteralPath $vendorLinkLibrary)) {
    Invoke-Checked $adb @('pull', '/vendor/lib64/libOpenCL.so', $vendorLinkLibrary)
}
Get-FileHash -LiteralPath $vendorLinkLibrary -Algorithm SHA256

$configurationArguments = @(
    '-S', $sourceDirectory, '-B', $BuildDirectory, '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$ninja", "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
    '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_shared',
    '-DCMAKE_BUILD_TYPE=Release', '-DBUILD_SHARED_LIBS=ON',
    '-DGGML_BACKEND_DL=ON', '-DGGML_NATIVE=OFF', '-DGGML_CPU=OFF',
    '-DGGML_VULKAN=OFF', '-DGGML_OPENCL=ON', '-DGGML_OPENCL_TARGET_VERSION=300',
    '-DGGML_OPENCL_EMBED_KERNELS=ON', '-DGGML_OPENCL_USE_ADRENO_KERNELS=OFF',
    '-DGGML_OPENCL_USE_ADRENO_BIN_KERNELS=OFF',
    "-DOpenCL_LIBRARY=$vendorLinkLibrary", "-DOpenCL_INCLUDE_DIR=$headersDirectory",
    "-DPython3_EXECUTABLE=$python",
    '-DLLAMA_BUILD_COMMON=OFF', '-DLLAMA_BUILD_TESTS=OFF', '-DLLAMA_BUILD_TOOLS=OFF',
    '-DLLAMA_BUILD_EXAMPLES=OFF', '-DLLAMA_BUILD_SERVER=OFF', '-DLLAMA_BUILD_APP=OFF',
    '-DLLAMA_BUILD_MTMD=OFF', '-DLLAMA_BUILD_UI=OFF'
)
Invoke-Checked $cmake $configurationArguments
Invoke-Checked $cmake @('--build', $BuildDirectory, '--target', 'ggml-opencl', '--parallel', "$Parallel")
$builtLibrary = Join-Path $BuildDirectory 'bin/libggml-opencl.so'
if (-not (Test-Path -LiteralPath $builtLibrary)) { throw "Expected backend was not produced: $builtLibrary" }
$outputLibrary = Join-Path $outputDirectory 'libggml-opencl.so'
Copy-Item -LiteralPath $builtLibrary -Destination $outputLibrary
Get-Item -LiteralPath $outputLibrary | Select-Object FullName, Length, LastWriteTime
Get-FileHash -LiteralPath $outputLibrary -Algorithm SHA256
Write-Host 'Only libggml-opencl.so is staged. Integrate it alongside the exact b10333 ggml runtime separately.'
