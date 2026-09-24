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
$required=@([string]$info.jar,'webgui-neoforge-1.6.2+mc26.1.2.jar','mcef_neoforge_2.2.0_MC_26.1.1.jar','mcef-2.2.0-neoforge-sources.jar','LICENSE-WebGUI.txt','LICENSE-MCEF.txt','LICENSE-DIVZERO.txt','DEPENDENCIES.json','README.txt','THIRD-PARTY-NOTICES.md','BUILD-INFO.json')
foreach($name in $required) { if (-not $manifest.ContainsKey($name)) { throw "RELEASE_REQUIRED_FILE_MISSING: $name" } }
if ($manifest[[string]$info.jar] -ne $info.sha256) { throw 'RELEASE_MAIN_JAR_HASH_MISMATCH' }
$manifest.Add('SHA256SUMS',(Get-FileHash -LiteralPath (Join-Path $assets 'SHA256SUMS') -Algorithm SHA256).Hash.ToLowerInvariant())
$short=$Commit.Substring(0,12)
$tag=[string]$versions.modVersion
$title=[string]$versions.modVersion
$marker="<!-- divzero-version-release-v1 version=$($versions.modVersion) commit=$Commit variant=$Variant -->"
$notes=@"
$marker
## $title

| 支持环境 | 版本 |
| --- | --- |
| Mod | $($versions.modVersion) |
| Minecraft | $($versions.minecraftVersion)（声明范围：$($versions.minecraftVersionRange)） |
| NeoForge 构建版本 | $($versions.neoForgeVersion) |
| Java | $($versions.requiredJavaVersion) |

开发测试版，网络协议 6；客户端与服务端同步更新。
源码：[$short](https://github.com/$repo/commit/$Commit) · [构建记录](https://github.com/$repo/actions/runs/$run)

### 功能与入口

- **/ai create "星河"** 创建 AI，**/ai accept** 初始化本人权限；原生面板默认 Ctrl+M，Web 工作区默认 F2。
- API URL／模型／Key：Ctrl+M → 模型；F2 → 更多 → API 设置。两个入口均可配置模型，F2 的 Key 按钮打开本机保密输入。
- 默认跟随游戏语言，F2 → 更多 → 语言 / 原生面板 → 语言可手动覆盖。
- 流式对话、排队／打断／取消、对话删除、本人默认响应 AI、创建者响应许可。
- AI 真实持物交互、低净空潜行、永久桥梁／台阶、跳跃搭高与可复用梯子；放置／破坏／使用分别设置。
- 生物整体网格动画、物品／生物预览、建模、建筑导入、PNG 换肤与 Java 管理的 Python。

[全部功能与命令](https://github.com/gaoshanliuni/divzero/blob/$Commit/docs/FEATURES.md) · [功能截图](https://github.com/gaoshanliuni/divzero/blob/$Commit/docs/images/README.md)

### 下载附件（Assets）

**安装包已作为本 Release 的附件上传。请向下滚动到 Assets（资源），展开后分别下载以下三个 JAR。**
正文不提供另行拼接的下载超链接；直接使用 GitHub 附件区的下载按钮。

| 必装附件文件名 | 用途 |
| --- | --- |
| $($info.jar) | DivZero 主模组 |
| webgui-neoforge-1.6.2+mc26.1.2.jar | WebGUI |
| mcef_neoforge_2.2.0_MC_26.1.1.jar | MCEF |

下载后将以上三个文件放入客户端 mods。名称带 sources 的工件用于源码阅读和开发。

请使用上述 Minecraft / NeoForge / Java 版本。关闭游戏并备份存档，每个实例保留一份主模组、WebGUI 和所选 MCEF。
MCEF 使用固定版本的兼容工件，校验值随构建验证。
Chromium/JCEF 运行库由 MCEF 配置准备；YSM 模型由用户单独安装。

校验值直接列在正文；完整构建信息保留在本次 Actions 工件，不作为 Release 附件。主 JAR 内含 DivZero 许可及第三方说明，依赖保留自身许可。
[MCEF 对应开发源码](https://cdn.modrinth.com/data/bQhBuv7x/versions/h38n5aI0/sources_mcef_neoforge_2.2.0_MC_26.1.1.jar)供开发者阅读和构建；许可及来源见[第三方声明](https://github.com/gaoshanliuni/divzero/blob/$Commit/docs/THIRD_PARTY_NOTICES.md)。
WebGUI [上游源码](https://github.com/mc-webgui/webgui/tree/v1.6.2)，MCEF [上游源码](https://github.com/Keksuccino/mcef/)。

GitHub 的 Source code (zip/tar.gz) 用于查看和构建源码。游戏安装请使用 Assets 中的运行 JAR。
standard 为标准版；with-media 附带可选 yt-dlp/FFmpeg。
构建记录覆盖编译、打包和选定测试；模型联验与游戏场景见功能说明。
"@
# Keep legacy fixture tests unchanged; real builds with the checked-in offline
# lock must stage and verify the offline attachments before publication.
if (Test-Path -LiteralPath (Join-Path $root 'gradle/mcef-offline.lock.json')) {
    if (-not $info.PSObject.Properties['offlineMcef']) { throw 'RELEASE_OFFLINE_MCEF_NOT_STAGED' }
    $notes = & (Join-Path $PSScriptRoot 'format-offline-release-notes.ps1') -OriginalNotes $notes -BuildInfo $info -Checksums $manifest
}
# All staged files remain verified, but only runtime JARs become public Release assets.
$verifiedCount=$files.Count
function Select-RuntimeFiles($Info,$AllFiles,$Manifest) {
$publishNames=@([string]$Info.jar,'webgui-neoforge-1.6.2+mc26.1.2.jar')
if ($Info.PSObject.Properties['offlineMcef']) {
    $publishNames+=@($Info.offlineMcef.platforms.PSObject.Properties | ForEach-Object { [string]$_.Value.file })
} else { $publishNames+='mcef_neoforge_2.2.0_MC_26.1.1.jar' }
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
$notes+="`nRelease 只上传上表运行 JAR。源码、许可证和构建审计使用正文链接或 JAR 内副本；完整提供对应来源信息。`n"
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
