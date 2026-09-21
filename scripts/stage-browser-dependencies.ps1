param([Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out = [IO.Path]::GetFullPath($OutputDirectory, $root)
if (-not $out.StartsWith((Join-Path $root 'build') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'DEPENDENCY_OUTPUT_BOUNDARY' }
New-Item -ItemType Directory -Path $out -Force | Out-Null
$build = [IO.File]::ReadAllText((Join-Path $root 'neoforge/build.gradle'))
$dependencies = @(
    @{Name='webgui-neoforge-1.6.2+mc26.1.2.jar'; Role='runtime'; Project='tDw1xQja'; Version='SVU4eUap'; Algorithm='SHA256'; Hash='fd7feec4c60b74c9c6fc0d831fa0599267a2204353b9958e36649351a90dd7b0'; Url='https://cdn.modrinth.com/data/tDw1xQja/versions/SVU4eUap/webgui-neoforge-1.6.2%2Bmc26.1.2.jar'; Source='https://github.com/mc-webgui/webgui/tree/v1.6.2'; License='MIT'},
    @{Name='mcef_neoforge_2.2.0_MC_26.1.1.jar'; Role='runtime'; Project='bQhBuv7x'; Version='h38n5aI0'; Algorithm='SHA256'; Hash='7043d21c4deaf4149401aa4d1761b73ca63386874c0c5ce1fa80d6ae601e65d0'; Url='https://cdn.modrinth.com/data/bQhBuv7x/versions/h38n5aI0/mcef_neoforge_2.2.0_MC_26.1.1.jar'; Source='https://github.com/Keksuccino/mcef/'; License='LGPL-2.1-or-later'},
    @{Name='mcef-2.2.0-neoforge-sources.jar'; Role='sources-not-for-mods'; Project='bQhBuv7x'; Version='h38n5aI0'; Algorithm='SHA512'; Hash='837f34c0e1fa348a8c4c59ea59bbda36187d943c7d662bea31f8759d90680f99af8505d6e634ad309d429e92f71a4c18fd4dfc00fc869e5b2c67889ef7991359'; Url='https://cdn.modrinth.com/data/bQhBuv7x/versions/h38n5aI0/sources_mcef_neoforge_2.2.0_MC_26.1.1.jar'; Source='https://github.com/Keksuccino/mcef/'; License='LGPL-2.1-or-later'}
)
# Refuse to distribute old browser binaries after the build's dependency lock changes.
foreach ($dependency in $dependencies | Where-Object Role -eq 'runtime') {
    if (-not $build.Contains("webguiLocked('maven.modrinth:$($dependency.Project):$($dependency.Version)')") -or
        -not $build.Contains("'$($dependency.Project)-$($dependency.Version).jar': '$($dependency.Hash)'")) {
        throw 'BROWSER_RELEASE_LOCK_DOES_NOT_MATCH_BUILD'
    }
}
$records = @()
foreach ($dependency in $dependencies) {
    $target = Join-Path $out $dependency.Name
    if (Test-Path -LiteralPath $target) { throw 'BROWSER_RELEASE_FILE_ALREADY_EXISTS' }
    $temp = Join-Path $out ([Guid]::NewGuid().ToString() + '.download')
    try {
        for ($attempt=1; $attempt -le 3; $attempt++) {
            try { Invoke-WebRequest -Uri $dependency.Url -OutFile $temp -TimeoutSec 180 -Headers @{'User-Agent'='DivZero release dependency staging'}; break }
            catch { if ($attempt -eq 3) { throw }; Start-Sleep -Seconds (2 * $attempt) }
        }
        $actual = (Get-FileHash -LiteralPath $temp -Algorithm $dependency.Algorithm).Hash.ToLowerInvariant()
        if ($actual -ne $dependency.Hash) { throw "BROWSER_RELEASE_HASH_MISMATCH: $($dependency.Name)" }
        Move-Item -LiteralPath $temp -Destination $target
        $records += [ordered]@{file=$dependency.Name;role=$dependency.Role;project=$dependency.Project;versionId=$dependency.Version;upstream=$dependency.Url;source=$dependency.Source;license=$dependency.License;sha256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()}
    } finally { if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp } }
}
$archive = [IO.Compression.ZipFile]::OpenRead((Join-Path $out 'mcef_neoforge_2.2.0_MC_26.1.1.jar'))
try {
    $entry=$archive.GetEntry('LICENSE_MCEF (Minecraft Chromium Embedded Framework)')
    if ($null -eq $entry) { throw 'MCEF_LICENSE_MISSING' }
    $input=$entry.Open();$output=[IO.File]::Create((Join-Path $out 'LICENSE-MCEF.txt'))
    try { $input.CopyTo($output) } finally { $input.Dispose();$output.Dispose() }
} finally { $archive.Dispose() }
Copy-Item -LiteralPath (Join-Path $root 'docs/licenses/WebGUI-MIT.txt') -Destination (Join-Path $out 'LICENSE-WebGUI.txt')
[IO.File]::WriteAllText((Join-Path $out 'DEPENDENCIES.json'), (ConvertTo-Json -InputObject @($records) -Depth 5), [Text.UTF8Encoding]::new($false))
Write-Output 'Verified original WebGUI/MCEF JARs, corresponding MCEF sources and license notices.'
