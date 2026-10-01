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
if (-not $DryRun) {
    $expectedVersion = & (Join-Path $PSScriptRoot 'get-build-version.ps1') -Commit $Commit
    if ($versions.modVersion -cne $expectedVersion) { throw 'RELEASE_NOT_SOURCE_BASED_VERSION' }
}
foreach ($property in $versions.PSObject.Properties) {
    if (-not $info.PSObject.Properties[$property.Name] -or $info.($property.Name) -cne $property.Value) { throw "RELEASE_VERSION_MISMATCH: $($property.Name)" }
}
if ($info.jar -cne "DivZero-mineagent-$($versions.modVersion).jar") { throw 'RELEASE_MAIN_NAME_MISMATCH' }
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
$required=@([string]$info.jar,'ldlib2-neoforge-26.1-26.1.2.41.jar','kubejs-neoforge-26.1.2-8.0.6.jar','better-advanced-tooltips-2601.1.0-build.9.jar','LICENSE-DIVZERO.txt','DEPENDENCIES.json','README.txt','THIRD-PARTY-NOTICES.md','BUILD-INFO.json')
foreach($name in $required) { if (-not $manifest.ContainsKey($name)) { throw "RELEASE_REQUIRED_FILE_MISSING: $name" } }
if ($manifest[[string]$info.jar] -ne $info.sha256) { throw 'RELEASE_MAIN_JAR_HASH_MISMATCH' }
$manifest.Add('SHA256SUMS',(Get-FileHash -LiteralPath (Join-Path $assets 'SHA256SUMS') -Algorithm SHA256).Hash.ToLowerInvariant())
$short=$Commit.Substring(0,12)
$tag=[string]$versions.modVersion
$title=[string]$versions.modVersion
$marker="<!-- divzero-version-release-v1 version=$($versions.modVersion) commit=$Commit variant=$Variant -->"
$changeFile=Join-Path $root "docs/releases/$($versions.modVersion).md"
if (-not (Test-Path -LiteralPath $changeFile -PathType Leaf)) { throw 'RELEASE_CHANGELOG_MISSING' }
$changes=[IO.File]::ReadAllText($changeFile)
$notes=@"
$marker
## $title

Minecraft $($versions.minecraftVersion), NeoForge $($versions.neoForgeVersion), Java $($versions.requiredJavaVersion).
源码：[$short](https://github.com/$repo/commit/$Commit) · [构建记录](https://github.com/$repo/actions/runs/$run)

$changes

### 下载附件（Assets）

| 附件 | 用途 | 是否必需 |
| --- | --- | --- |
| $($info.jar) | DivZero 主模组 | 是 |
| ldlib2-neoforge-26.1-26.1.2.41.jar | F2 与原生 UI / HUD | 是 |
| kubejs-neoforge-26.1.2-8.0.6.jar | AI 动态界面 | 使用 AI 动态界面时 |
| better-advanced-tooltips-2601.1.0-build.9.jar | KubeJS 依赖 | 安装 KubeJS 时 |

内置 F2 只需前两个附件，完整 AI 动态界面安装四个。Rhino 已内嵌。新版本不打包或发行 MCEF / WebGUI，也没有浏览器备用渲染。

### 升级方法

关闭游戏并备份实例，移除旧 DivZero 主 JAR 和为旧版安装的 MCEF / WebGUI，放入所需新附件。客户端与服务端同步升级；保留存档和数据库。旧 HTML/CSS/DOM 内容需显式迁移为原生界面。

首次进入世界点击聊天中的“启用”即可使用。F2 → 设置 → Provider 配置模型；右键 AI 打开专属面板。

[安装说明](https://github.com/$repo/blob/$Commit/docs/BUILD_JAR.md) · [升级状态](https://github.com/$repo/blob/$Commit/docs/NATIVE_UI_MIGRATION_STATUS.md) · [持续技能实测](https://github.com/$repo/blob/$Commit/docs/PERSISTENT_PLAYER_SKILLS.md)
[依赖许可与固定对应源码](https://github.com/$repo/blob/$Commit/docs/THIRD_PARTY_NOTICES.md) · [锁定来源与 SHA-256](https://github.com/$repo/blob/$Commit/scripts/stage-native-ui-dependencies.ps1)

![LDLib2 MC 工作区](https://raw.githubusercontent.com/$repo/$Commit/docs/images/native-workspace.png)
![原生托管面板](https://raw.githubusercontent.com/$repo/$Commit/docs/images/player-takeover.png)

构建通过不代表全部游戏场景验收。GitHub Source code 压缩包不是 Mod 安装包。
"@
# All staged files remain verified, but only runtime JARs become public Release assets.
$verifiedCount=$files.Count
function Select-RuntimeFiles($Info,$AllFiles,$Manifest) {
if (@($AllFiles | Where-Object { $_.Name -match '(?i)(mcef|webgui|jcef)' }).Count) { throw 'RELEASE_BROWSER_FILE_FORBIDDEN' }
$publishNames=@([string]$Info.jar,'ldlib2-neoforge-26.1-26.1.2.41.jar','kubejs-neoforge-26.1.2-8.0.6.jar','better-advanced-tooltips-2601.1.0-build.9.jar')
if (@($publishNames | Sort-Object -Unique).Count -ne $publishNames.Count) { throw 'RELEASE_RUNTIME_LIST_DUPLICATE' }
foreach ($name in $publishNames) {
    if ($name -notmatch '^[A-Za-z0-9][A-Za-z0-9_.+-]*\.jar$' -or $name -match '(sources|javadoc|corresponding|neoforge-api)' -or -not $Manifest.ContainsKey($name)) { throw 'RELEASE_RUNTIME_JAR_REQUIRED' }
}
$selected=@($AllFiles | Where-Object { $_.Name -cin $publishNames })
if ($selected.Count -ne $publishNames.Count) { throw 'RELEASE_RUNTIME_LIST_MISSING' }
    return $selected
}
$files=@(Select-RuntimeFiles $info $files $manifest)
$notes+="`n`n### 运行 JAR 的 SHA-256`n`n| 文件 | SHA-256 |`n|---|---|`n"
foreach ($file in $files) { $notes+='| '+$file.Name+' | `'+$manifest[$file.Name]+'` |'+"`n" }
$notes+="`n主模组和依赖以独立运行 JAR 提供，可选地图另附独立存档 ZIP。源码、许可证和构建审计使用正文链接或 JAR 内副本；完整提供对应来源信息。`n"
if ($DryRun) { Write-Output "RELEASE_DRY_RUN_TAG=$tag"; Write-Output "RELEASE_TITLE=$title"; Write-Output $notes; Write-Output "VERIFIED_FILES=$verifiedCount"; Write-Output "PUBLISHED_JARS=$($files.Count)"; foreach ($file in $files) { Write-Output "PUBLISH_JAR=$($file.Name)" }; return }
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
function Check-PublishedDownload($Asset) {
    # Use GitHub's returned attachment URL, not a handcrafted link. Never send
    # the workflow token to this public request or the redirected download CDN.
    $url = [string]$Asset.browser_download_url
    if (-not $url.StartsWith("https://github.com/$repo/releases/download/$tag/",[StringComparison]::Ordinal)) { throw 'RELEASE_PUBLIC_DOWNLOAD_URL_INVALID' }
    $lastStatus = 'unavailable'
    for ($try=1; $try -le 4; $try++) {
        try {
            $response = Invoke-WebRequest -Uri $url -Method Head -SkipHttpErrorCheck -TimeoutSec 60
            $lastStatus = [string]$response.StatusCode
            if ([int]$response.StatusCode -eq 200) {
                Write-Output "PUBLIC_ATTACHMENT_HTTP_200=$($Asset.name)"
                return
            }
        } catch { $lastStatus = 'network-error' }
        if ($try -lt 4) { Start-Sleep -Seconds (2 * $try) }
    }
    throw "RELEASE_PUBLIC_DOWNLOAD_FAILED: $($Asset.name) status=$lastStatus"
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
# Draft asset URLs use GitHub's temporary untagged reference. Refresh after
# publication before checking the final public attachment addresses.
$publishedAssets = @(Get-Assets $release.id)
if ($publishedAssets.Count -ne $files.Count) { throw 'RELEASE_PUBLIC_ASSET_SET_INCOMPLETE' }
foreach ($asset in $publishedAssets) { Check-PublishedDownload $asset }
Write-Output "RELEASE_URL=$($release.html_url)"
if ($env:GITHUB_OUTPUT) { "release-url=$($release.html_url)" >> $env:GITHUB_OUTPUT }
if ($env:GITHUB_STEP_SUMMARY) { "## DivZero 独立文件下载`n[打开 Releases 下载 JAR]($($release.html_url))`n未上传整合 ZIP；已校验 $($files.Count) 个独立附件。" >> $env:GITHUB_STEP_SUMMARY }
