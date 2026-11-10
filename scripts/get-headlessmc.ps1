# Makes sure the newest HeadlessMC launcher-wrapper jar is in .\HeadlessMC and prints its file name.
# Falls back to whatever jar is already there if GitHub can't be reached.
param([string]$Dir = (Join-Path $PSScriptRoot '..\HeadlessMC'))

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$existing = Get-ChildItem $Dir -Filter 'headlessmc-launcher-wrapper-*.jar' | Sort-Object LastWriteTime -Descending

try {
    # Newest release that still ships the 2.x launcher-wrapper jar. HeadlessMC 3.x is a rewrite
    # (single headlessmc.jar, different CLI) that this setup hasn't been tested with.
    $releases = Invoke-RestMethod 'https://api.github.com/repos/headlesshq/headlessmc/releases?per_page=50' `
        -Headers @{ 'User-Agent' = 'partyfiller-launcher' } -TimeoutSec 15
    $asset = $null
    foreach ($rel in $releases) {
        if ($rel.prerelease -or $rel.draft) { continue }
        $asset = $rel.assets | Where-Object { $_.name -match '^headlessmc-launcher-wrapper-[\d.]+\.jar$' } | Select-Object -First 1
        if ($asset) { break }
    }
    if (-not $asset) { throw 'no release with a launcher-wrapper jar found' }

    $target = Join-Path $Dir $asset.name
    if (-not (Test-Path $target) -or (Get-Item $target).Length -ne $asset.size) {
        [Console]::Error.WriteLine("Downloading $($asset.name) ...")
        $tmp = "$target.part"
        Invoke-WebRequest $asset.browser_download_url -OutFile $tmp -UseBasicParsing
        if ((Get-Item $tmp).Length -ne $asset.size) { Remove-Item $tmp; throw 'download size mismatch' }
        Move-Item $tmp $target -Force
        # remove older wrapper jars
        Get-ChildItem $Dir -Filter 'headlessmc-launcher-wrapper-*.jar' | Where-Object { $_.Name -ne $asset.name } | Remove-Item
    }
    Write-Output $asset.name
}
catch {
    if ($existing) {
        [Console]::Error.WriteLine("Could not check for HeadlessMC updates ($($_.Exception.Message)); using $($existing[0].Name)")
        Write-Output $existing[0].Name
    } else {
        [Console]::Error.WriteLine("Could not download HeadlessMC: $($_.Exception.Message)")
        exit 1
    }
}
