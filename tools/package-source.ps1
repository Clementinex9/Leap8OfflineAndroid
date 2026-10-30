#Requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$ArchivePath)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$archiveFile = [IO.Path]::GetFullPath($ArchivePath)
if ($archiveFile.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Place the source archive outside the project to avoid including itself.'
}
if (Test-Path -LiteralPath $archiveFile) { throw "Archive already exists: $archiveFile" }
$parent = Split-Path $archiveFile -Parent
if (-not (Test-Path -LiteralPath $parent -PathType Container)) { throw "Destination directory does not exist: $parent" }

# Prune generated trees before walking; retain shipped models, native runtime,
# headers, notices, source tests and reproducible build scripts.
$excludedDirectories = @('.git', '.gradle', '.kotlin', '.idea', '.cxx', 'build', 'work')
$directories = [Collections.Generic.Stack[string]]::new()
$directories.Push($projectRoot)
$files = [Collections.Generic.List[string]]::new()
while ($directories.Count -gt 0) {
    $directory = $directories.Pop()
    foreach ($child in Get-ChildItem -LiteralPath $directory -Force) {
        if ($child.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }
        if ($child.PSIsContainer) {
            if ($child.Name -notin $excludedDirectories) { $directories.Push($child.FullName) }
        } elseif ($child.Name -ne 'local.properties' -and $child.Name -ne '.DS_Store' -and
            $child.Extension -notin @('.iml', '.jks', '.keystore') -and
            $child.Name -ne 'libOpenCL.so') {
            # libOpenCL is device-owned, link-only and must not be redistributed.
            $files.Add($child.FullName)
        }
    }
}
$output = [IO.File]::Open($archiveFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
try {
    $zip = [IO.Compression.ZipArchive]::new($output, [IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        foreach ($file in ($files | Sort-Object)) {
            $relative = [IO.Path]::GetRelativePath($projectRoot, $file).Replace('\', '/')
            $entry = $zip.CreateEntry("Leap8OfflineAndroid/$relative", [IO.Compression.CompressionLevel]::Optimal)
            $modified = [IO.File]::GetLastWriteTime($file)
            if ($modified.Year -lt 1980 -or $modified.Year -gt 2107) { $modified = [DateTime]::new(1980, 1, 1) }
            $entry.LastWriteTime = $modified
            $input = [IO.File]::OpenRead($file)
            $target = $entry.Open()
            try { $input.CopyTo($target) } finally { $target.Dispose(); $input.Dispose() }
        }
    } finally { $zip.Dispose() }
} finally { $output.Dispose() }
Write-Host "Packaged $($files.Count) files: $archiveFile"
Get-FileHash -LiteralPath $archiveFile -Algorithm SHA256
