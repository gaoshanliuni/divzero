param([string]$CacheDirectory = '')
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$destination = [IO.Path]::GetFullPath((Join-Path $root 'worker/src/main/resources/META-INF/mineagent/tools'))
if (-not $destination.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'MEDIA_PATH_BOUNDARY' }
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$assets = @(
    @{Name='yt-dlp-2026.08.19.exe'; Hash='66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a'; Url='https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/yt-dlp.exe'},
    @{Name='ffmpeg-n8.1.3-6-gff48edd8b2-win64-lgpl-shared-8.1.zip'; Hash='1c9af2356443fec537fe1a64a5b33cb4c54fa212ad6590464423b3437e1aaa44'; Url='https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-09-29-13-10/ffmpeg-n8.1.3-6-gff48edd8b2-win64-lgpl-shared-8.1.zip'}
)
foreach ($asset in $assets) {
    $target = Join-Path $destination $asset.Name
    if ((Test-Path -LiteralPath $target) -and (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -eq $asset.Hash) { continue }
    $temp = Join-Path $destination ([Guid]::NewGuid().ToString() + '.download')
    try {
        $cached = if ($CacheDirectory) { Join-Path $CacheDirectory $asset.Name } else { '' }
        if ($cached -and (Test-Path -LiteralPath $cached)) { Copy-Item -LiteralPath $cached -Destination $temp }
        else { Invoke-WebRequest -Uri $asset.Url -OutFile $temp -TimeoutSec 180 }
        if ((Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash.ToLowerInvariant() -ne $asset.Hash) { throw 'MEDIA_DEPENDENCY_HASH_MISMATCH' }
        Move-Item -LiteralPath $temp -Destination $target -Force
        Write-Output ('Verified dependency: ' + $asset.Name)
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
    }
}
