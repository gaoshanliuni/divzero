param([string]$CacheDirectory = '')
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$destination = [IO.Path]::GetFullPath((Join-Path $root 'worker/src/main/resources/META-INF/mineagent/tools'))
if (-not $destination.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'MEDIA_PATH_BOUNDARY' }
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$assets = @(
    @{Name='yt-dlp-2026.08.19.exe'; Bytes=17840399; Url='https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/yt-dlp.exe'},
    @{Name='ffmpeg-n8.1.3-34-ga35157abb9-win64-lgpl-shared-8.1.zip'; Bytes=80313154; Url='https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/autobuild-2026-11-09-12-42/ffmpeg-n8.1.3-34-ga35157abb9-win64-lgpl-shared-8.1.zip'}
)
foreach ($asset in $assets) {
    $target = Join-Path $destination $asset.Name
    if ((Test-Path -LiteralPath $target) -and (Get-Item -LiteralPath $target).Length -eq $asset.Bytes) { continue }
    $temp = Join-Path $destination ([Guid]::NewGuid().ToString() + '.download')
    try {
        $cached = if ($CacheDirectory) { Join-Path $CacheDirectory $asset.Name } else { '' }
        if ($cached -and (Test-Path -LiteralPath $cached)) { Copy-Item -LiteralPath $cached -Destination $temp }
        else { Invoke-WebRequest -Uri $asset.Url -OutFile $temp -TimeoutSec 180 }
        if ((Get-Item -LiteralPath $temp).Length -ne $asset.Bytes) { throw 'MEDIA_DEPENDENCY_SIZE_MISMATCH' }
        if (Test-Path -LiteralPath $target) { Move-Item -LiteralPath $target -Destination ($target + '.previous-' + [Guid]::NewGuid().ToString()) }
        Move-Item -LiteralPath $temp -Destination $target
        Write-Output ('Verified dependency: ' + $asset.Name)
    } finally {
        # Keep incomplete download evidence for inspection; never permanently delete it here.
    }
}
