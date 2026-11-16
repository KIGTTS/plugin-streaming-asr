#Requires -Version 7.0
param([Parameter(Mandatory)][string]$SigningKey,[string]$AndroidSdk=$env:ANDROID_HOME,[switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$model = Join-Path $PSScriptRoot 'src/main/assets/models/zh14m'
$checksums = Get-Content -LiteralPath (Join-Path $model 'SHA256SUMS')
if ($checksums.Count -ne 4 -or -not (Test-Path -LiteralPath (Join-Path $model 'asr-pack.json'))) {
    throw 'The bundled recognizer must contain its model manifest and all four verified files'
}
foreach ($line in $checksums) {
    if ($line -notmatch '^([0-9a-f]{64})  (?:((?:encoder|decoder|joiner)-[a-z0-9.-]+\.onnx|tokens\.txt))$') {
        throw 'Invalid bundled model checksum entry'
    }
    $file = Join-Path $model $Matches[2]
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $Matches[1]) { throw "Bundled model checksum mismatch: $file" }
}
& (Join-Path $PSScriptRoot '.sdk/tools/Package-Plugin.ps1') -ProjectRoot $PSScriptRoot -SigningKey $SigningKey -AndroidSdk $AndroidSdk -SkipBuild:$SkipBuild
