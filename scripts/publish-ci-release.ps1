param(
    [Parameter(Mandatory)][string]$Commit,
    [ValidateSet('standard','with-media')][string]$Variant = 'standard',
    [string]$AssetDirectory = 'build/release-assets',
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo='gaoshanliuni/divzero'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$assets=[IO.Path]::GetFullPath($AssetDirectory,$root)
if (-not $assets.StartsWith((Join-Path $root 'build')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'RELEASE_ASSET_PATH_BOUNDARY' }
if ($Commit -notmatch '^[a-f0-9]{40}$') { throw 'RELEASE_COMMIT_INVALID' }
$info=Get-Content -LiteralPath (Join-Path $assets 'BUILD-INFO.json') -Raw | ConvertFrom-Json
if ($info.sourceCommit -ne $Commit -or $info.variant -ne $Variant -or $info.repository -ne "https://github.com/$repo") { throw 'RELEASE_BUILD_IDENTITY_MISMATCH' }
$versions = & (Join-Path $PSScriptRoot 'get-release-versions.ps1')
foreach ($property in $versions.PSObject.Properties) {
    if (-not $info.PSObject.Properties[$property.Name] -or $info.($property.Name) -cne $property.Value) { throw "RELEASE_VERSION_MISMATCH: $($property.Name)" }
}
$run=[string]$info.workflowRunId;$attempt=[string]$info.workflowRunAttempt
if ($run -notmatch '^[1-9][0-9]*$' -or $attempt -notmatch '^[1-9][0-9]*$') { throw 'RELEASE_BUILD_RUN_MISSING' }
if (-not $DryRun -and ($run -ne $env:GITHUB_RUN_ID -or [long]$attempt -gt [long]$env:GITHUB_RUN_ATTEMPT -or $env:GITHUB_REPOSITORY -ne $repo -or $env:GITHUB_REF -ne 'refs/heads/main')) { throw 'RELEASE_ONLY_FROM_TRUSTED_MAIN_RUN' }
$manifest=[Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
foreach($line in [IO.File]::ReadAllLines((Join-Path $assets 'SHA256SUMS'))) {
    if ($line -notmatch '^([a-f0-9]{64})  ([A-Za-z0-9][A-Za-z0-9_.+ -]*)$') { throw 'RELEASE_CHECKSUM_FORMAT' }
    $hash=$Matches[1];$name=$Matches[2]
    if ($manifest.ContainsKey($name) -or $name -eq 'SHA256SUMS') { throw 'RELEASE_DUPLICATE_CHECKSUM' }
    $file=Join-Path $assets $name
    if (-not (Test-Path -LiteralPath $file -PathType Leaf) -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) { throw "RELEASE_CHECKSUM_MISMATCH: $name" }
    $manifest.Add($name,$hash)
}
$files=@(Get-ChildItem -LiteralPath $assets -File | Sort-Object Name)
if ($files.Count -ne $manifest.Count+1 -or @(Get-ChildItem -LiteralPath $assets -Directory).Count) { throw 'RELEASE_UNEXPECTED_FILES' }
foreach($file in $files) {
    if ($file.Extension -notin @('.jar','.json','.txt','.md','') -or $file.Name -ne 'SHA256SUMS' -and -not $manifest.ContainsKey($file.Name)) { throw 'RELEASE_FILE_NOT_ALLOWED' }
}
$required=@([string]$info.jar,'webgui-neoforge-1.6.2+mc26.1.2.jar','mcef_neoforge_2.2.0_MC_26.1.1.jar','mcef-2.2.0-neoforge-sources.jar','LICENSE-WebGUI.txt','LICENSE-MCEF.txt','LICENSE-DIVZERO.txt','DEPENDENCIES.json','README.txt','THIRD-PARTY-NOTICES.md','BUILD-INFO.json')
foreach($name in $required) { if (-not $manifest.ContainsKey($name)) { throw "RELEASE_REQUIRED_FILE_MISSING: $name" } }
if ($manifest[[string]$info.jar] -ne $info.sha256) { throw 'RELEASE_MAIN_JAR_HASH_MISMATCH' }
$manifest.Add('SHA256SUMS',(Get-FileHash -LiteralPath (Join-Path $assets 'SHA256SUMS') -Algorithm SHA256).Hash.ToLowerInvariant())
$short=$Commit.Substring(0,12)
$tag="ci-v$($versions.modVersion)-mc$($versions.minecraftVersion)-$short-$Variant-r$run-a$attempt"
$title="DivZero $($versions.modVersion) · Minecraft $($versions.minecraftVersion) · 开发构建 $short ($Variant)"
$marker="<!-- divzero-ci-release-v1 commit=$Commit variant=$Variant run=$run build-attempt=$attempt -->"
$notes=@"
$marker
## $title

| 支持环境 | 版本 |
| --- | --- |
| Mod | $($versions.modVersion) |
| Minecraft | $($versions.minecraftVersion)（声明范围：$($versions.minecraftVersionRange)） |
| NeoForge 构建版本 | $($versions.neoForgeVersion) |
| Java | $($versions.requiredJavaVersion) |

自动构建的测试版本，不是完整 V1 正式发行。
源码：[$short](https://github.com/$repo/commit/$Commit) · [构建记录](https://github.com/$repo/actions/runs/$run)

### 客户端安装：分别下载这三个 JAR，放入 mods
- [DivZero 主模组](https://github.com/$repo/releases/download/$tag/$([Uri]::EscapeDataString([string]$info.jar)))
- [WebGUI 1.6.2+mc26.1.2](https://github.com/$repo/releases/download/$tag/webgui-neoforge-1.6.2%2Bmc26.1.2.jar)
- [MCEF 2.2.0](https://github.com/$repo/releases/download/$tag/mcef_neoforge_2.2.0_MC_26.1.1.jar)

请使用上述 Minecraft / NeoForge / Java 版本；不代表支持其它 Minecraft 版本。关闭游戏后，在备份过的测试实例替换旧版同 Mod；不要同时放入多个版本。
MCEF 的原始文件名标注 MC26.1.1，本项目锁定并测试的兼容工件就是这一版；不修改上游 JAR。
Chromium/JCEF 原生运行库仍由 MCEF 准备，这里不打包浏览器原生库或 YSM 模型。

SHA256SUMS 包含全部附件的校验值，BUILD-INFO.json 记录对应源码与构建信息。
mcef-2.2.0-neoforge-sources.jar 是对应开发源码，**不要放入 mods**；许可及来源分别在 LICENSE-*.txt、DEPENDENCIES.json、THIRD-PARTY-NOTICES.md 中。
WebGUI [上游源码](https://github.com/mc-webgui/webgui/tree/v1.6.2)，MCEF [上游源码](https://github.com/Keksuccino/mcef/)。

不上传整合 ZIP。GitHub 自动附加的 Source code (zip/tar.gz) 是源码，不是安装包。
标准版不包含可选 yt-dlp/FFmpeg；with-media 为手动选择的多媒体版。
本流程只证明编译、打包及所列测试通过，不代替真实模型或游戏内完整验收。
"@
if ($DryRun) { Write-Output "RELEASE_DRY_RUN_TAG=$tag"; Write-Output "RELEASE_TITLE=$title"; Write-Output $notes; Write-Output "VERIFIED_FILES=$($files.Count)"; return }
if (-not $env:GH_TOKEN) { throw 'RELEASE_TOKEN_MISSING' }
$headers=@{Authorization="Bearer $env:GH_TOKEN";Accept='application/vnd.github+json';'X-GitHub-Api-Version'='2022-11-28';'User-Agent'='DivZero automatic development releases'}
$api="https://api.github.com/repos/$repo"
function Get-Optional([string]$Url) {
    try { return Invoke-RestMethod -Uri $Url -Headers $headers -TimeoutSec 60 }
    catch { if ($null -ne $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) { return $null }; throw }
}
function Get-Assets([long]$ReleaseId) {
    $all = @()
    for ($page=1; $page -le 10; $page++) {
        # Invoke-RestMethod emits a JSON array as one pipeline object. Assign first,
        # then normalize, so an empty GitHub array is zero assets, not one array asset.
        $response = Invoke-RestMethod -Uri "$api/releases/$ReleaseId/assets?per_page=100&page=$page" -Headers $headers -TimeoutSec 60
        $part = @($response)
        $all += $part
        if ($part.Count -lt 100) { return $all }
    }
    throw 'RELEASE_TOO_MANY_ASSETS'
}
function Check-Tag {
    $ref=Get-Optional "$api/git/ref/tags/$tag"
    if ($null -eq $ref) { return }
    $object=$ref.object
    for($depth=0;$object.type -eq 'tag' -and $depth -lt 4;$depth++){$object=(Invoke-RestMethod -Uri "$api/git/tags/$($object.sha)" -Headers $headers -TimeoutSec 60).object}
    if ($object.type -ne 'commit' -or $object.sha -ne $Commit) { throw 'RELEASE_TAG_POINTS_TO_DIFFERENT_SOURCE' }
}
function Check-Asset($Asset,$File) {
    if ($Asset.name -ne $File.Name -or [long]$Asset.size -ne $File.Length) { throw "RELEASE_UPLOADED_ASSET_MISMATCH: $($File.Name)" }
    if ($Asset.PSObject.Properties['digest'] -and $Asset.digest -and $Asset.digest -ne "sha256:$($manifest[$File.Name])") { throw "RELEASE_UPLOADED_HASH_MISMATCH: $($File.Name)" }
}
Check-Tag
$release=Get-Optional "$api/releases/tags/$tag"
if ($null -ne $release -and (-not $release.prerelease -or -not ([string]$release.body).Contains($marker))) { throw 'RELEASE_NOT_OWNED_BY_THIS_BUILD' }
if ($null -eq $release) {
    $body=@{tag_name=$tag;target_commitish=$Commit;name=$title;body=$notes;draft=$true;prerelease=$true;make_latest='false'}|ConvertTo-Json -Depth 5
    $release=Invoke-RestMethod -Method Post -Uri "$api/releases" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 60
}
$existing=@(Get-Assets $release.id)
if ($release.draft) {
    $upload=([string]$release.upload_url) -replace '\{.*$',''
    if ($upload -ne "https://uploads.github.com/repos/$repo/releases/$($release.id)/assets") { throw 'RELEASE_UPLOAD_HOST_MISMATCH' }
    foreach($file in $files) {
        $same=@($existing|Where-Object name -CEQ $file.Name)
        if ($same.Count -gt 1) { throw 'RELEASE_DUPLICATE_REMOTE_ASSET' }
        if ($same.Count -eq 1) {
            # Only unfinished assets in this build's own draft can be replaced.
            Invoke-RestMethod -Method Delete -Uri "$api/releases/assets/$($same[0].id)" -Headers $headers -TimeoutSec 60 | Out-Null
        }
        $uploaded=Invoke-RestMethod -Method Post -Uri ($upload+'?name='+[Uri]::EscapeDataString($file.Name)) -Headers $headers -ContentType 'application/octet-stream' -InFile $file.FullName -TimeoutSec 600
        Check-Asset $uploaded $file
        Write-Output "Uploaded individual file: $($file.Name)"
    }
}
# Do not modify an already-published release. Verify it, or refuse on mismatch.
$actual=@(Get-Assets $release.id)
if ($actual.Count -ne $files.Count) { throw 'RELEASE_UNEXPECTED_REMOTE_ASSETS' }
foreach($file in $files){$match=@($actual|Where-Object name -CEQ $file.Name);if($match.Count -ne 1){throw 'RELEASE_ASSET_SET_INCOMPLETE'};Check-Asset $match[0] $file}
Check-Tag
if ($release.draft) {
    $release=Invoke-RestMethod -Method Patch -Uri "$api/releases/$($release.id)" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body (@{draft=$false;prerelease=$true;body=$notes;name=$title;make_latest='false'}|ConvertTo-Json -Depth 5) -TimeoutSec 60
}
Check-Tag
if ($release.draft -or -not $release.prerelease) { throw 'RELEASE_NOT_PUBLISHED_AS_PRERELEASE' }
Write-Output "RELEASE_URL=$($release.html_url)"
if ($env:GITHUB_OUTPUT) { "release-url=$($release.html_url)" >> $env:GITHUB_OUTPUT }
if ($env:GITHUB_STEP_SUMMARY) { "## DivZero 独立文件下载`n[打开 Releases 下载 JAR]($($release.html_url))`n未上传整合 ZIP；已校验 $($files.Count) 个独立附件。" >> $env:GITHUB_STEP_SUMMARY }
