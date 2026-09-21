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
$jars = @(Get-ChildItem -LiteralPath (Join-Path $root 'neoforge/build/libs') -Filter '*.jar' -File |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' })
if ($jars.Count -ne 1) { throw "CI_EXPECTED_ONE_INSTALLABLE_JAR: found $($jars.Count)" }
$jar = $jars[0]
$archive = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    foreach ($entry in @('META-INF/neoforge.mods.toml', 'META-INF/mineagent/worker/mineagent-worker.jar')) {
        if ($null -eq $archive.GetEntry($entry)) { throw "CI_MISSING_RUNTIME_ENTRY: $entry" }
    }
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -match '(^|/)(saves|logs|mineagent-runtime-data|\.git)/|\.(db|sqlite|log)$') {
            throw 'CI_UNEXPECTED_RUNTIME_DATA_IN_JAR'
        }
    }
} finally { $archive.Dispose() }
New-Item -ItemType Directory -Path $out -Force | Out-Null
$name = "$($jar.BaseName)-$Variant-$short.jar"
$destination = Join-Path $out $name
Copy-Item -LiteralPath $jar.FullName -Destination $destination
$sha = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $out 'SHA256SUMS'), "$sha  $name`n", [Text.UTF8Encoding]::new($false))
$info = [ordered]@{
    repository = 'https://github.com/gaoshanliuni/divzero'
    sourceCommit = $Commit
    variant = $Variant
    builtAtUtc = [DateTime]::UtcNow.ToString('o')
    jar = $name
    sha256 = $sha
    requiredJavaVersion = 25
    acceptance = 'Compile, packaged parser integrity and included unit tests; not live model or in-game acceptance.'
}
[IO.File]::WriteAllText((Join-Path $out 'BUILD-INFO.json'), ($info | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
$readme = @"
DivZero CI build ($Variant)
Source: https://github.com/gaoshanliuni/divzero/commit/$Commit

Install the .jar from this archive, NOT the GitHub source ZIP.
Requires matching Minecraft / NeoForge / Java versions and WebGUI/MCEF dependencies;
see https://github.com/gaoshanliuni/divzero/blob/$Commit/docs/SOURCE_SNAPSHOT.md
Replace the previous same-Mod JAR in a separate, backed-up test instance.
This is a CI build, not a formal Release or full V1 acceptance.
Standard builds do not include optional Windows media binaries.
SHA-256 is recorded in SHA256SUMS and BUILD-INFO.json.
"@
[IO.File]::WriteAllText((Join-Path $out 'README.txt'), $readme, [Text.UTF8Encoding]::new($false))
if ($env:GITHUB_OUTPUT) { "short-sha=$short" >> $env:GITHUB_OUTPUT }
Write-Output "CI_JAR=$destination"
Write-Output "SHA256=$sha"
