param([string]$Commit = 'HEAD')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($Commit -ne 'HEAD' -and $Commit -notmatch '^[a-f0-9]{40}$') { throw 'BUILD_VERSION_COMMIT_INVALID' }
$shallow=(& git -C $root rev-parse --is-shallow-repository).Trim()
if ($LASTEXITCODE -ne 0 -or $shallow -ne 'false') { throw 'BUILD_VERSION_REQUIRES_FULL_PUBLIC_HISTORY' }
$sha=(& git -C $root rev-parse --verify --end-of-options "$Commit^{commit}").Trim()
if ($LASTEXITCODE -ne 0 -or $sha -notmatch '^[a-f0-9]{40}$') { throw 'BUILD_VERSION_COMMIT_UNAVAILABLE' }
$count=(& git -C $root rev-list --count $sha).Trim()
$time=(& git -C $root show -s --format=%cI $sha).Trim()
if ($LASTEXITCODE -ne 0 -or $count -notmatch '^[1-9][0-9]*$') { throw 'BUILD_VERSION_HISTORY_INVALID' }
$date=[DateTimeOffset]::Parse($time,[Globalization.CultureInfo]::InvariantCulture).ToUniversalTime().ToString('yyyy.M.d',[Globalization.CultureInfo]::InvariantCulture)
Write-Output "$date-dev.$count"
