# Offline main-JAR fixture + real pinned dependency downloads; never publishes a Release.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixture = Join-Path $root ('build/ci-release-test-' + [Guid]::NewGuid().ToString('N'))
foreach ($dir in @('scripts','docs/licenses','neoforge/build/libs')) { New-Item -ItemType Directory -Path (Join-Path $fixture $dir) -Force | Out-Null }
foreach ($script in @('get-release-versions.ps1','package-ci-artifact.ps1','stage-browser-dependencies.ps1','publish-ci-release.ps1')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $script) -Destination (Join-Path $fixture "scripts/$script") }
foreach ($file in @('gradle.properties','build.gradle','neoforge/build.gradle','LICENSE','docs/THIRD_PARTY_NOTICES.md','docs/licenses/WebGUI-MIT.txt')) { Copy-Item -LiteralPath (Join-Path $root $file) -Destination (Join-Path $fixture $file) }
$props = Get-Content -LiteralPath (Join-Path $root 'gradle.properties') -Raw | ConvertFrom-StringData
$metadata = [IO.File]::ReadAllText((Join-Path $root 'neoforge/src/main/templates/META-INF/neoforge.mods.toml'))
foreach ($key in $props.Keys) { $metadata = $metadata.Replace('${'+$key+'}',[string]$props[$key]) }
$jar = Join-Path $fixture 'neoforge/build/libs/TEST-ONLY-NOT-INSTALLABLE.jar'
$archive = [IO.Compression.ZipFile]::Open($jar,[IO.Compression.ZipArchiveMode]::Create)
try {
    $writer = [IO.StreamWriter]::new($archive.CreateEntry('META-INF/neoforge.mods.toml').Open())
    try { $writer.Write($metadata) } finally { $writer.Dispose() }
    [void]$archive.CreateEntry('META-INF/mineagent/worker/mineagent-worker.jar')
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
    if (-not ($result -match 'RELEASE_TITLE=DivZero .* · Minecraft ') -or -not ($result -match 'VERIFIED_FILES=12')) { throw 'DRY_RUN_SUMMARY_MISSING' }
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
    $env:GITHUB_RUN_ID=$oldRun; $env:GITHUB_RUN_ATTEMPT=$oldAttempt; $env:GITHUB_OUTPUT=$oldOutput
    # Keep diagnostic fixtures under build; never stage or publish them.
}
