# Reassembles libpostal model data from split chunks.
# PowerShell script for Windows.
#
# Usage:
#   .\reassemble.ps1 [-OutputDir ..\model-data]

param(
    [string]$OutputDir = (Join-Path (Split-Path $PSScriptRoot) "model-data")
)

$ErrorActionPreference = "Stop"
$ChunkDir = $PSScriptRoot

Write-Host "Reassembling libpostal model from chunks..."
Write-Host "Source: $ChunkDir"
Write-Host "Output: $OutputDir"

# Concatenate chunks
$chunks = Get-ChildItem -Path $ChunkDir -Filter "model-part-*" | Sort-Object Name
$tempFile = Join-Path $env:TEMP "libpostal-model.tar.gz"

Write-Host "Concatenating $($chunks.Count) chunks..."
$stream = [System.IO.File]::Create($tempFile)
foreach ($chunk in $chunks) {
    $bytes = [System.IO.File]::ReadAllBytes($chunk.FullName)
    $stream.Write($bytes, 0, $bytes.Length)
}
$stream.Close()
Write-Host "Combined size: $([math]::Round((Get-Item $tempFile).Length / 1MB)) MB"

# Extract using tar (available on Windows 10+)
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
tar xzf $tempFile -C $OutputDir
Remove-Item $tempFile

Write-Host "Model data extracted to: $OutputDir"
$size = (Get-ChildItem -Path $OutputDir -Recurse | Measure-Object -Property Length -Sum).Sum
Write-Host "Total size: $([math]::Round($size / 1GB, 1)) GB"
