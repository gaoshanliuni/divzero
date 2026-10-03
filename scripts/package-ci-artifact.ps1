param(
    [ValidateSet('standard', 'with-media')][string]$Variant = 'standard',
    [string]$Commit = '',
    [string]$OutputDirectory = 'build/ci-artifacts'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out = [IO.Path]::GetFullPath($OutputDirectory, $root)
$buildRoot = (Join-Path $root 'build') + [IO.Path]::DirectorySeparatorChar
if (-not $out.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'CI_ARTIFACT_PATH_OUTSIDE_BUILD' }
if ((Test-Path -LiteralPath $out) -and @(Get-ChildItem -LiteralPath $out -Force).Count -gt 0) { throw 'CI_ARTIFACT_DIRECTORY_NOT_EMPTY' }
if (-not $Commit) {
    $Commit = (& git -C $root rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'CI_SOURCE_COMMIT_UNAVAILABLE' }
}
if ($Commit -notmatch '^[0-9a-f]{40}([0-9a-f]{24})?$') { throw 'CI_SOURCE_COMMIT_INVALID' }
$short = $Commit.Substring(0, 12)
$versions = & (Join-Path $PSScriptRoot 'get-release-versions.ps1')
New-Item -ItemType Directory -Path $out -Force | Out-Null
$editionJars = @()
foreach ($edition in @('none','bundled')) {
$jars = @(Get-ChildItem -LiteralPath (Join-Path $root "build/editions/$edition/neoforge/libs") -Filter '*.jar' -File | Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' })
if ($jars.Count -ne 1) { throw "CI_EXPECTED_ONE_INSTALLABLE_JAR: $edition found $($jars.Count)" }
$jar = $jars[0]
$archive = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    foreach ($entry in @('META-INF/neoforge.mods.toml', 'META-INF/mineagent/worker/mineagent-worker.jar', 'LICENSE-DIVZERO.txt', 'META-INF/DIVZERO-THIRD-PARTY-NOTICES.md')) {
        if ($null -eq $archive.GetEntry($entry)) { throw "CI_MISSING_RUNTIME_ENTRY: $entry" }
    }
    $identity=$archive.GetEntry('META-INF/divzero/edition.properties')
    if($null -eq $identity){throw 'CI_EDITION_METADATA_MISSING'}
    $reader=[IO.StreamReader]::new($identity.Open());try{$identityText=$reader.ReadToEnd()}finally{$reader.Dispose()}
    if($identityText -notmatch ('(?m)^python='+$edition+'$')){throw 'CI_EDITION_METADATA_MISMATCH'}
    $payload=$archive.GetEntry('META-INF/divzero/python/runtime.tar.gz')
    if(($edition -eq 'bundled') -ne ($null -ne $payload)){throw 'CI_EDITION_PAYLOAD_MISMATCH'}
    if($edition -eq 'none' -and @($archive.Entries | Where-Object FullName -Match '^dev/mineagent/runtime/(client/host|core/host|neoforge/client/host)/').Count){throw 'CI_NO_PYTHON_RUNTIME_PRESENT'}
    $reader = [IO.StreamReader]::new($archive.GetEntry('META-INF/neoforge.mods.toml').Open())
    try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
    # Match complete table blocks, never a different Mod's version field.
    $tables = @([regex]::Split($metadata, '(?m)(?=^\[\[)'))
    foreach ($expected in @(
        @{table='mods';id=$versions.modId;key='version';value=$versions.modVersion},
        @{table="dependencies.$($versions.modId)";id='minecraft';key='versionRange';value=$versions.minecraftVersionRange},
        @{table="dependencies.$($versions.modId)";id='neoforge';key='versionRange';value="[$($versions.neoForgeVersion),)"}
    )) {
        $blocks = @($tables | Where-Object { $_ -match ('^\[\['+[regex]::Escape($expected.table)+'\]\]') -and $_ -match ('(?m)^modId\s*=\s*"'+[regex]::Escape($expected.id)+'"\s*$') })
        if ($blocks.Count -ne 1 -or $blocks[0] -notmatch ('(?m)^'+$expected.key+'\s*=\s*"'+[regex]::Escape($expected.value)+'"\s*$')) { throw "CI_PACKAGED_VERSION_MISMATCH: $($expected.id)" }
    }
    if($metadata -match '(?m)^modId\s*=\s*"(?:mcef|webgui)"\s*$'){throw 'CI_BROWSER_DEPENDENCY_REINTRODUCED'}
    foreach ($entry in $archive.Entries) {
        if($entry.FullName -match '(org/cef/|com/cinemamod/mcef/|/Mcef[^/]*\.class$|assets/mineagent_runtime/webui/)'){throw 'CI_BROWSER_RUNTIME_REINTRODUCED'}
        if ($entry.FullName -match '(^|/)(saves|logs|mineagent-runtime-data|\.git)/|\.(db|sqlite|log)$') {
            throw 'CI_UNEXPECTED_RUNTIME_DATA_IN_JAR'
        }
    }
} finally { $archive.Dispose() }
$suffix=if($edition -eq 'none'){'no-python'}else{'with-python'}
$name = "DivZero-mineagent-$($versions.modVersion)-$suffix.jar"
$destination = Join-Path $out $name
Copy-Item -LiteralPath $jar.FullName -Destination $destination
$sha = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
$editionJars += [pscustomobject]@{edition=$suffix;jar=$name;sha256=$sha}
}
$name=$editionJars[0].jar;$sha=$editionJars[0].sha256
& (Join-Path $PSScriptRoot 'stage-native-ui-dependencies.ps1') -OutputDirectory $out
Copy-Item -LiteralPath (Join-Path $root 'LICENSE') -Destination (Join-Path $out 'LICENSE-DIVZERO.txt')
Copy-Item -LiteralPath (Join-Path $root 'docs/THIRD_PARTY_NOTICES.md') -Destination (Join-Path $out 'THIRD-PARTY-NOTICES.md')
$info = [ordered]@{
    repository = 'https://github.com/gaoshanliuni/divzero'
    sourceCommit = $Commit
    variant = $Variant
    builtAtUtc = [DateTime]::UtcNow.ToString('o')
    jars = $editionJars
    jar = $name
    sha256 = $sha
    modId = $versions.modId
    modVersion = $versions.modVersion
    minecraftVersion = $versions.minecraftVersion
    minecraftVersionRange = $versions.minecraftVersionRange
    neoForgeVersion = $versions.neoForgeVersion
    requiredJavaVersion = $versions.requiredJavaVersion
    workflowRunId = $env:GITHUB_RUN_ID
    workflowRunAttempt = $env:GITHUB_RUN_ATTEMPT
    dependencies = @(Get-Content -LiteralPath (Join-Path $out 'DEPENDENCIES.json') -Raw | ConvertFrom-Json)
    acceptance = 'Compile, packaged parser integrity and included unit tests; not live model or in-game acceptance.'
}
[IO.File]::WriteAllText((Join-Path $out 'BUILD-INFO.json'), ($info | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
$readme = @"
DivZero $($versions.modVersion) CI build ($Variant)
Minecraft: $($versions.minecraftVersion) (supported range: $($versions.minecraftVersionRange))
NeoForge: $($versions.neoForgeVersion); Java: $($versions.requiredJavaVersion)
Source: https://github.com/gaoshanliuni/divzero/commit/$Commit

Install the main JAR and LDLib2 for the built-in F2 workspace.
- Choose ONE: $($editionJars[0].jar) OR $($editionJars[1].jar)
- Do not install both editions together. The with-python edition bundles full CPython, pinned requests/Pillow/NumPy/colorama, dependency wheels and licenses. First use is offline. The no-python edition removes the runtime executors.
- ldlib2-neoforge-26.1-26.1.2.41.jar
AI-created native interfaces additionally require:
- kubejs-neoforge-26.1.2-8.0.6.jar
- better-advanced-tooltips-2601.1.0-build.9.jar
Rhino is already embedded. Do not install MCEF/WebGUI for this build.
GitHub Source code archives are development sources, not installable Mods.
See https://github.com/gaoshanliuni/divzero/blob/$Commit/docs/NATIVE_INSTALLATION.md
Replace the previous same-Mod JAR in a separate, backed-up test instance.
This is a CI build, not a formal Release or full V1 acceptance.
Standard builds do not include optional Windows media binaries.
SHA-256 is recorded in SHA256SUMS and BUILD-INFO.json.
"@
[IO.File]::WriteAllText((Join-Path $out 'README.txt'), $readme, [Text.UTF8Encoding]::new($false))
$checksums = foreach ($file in Get-ChildItem -LiteralPath $out -File | Sort-Object Name) {
    '{0}  {1}' -f (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $file.Name
}
[IO.File]::WriteAllText((Join-Path $out 'SHA256SUMS'), (($checksums -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
if ($env:GITHUB_OUTPUT) {
    "short-sha=$short" >> $env:GITHUB_OUTPUT
    "variant=$Variant" >> $env:GITHUB_OUTPUT
    "artifact-name=release-files-$Variant-$short-$($env:GITHUB_RUN_ID)-$($env:GITHUB_RUN_ATTEMPT)" >> $env:GITHUB_OUTPUT
}
Write-Output "CI_JAR=$destination"
Write-Output "SHA256=$sha"
