param([string]$ModVersion = $env:DIVZERO_BUILD_VERSION)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$properties = [IO.File]::ReadAllText((Join-Path $root 'gradle.properties'))
function Read-VersionProperty([string]$Name, [string]$Pattern) {
    $matches = [regex]::Matches($properties, '(?m)^' + [regex]::Escape($Name) + '=([^\r\n]+)\r?$')
    if ($matches.Count -ne 1) { throw "RELEASE_VERSION_PROPERTY_MISSING_OR_DUPLICATE: $Name" }
    $value = $matches[0].Groups[1].Value.Trim()
    if ($value -notmatch $Pattern) { throw "RELEASE_VERSION_PROPERTY_INVALID: $Name" }
    return $value
}
$java = [regex]::Matches([IO.File]::ReadAllText((Join-Path $root 'build.gradle')), 'toolchain\.languageVersion\s*=\s*JavaLanguageVersion\.of\((\d+)\)')
if ($java.Count -ne 1) { throw 'RELEASE_JAVA_VERSION_UNAVAILABLE' }
[pscustomobject][ordered]@{
    modId = Read-VersionProperty 'mod_id' '^[a-z][a-z0-9_]*$'
    modVersion = if ($ModVersion) { if ($ModVersion -notmatch '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$') { throw 'RELEASE_BUILD_VERSION_INVALID' }; $ModVersion } else { Read-VersionProperty 'mod_version' '^[0-9][A-Za-z0-9.+_-]*$' }
    minecraftVersion = Read-VersionProperty 'minecraft_version' '^[0-9][A-Za-z0-9.+_-]*$'
    minecraftVersionRange = Read-VersionProperty 'minecraft_version_range' '^[\[\(][A-Za-z0-9.,+ _\[\]\(\)-]+[\]\)]$'
    neoForgeVersion = Read-VersionProperty 'neo_version' '^[0-9][A-Za-z0-9.+_-]*$'
    requiredJavaVersion = [int]$java[0].Groups[1].Value
}
