# Offline version resolution only. No network calls or Release creation.
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$tokens=$null;$errors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'get-build-version.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'VERSION_SCRIPT_PARSE_FAILED'}
$function=$ast.Find({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Resolve-SourceModVersion'},$true)
if(-not $function){throw 'VERSION_RESOLVER_MISSING'}
. ([scriptblock]::Create($function.Extent.Text))
if((Resolve-SourceModVersion "mod_version=1.0.0`n") -cne '1.0.0'){throw 'FIXED_VERSION_NOT_PRESERVED'}
if((Resolve-SourceModVersion "mod_version=1.2.0-beta.3`r`n") -cne '1.2.0-beta.3'){throw 'PRERELEASE_VERSION_NOT_PRESERVED'}
if($null -ne (Resolve-SourceModVersion "mod_version=0.1.0-SNAPSHOT`n")){throw 'SNAPSHOT_NO_LONGER_DYNAMIC'}
foreach($bad in @('',"mod_version=1.0.0`nmod_version=2.0.0",'mod_version=../outside','mod_version=bad version')){
 $rejected=$false;try{Resolve-SourceModVersion $bad|Out-Null}catch{$rejected=$true};if(-not $rejected){throw 'BAD_VERSION_ACCEPTED'}
}
$r=& (Join-Path $PSScriptRoot 'get-release-versions.ps1') -ModVersion '1.0.0'
if($r.modVersion -cne '1.0.0'){throw 'VERSION_OVERRIDE_REJECTED'}
$actual=& (Join-Path $PSScriptRoot 'get-build-version.ps1')
if($actual -notmatch '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$'){throw 'COMMITTED_SOURCE_VERSION_INVALID'}
Write-Output "BUILD_VERSION_TESTS_PASSED=$actual"
