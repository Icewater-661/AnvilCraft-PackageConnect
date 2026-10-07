# SPDX-License-Identifier: MIT
# Parts of this script were generated with AI assistance using OpenAI Codex.
param(
    [Parameter(Mandatory = $true)]
    [string]$ModsDirectory
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectDirectory = Split-Path -Parent $PSScriptRoot
$dependencyDirectory = Join-Path $projectDirectory 'libs'
$resolvedModsDirectory = (Resolve-Path -LiteralPath $ModsDirectory).Path
$modFiles = Get-ChildItem -LiteralPath $resolvedModsDirectory -File -Filter '*.jar'
$createFiles = @($modFiles | Where-Object Name -Match '^create-\d.*\.jar$')
$anvilFiles = @($modFiles | Where-Object Name -Match 'anvilcraft-neoforge-.*\.jar$')
if ($createFiles.Count -ne 1 -or $anvilFiles.Count -ne 1) {
    throw 'Expected exactly one Create JAR and one AnvilCraft JAR in the supplied mods directory.'
}
New-Item -ItemType Directory -Path $dependencyDirectory -Force | Out-Null
if (@(Get-ChildItem -LiteralPath $dependencyDirectory -Filter '*.jar' -File).Count -gt 0) {
    throw 'libs already contains JAR files. Use an empty libs directory to avoid mixing dependency versions.'
}

$pendingArchives = [Collections.Generic.Queue[string]]::new()
$seenArchives = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($modFile in @($createFiles[0], $anvilFiles[0])) {
    $destination = Join-Path $dependencyDirectory $modFile.Name
    Copy-Item -LiteralPath $modFile.FullName -Destination $destination
    $pendingArchives.Enqueue($destination)
    [void]$seenArchives.Add($modFile.Name)
}
while ($pendingArchives.Count -gt 0) {
    $archivePath = $pendingArchives.Dequeue()
    $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        foreach ($entry in $archive.Entries | Where-Object FullName -Match '^META-INF/jarjar/.*\.jar$') {
            $fileName = [IO.Path]::GetFileName($entry.FullName)
            if (-not $seenArchives.Add($fileName)) { continue }
            $destination = Join-Path $dependencyDirectory $fileName
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $false)
            $pendingArchives.Enqueue($destination)
        }
    } finally {
        $archive.Dispose()
    }
}
Write-Output ('Prepared {0} dependency JARs in {1}' -f $seenArchives.Count, $dependencyDirectory)
