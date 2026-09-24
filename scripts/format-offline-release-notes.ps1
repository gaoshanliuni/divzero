param(
    [Parameter(Mandatory)][string]$OriginalNotes,
    [Parameter(Mandatory)]$BuildInfo,
    [Parameter(Mandatory)]$Checksums
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lock=Get-Content -LiteralPath (Join-Path $root 'gradle/mcef-offline.lock.json') -Raw | ConvertFrom-Json
if($BuildInfo.offlineMcef.tag -ne $lock.tag -or $BuildInfo.offlineMcef.sourceCommit -ne $lock.sourceCommit) { throw 'RELEASE_OFFLINE_MCEF_LOCK_MISMATCH' }
$items=@($lock.sources)+@($lock.notices)+@($lock.manifest)+@($lock.platforms.PSObject.Properties|ForEach-Object Value)
foreach($item in $items) {
    if(-not $Checksums.ContainsKey([string]$item.file) -or $Checksums[[string]$item.file] -ne $item.sha256) { throw "RELEASE_OFFLINE_MCEF_ASSET_MISMATCH: $($item.file)" }
}
$notes=$OriginalNotes.Replace('展开后分别下载以下三个 JAR。','展开后下载主模组、WebGUI，再从下面的 MCEF 方案中选择一个。')
$notes=$notes.Replace('| mcef_neoforge_2.2.0_MC_26.1.1.jar | MCEF |','| mcef-offline-neoforge-<platform>.jar | 推荐：与你的系统和 Java 架构匹配的离线 MCEF，只选一个 |')
$notes=$notes.Replace('下载后将以上三个文件放入客户端 mods。','下载后将主模组、WebGUI 和选定的一个 MCEF 放入客户端 mods。')
$notes=$notes.Replace('MCEF 使用固定版本的兼容工件，校验值随构建验证。','MCEF Offline 使用固定 Release 的 API 与运行包，版本和 SHA-256 由源码中的 lock 文件管理。')
$notes=$notes.Replace('Chromium/JCEF 运行库由 MCEF 配置准备；YSM 模型由用户单独安装。','离线 MCEF 附件内置各自平台的 Chromium/JCEF 运行库，安装、校验和修复在本地完成；在线网页与 AI API 使用网络。YSM 模型由用户单独安装。')
$extra=@"

### 离线 MCEF：按平台选择一个附件

| 系统 / Java 架构 | 离线附件 |
| --- | --- |
| Windows x64 | mcef-offline-neoforge-windows_amd64.jar |
| Linux x64 | mcef-offline-neoforge-linux_amd64.jar |
| macOS Intel | mcef-offline-neoforge-macos_amd64.jar |
| macOS Apple Silicon | mcef-offline-neoforge-macos_arm64.jar |

**每个实例选择一个与 Minecraft 所用 Java 架构一致的离线 MCEF 平台包。**
原生 Windows/Linux ARM64 列入后续平台适配。

离线分支版本：$($lock.tag)。[离线分支源码](https://github.com/$($lock.repository)/commit/$($lock.sourceCommit))。
[完整对应源码](https://github.com/$($lock.repository)/releases/download/$($lock.tag)/$($lock.sources.file))与[许可声明](https://github.com/$($lock.repository)/releases/download/$($lock.tag)/$($lock.notices.file))用于源码阅读、构建与许可核对。
平台打包、安装器与具体游戏渲染分别记录验证范围，详见功能说明。
"@
Write-Output ($notes+$extra)
