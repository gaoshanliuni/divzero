# Offline main-JAR fixture + real pinned dependency downloads; never publishes a Release.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$buildVersion=& (Join-Path $PSScriptRoot 'get-build-version.ps1')
& (Join-Path $PSScriptRoot 'test-build-version.ps1')
if ($buildVersion -notmatch '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$' -or $buildVersion.EndsWith('-SNAPSHOT')) { throw 'BUILD_VERSION_INVALID' }
$oldVersion=$env:DIVZERO_BUILD_VERSION
try {
    $env:DIVZERO_BUILD_VERSION=$buildVersion
    $resolved=& (Join-Path $PSScriptRoot 'get-release-versions.ps1')
    if ($resolved.modVersion -cne $buildVersion) { throw 'BUILD_VERSION_OVERRIDE_NOT_USED' }
} finally { $env:DIVZERO_BUILD_VERSION=$oldVersion }
Write-Output "SOURCE_BUILD_VERSION_TEST_PASSED=$buildVersion"
# Reproduce PowerShell REST array semantics without network writes or credentials.
& {
    $tokens=$null; $errors=$null
    $ast=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'publish-ci-release.ps1'),[ref]$tokens,[ref]$errors)
    $function=$ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-Assets'},$true)
    . ([scriptblock]::Create($function.Extent.Text))
    $api='https://example.invalid';$headers=@{}
    function Invoke-RestMethod {
        param($Uri,$Headers,$TimeoutSec)
        $data = switch ($mode) {
            'empty' { @() }
            'single' { [pscustomobject]@{name='one.jar'} }
            'paged' {
                if ($Uri.EndsWith('page=1')) { 1..100 | ForEach-Object { [pscustomobject]@{name="$_ .jar"} } }
                else { [pscustomobject]@{name='last.jar'} }
            }
        }
        Write-Output -NoEnumerate ([object[]]@($data))
    }
    foreach ($case in @(@{mode='empty';count=0},@{mode='single';count=1},@{mode='paged';count=101})) {
        $mode=$case.mode
        $result=@(Get-Assets 42)
        if ($result.Count -ne $case.count) { throw "REST_ARRAY_TEST_FAILED: $mode count=$($result.Count)" }
        foreach ($asset in $result) { if (-not $asset.PSObject.Properties['name']) { throw 'REST_ARRAY_WAS_NOT_FLATTENED' } }
        Write-Output "REST_ARRAY_TEST_PASSED=$mode"
    }
}
# Download reachability is a read-only check. Simulate 200/404/network failure
# locally; the actual workflow must additionally check the real public assets.
& {
    $tokens=$null; $errors=$null
    $ast=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'publish-ci-release.ps1'),[ref]$tokens,[ref]$errors)
    $function=$ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Check-PublishedDownload'},$true)
    . ([scriptblock]::Create($function.Extent.Text))
    $repo='gaoshanliuni/divzero';$tag='test-only'
    function Invoke-WebRequest {
        param($Uri,$Method,[switch]$SkipHttpErrorCheck,$TimeoutSec)
        if ($Method -ne 'Head' -or $args.Count) { throw 'UNEXPECTED_DOWNLOAD_CHECK_PARAMETERS' }
        if ($mode -eq 'network') { throw 'simulated offline' }
        [pscustomobject]@{StatusCode=[int]$mode}
    }
    function Start-Sleep { param($Seconds) }
    $asset=[pscustomobject]@{name='test.jar';browser_download_url="https://github.com/$repo/releases/download/$tag/test.jar"}
    foreach ($mode in @('200','404','network')) {
        $rejected=$false
        try { Check-PublishedDownload $asset | Out-Null } catch {
            if ($_.Exception.Message -notlike 'RELEASE_PUBLIC_DOWNLOAD_FAILED:*') { throw }
            $rejected=$true
        }
        if (($mode -eq '200') -eq $rejected) { throw "DOWNLOAD_CHECK_TEST_FAILED: $mode" }
        Write-Output "PUBLIC_DOWNLOAD_TEST_PASSED=$mode"
    }
}
# Runtime-only publication must exclude metadata, sources/API and the redundant online MCEF.
& {
    $tokens=$null; $errors=$null
    $ast=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'publish-ci-release.ps1'),[ref]$tokens,[ref]$errors)
    if ($errors.Count) { throw 'PUBLISH_SCRIPT_PARSE_FAILED' }
    $function=$ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Select-RuntimeFiles'},$true)
    . ([scriptblock]::Create($function.Extent.Text))
    $platforms=[ordered]@{}
    foreach ($platform in @('windows_amd64','linux_amd64','macos_amd64','macos_arm64')) { $platforms[$platform]=[pscustomobject]@{file="mcef-offline-neoforge-$platform.jar"} }
    $info=[pscustomobject]@{jar='DivZero-test.jar';offlineMcef=[pscustomobject]@{platforms=[pscustomobject]$platforms}}
    $names=@('DivZero-test.jar','webgui-neoforge-1.6.2+mc26.1.2.jar','mcef_neoforge_2.2.0_MC_26.1.1.jar','mcef-offline-corresponding-sources.jar','mcef-offline-neoforge-api.jar','BUILD-INFO.json','LICENSE-DIVZERO.txt','SHA256SUMS')+@($platforms.Values | ForEach-Object file)
    $all=@($names | ForEach-Object { [pscustomobject]@{Name=$_} });$hashes=[Collections.Generic.Dictionary[string,string]]::new()
    foreach($name in $names){$hashes.Add($name,'0'*64)}
    $selected=@(Select-RuntimeFiles $info $all $hashes)
    if($selected.Count -ne 6 -or @($selected | Where-Object { $_.Name -match 'sources|neoforge-api|json|txt|SUMS|mcef_neoforge' }).Count){throw 'RUNTIME_ONLY_SELECTION_FAILED'}
    $info.jar='mcef-offline-corresponding-sources.jar';$rejected=$false
    try { Select-RuntimeFiles $info $all $hashes | Out-Null } catch { if($_.Exception.Message -ne 'RELEASE_RUNTIME_JAR_REQUIRED'){throw};$rejected=$true }
    if(-not $rejected){throw 'SOURCE_JAR_WAS_PUBLISHABLE_AS_MAIN'}
    Write-Output 'RUNTIME_ONLY_OFFLINE_MCEF_SELECTION_PASSED=6'
}
$fixture = Join-Path $root ('build/ci-release-test-' + [Guid]::NewGuid().ToString('N'))
foreach ($dir in @('scripts','docs/licenses','neoforge/build/libs')) { New-Item -ItemType Directory -Path (Join-Path $fixture $dir) -Force | Out-Null }
foreach ($script in @('get-build-version.ps1','get-release-versions.ps1','package-ci-artifact.ps1','stage-browser-dependencies.ps1','publish-ci-release.ps1')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $script) -Destination (Join-Path $fixture "scripts/$script") }
foreach ($file in @('gradle.properties','build.gradle','neoforge/build.gradle','LICENSE','docs/THIRD_PARTY_NOTICES.md','docs/licenses/WebGUI-MIT.txt')) { Copy-Item -LiteralPath (Join-Path $root $file) -Destination (Join-Path $fixture $file) }
$props = Get-Content -LiteralPath (Join-Path $root 'gradle.properties') -Raw | ConvertFrom-StringData
$props.mod_version=$buildVersion
$env:DIVZERO_BUILD_VERSION=$buildVersion
$metadata = [IO.File]::ReadAllText((Join-Path $root 'neoforge/src/main/templates/META-INF/neoforge.mods.toml'))
foreach ($key in $props.Keys) { $metadata = $metadata.Replace('${'+$key+'}',[string]$props[$key]) }
$jar = Join-Path $fixture 'neoforge/build/libs/TEST-ONLY-NOT-INSTALLABLE.jar'
$archive = [IO.Compression.ZipFile]::Open($jar,[IO.Compression.ZipArchiveMode]::Create)
try {
    $writer = [IO.StreamWriter]::new($archive.CreateEntry('META-INF/neoforge.mods.toml').Open())
    try { $writer.Write($metadata) } finally { $writer.Dispose() }
    [void]$archive.CreateEntry('META-INF/mineagent/worker/mineagent-worker.jar')
    [void]$archive.CreateEntry('LICENSE-DIVZERO.txt')
    [void]$archive.CreateEntry('META-INF/DIVZERO-THIRD-PARTY-NOTICES.md')
} finally { $archive.Dispose() }
$oldRun=$env:GITHUB_RUN_ID; $oldAttempt=$env:GITHUB_RUN_ATTEMPT; $oldOutput=$env:GITHUB_OUTPUT
$commit='a'*40
function Expect-Failure([string]$Expected,[scriptblock]$Action) {
    try { & $Action | Out-Null } catch { if ($_.Exception.Message -like "*$Expected*") { Write-Output "EXPECTED_REJECTION=$Expected"; return }; throw }
    throw "NEGATIVE_TEST_DID_NOT_REJECT: $Expected"
}
try {
    $env:GITHUB_RUN_ID='123'; $env:GITHUB_RUN_ATTEMPT='1'; $env:GITHUB_OUTPUT=$null
    & (Join-Path $fixture 'scripts/package-ci-artifact.ps1') -Commit $commit
    $assets=Join-Path $fixture 'build/ci-artifacts'
    $publish=Join-Path $fixture 'scripts/publish-ci-release.ps1'
    $result=& $publish -Commit $commit -AssetDirectory $assets -DryRun
    if (-not ($result -match 'RELEASE_TITLE=DivZero .* · Minecraft ') -or -not ($result -match 'VERIFIED_FILES=12') -or -not ($result -match 'PUBLISHED_JARS=3')) { throw 'DRY_RUN_SUMMARY_MISSING' }
    $notes=$result -join "`n"
    if ($notes -notmatch '下载附件（Assets）' -or @($result | Where-Object { $_ -like 'PUBLISH_JAR=*' -and $_ -match 'sources|LICENSE|json|md|txt' }).Count) { throw 'RELEASE_NOTES_MUST_USE_ASSETS_SECTION' }
    $result | Where-Object { $_ -match '^(RELEASE_TITLE|VERIFIED_FILES)=' }
    Expect-Failure 'CI_ARTIFACT_DIRECTORY_NOT_EMPTY' { & (Join-Path $fixture 'scripts/package-ci-artifact.ps1') -Commit $commit }
    Expect-Failure 'RELEASE_ASSET_PATH_BOUNDARY' { & $publish -Commit $commit -AssetDirectory $fixture -DryRun }
    $extra=Join-Path $assets 'unexpected.zip'; [IO.File]::WriteAllText($extra,'test')
    try { Expect-Failure 'RELEASE_UNEXPECTED_FILES' { & $publish -Commit $commit -AssetDirectory $assets -DryRun } } finally { Remove-Item -LiteralPath $extra }
    $readme=Join-Path $assets 'README.txt';$original=[IO.File]::ReadAllBytes($readme)
    try { [IO.File]::AppendAllText($readme,'tampered'); Expect-Failure 'RELEASE_CHECKSUM_MISMATCH' { & $publish -Commit $commit -AssetDirectory $assets -DryRun } } finally { [IO.File]::WriteAllBytes($readme,$original) }
    $infoPath=Join-Path $assets 'BUILD-INFO.json';$original=[IO.File]::ReadAllBytes($infoPath)
    try {
        $info=Get-Content -LiteralPath $infoPath -Raw | ConvertFrom-Json; $info.modVersion='999.0'
        $info | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $infoPath -Encoding utf8NoBOM
        Expect-Failure 'RELEASE_VERSION_MISMATCH' { & $publish -Commit $commit -AssetDirectory $assets -DryRun }
    } finally { [IO.File]::WriteAllBytes($infoPath,$original) }
    # Reject metadata mismatches even if the file is named like a valid Mod.
    $archive=[IO.Compression.ZipFile]::Open($jar,[IO.Compression.ZipArchiveMode]::Update)
    try {
        $archive.GetEntry('META-INF/neoforge.mods.toml').Delete()
        $writer=[IO.StreamWriter]::new($archive.CreateEntry('META-INF/neoforge.mods.toml').Open())
        try { $writer.Write($metadata.Replace('version="'+$props.mod_version+'"','version="999.0"')) } finally { $writer.Dispose() }
    } finally { $archive.Dispose() }
    Expect-Failure 'CI_PACKAGED_VERSION_MISMATCH' { & (Join-Path $fixture 'scripts/package-ci-artifact.ps1') -Commit $commit -OutputDirectory 'build/rejected' }
    Write-Output 'CI_RELEASE_PACKAGING_TESTS_PASSED (synthetic main JAR; no release written)'
} finally {
    $env:GITHUB_RUN_ID=$oldRun; $env:GITHUB_RUN_ATTEMPT=$oldAttempt; $env:GITHUB_OUTPUT=$oldOutput; $env:DIVZERO_BUILD_VERSION=$oldVersion
    # Keep diagnostic fixtures under build; never stage or publish them.
}
