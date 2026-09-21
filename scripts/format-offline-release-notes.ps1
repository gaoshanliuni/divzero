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
$notes=$notes.Replace('MCEF 的原始文件名标注 MC26.1.1，本项目锁定并测试的兼容工件就是这一版；不修改上游 JAR。','MCEF Offline 基于本项目原有 MC26.1.1 / MCEF 2.2.0 兼容基线。编译依赖来自独立 MCEF-Offline 仓库的固定 Release，版本与 SHA-256 锁定在源码中。')
$notes=$notes.Replace('Chromium/JCEF 原生运行库仍由 MCEF 准备，这里不打包浏览器原生库或 YSM 模型。','离线 MCEF 附件内已包含各自平台的 Chromium/JCEF 运行库，首次本地安装、后续校验和损坏修复均不请求下载站。没有打包 YSM 模型。在线网页和 AI API 仍需要网络。')
$extra=@"

### 离线 MCEF：按平台选择一个附件

| 系统 / Java 架构 | 离线附件 |
| --- | --- |
| Windows x64 | mcef-offline-neoforge-windows_amd64.jar |
| Linux x64 | mcef-offline-neoforge-linux_amd64.jar |
| macOS Intel | mcef-offline-neoforge-macos_amd64.jar |
| macOS Apple Silicon | mcef-offline-neoforge-macos_arm64.jar |

**只安装与你运行 Minecraft 的 Java 架构一致的一个离线 MCEF。不要把多个平台一起装，也不要与原版 MCEF 同时安装。**
本次不提供原生 Windows/Linux ARM64 附件：固定上游对应名称的压缩包实际是 x64 二进制。
原版 mcef_neoforge_2.2.0_MC_26.1.1.jar 仍作为可选在线方案保留，会自行下载运行库；普通玩家推荐上面的离线方案。

离线分支版本：$($lock.tag)。[离线分支源码](https://github.com/$($lock.repository)/commit/$($lock.sourceCommit))。
mcef-offline-corresponding-sources.jar 是本离线分支的完整对应源码，**不是安装文件**；原版 sources 附件仅对应保留的在线方案。
运行库平台打包和安装器测试不等于已在全部平台完成真实游戏渲染验收。
"@
Write-Output ($notes+$extra)
