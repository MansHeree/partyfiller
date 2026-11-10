# Gets everything the bot needs, skipping whatever is already up to date:
#   JDK 25 -> HeadlessMC 2.x -> Fabric 26.1.2 -> Modrinth mods -> PartyFiller + NoammAddons builds.
# Everything (Minecraft, Fabric, mods, config) lives in .\instance; the real .minecraft is never touched.
# Prints the HeadlessMC jar name and the java.exe path (two lines) for start.bat.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Root      = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$McVersion = '26.1.2'
$Cache     = Join-Path $Root '.cache'
$HmcDir    = Join-Path $Root 'HeadlessMC'
$Instance  = Join-Path $Root 'instance'
$Mods      = Join-Path $Instance 'mods'
$Config    = Join-Path $Instance 'config'
$NoammRepo = 'https://github.com/MansHeree/NoammAddons.git'
$NoammRef  = '26.1.2'
$Modrinth  = @('fabric-api', 'fabric-language-kotlin', 'packdisabler-for-hypixel-skyblock')
$Headers   = @{ 'User-Agent' = 'partyfiller-setup' }

function Say($msg) { [Console]::Error.WriteLine("[setup] $msg") }
New-Item -ItemType Directory -Force -Path $Cache, $HmcDir, $Mods, $Config | Out-Null

# ---- JDK 25 -----------------------------------------------------------------
function Test-Jdk25($dir) {
    if (-not $dir -or -not (Test-Path (Join-Path $dir 'bin\javac.exe'))) { return $false }
    $rel = Join-Path $dir 'release'
    return (Test-Path $rel) -and ((Get-Content $rel -Raw) -match 'JAVA_VERSION="25')
}
$candidates = @()
$javaPathFile = Join-Path $Root 'java-path.txt'
if (Test-Path $javaPathFile) { $candidates += Split-Path (Split-Path (Get-Content $javaPathFile -TotalCount 1).Trim()) }
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
foreach ($base in "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft",
                  "$env:ProgramFiles\Zulu", "$env:APPDATA\PrismLauncher\java", "$env:USERPROFILE\.jdks") {
    if (Test-Path $base) { $candidates += (Get-ChildItem $base -Directory | ForEach-Object FullName) }
}
$Jdk = $candidates | Where-Object { Test-Jdk25 $_ } | Select-Object -First 1
if (-not $Jdk) { throw 'No JDK 25 found. Install one (e.g. Eclipse Temurin 25) or put the path to its java.exe in java-path.txt.' }
$Java = Join-Path $Jdk 'bin\java.exe'
$env:JAVA_HOME = $Jdk

# ---- HeadlessMC (newest 2.x; 3.x is a rewrite with a different CLI) ----------
$existing = Get-ChildItem $HmcDir -Filter 'headlessmc-launcher-wrapper-*.jar' | Sort-Object LastWriteTime -Descending
try {
    $releases = Invoke-RestMethod 'https://api.github.com/repos/headlesshq/headlessmc/releases?per_page=50' -Headers $Headers -TimeoutSec 15
    $asset = $null
    foreach ($rel in $releases) {
        if ($rel.prerelease -or $rel.draft) { continue }
        $asset = $rel.assets | Where-Object { $_.name -match '^headlessmc-launcher-wrapper-[\d.]+\.jar$' } | Select-Object -First 1
        if ($asset) { break }
    }
    if (-not $asset) { throw 'no release with a launcher-wrapper jar' }
    $target = Join-Path $HmcDir $asset.name
    if (-not (Test-Path $target) -or (Get-Item $target).Length -ne $asset.size) {
        Say "downloading $($asset.name)"
        Invoke-WebRequest $asset.browser_download_url -OutFile "$target.part" -UseBasicParsing
        Move-Item "$target.part" $target -Force
        Get-ChildItem $HmcDir -Filter 'headlessmc-launcher-wrapper-*.jar' | Where-Object Name -ne $asset.name | Remove-Item
    }
    $HmcJar = $asset.name
} catch {
    if (-not $existing) { throw "Could not download HeadlessMC: $($_.Exception.Message)" }
    Say "could not check for HeadlessMC updates, using $($existing[0].Name)"
    $HmcJar = $existing[0].Name
}

# HeadlessMC settings: our game dir + our JDK
$hmcConf = Join-Path $HmcDir 'HeadlessMC\config.properties'
New-Item -ItemType Directory -Force -Path (Split-Path $hmcConf) | Out-Null
$props = [ordered]@{}
if (Test-Path $hmcConf) {
    foreach ($l in Get-Content $hmcConf) { if ($l -match '^\s*([^#=]+?)\s*=(.*)$') { $props[$Matches[1]] = $Matches[2] } }
}
$props['hmc.mcdir'] = $Instance.Replace('\', '/')     # game files, never the real .minecraft
$props['hmc.gamedir'] = $Instance.Replace('\', '/')
$props['hmc.java.versions'] = $Java.Replace('\', '/')
[IO.File]::WriteAllLines($hmcConf, [string[]]($props.Keys | ForEach-Object { "$_=$($props[$_])" }))

# ---- Fabric -------------------------------------------------------------------
$versions = Join-Path $Instance 'versions'
if (-not (Get-ChildItem $versions -Directory -Filter "fabric-loader-*-$McVersion" -ErrorAction SilentlyContinue)) {
    Say "installing Minecraft $McVersion + Fabric (first run only)"
    $in = Join-Path $Cache 'hmc-fabric.txt'
    [IO.File]::WriteAllText($in, "download $McVersion`r`nfabric $McVersion`r`nquit`r`n", [Text.Encoding]::ASCII)
    $log = Join-Path $Cache 'hmc-install.log'
    $p = Start-Process $Java -ArgumentList '--enable-native-access=ALL-UNNAMED', '-jar', $HmcJar -WorkingDirectory $HmcDir `
        -RedirectStandardInput $in -RedirectStandardOutput $log -RedirectStandardError "$log.err" -NoNewWindow -Wait -PassThru
    if (-not (Get-ChildItem $versions -Directory -Filter "fabric-loader-*-$McVersion" -ErrorAction SilentlyContinue)) {
        throw "Fabric install failed, see $log"
    }
}

# ---- Modrinth mods --------------------------------------------------------------
$manifestFile = Join-Path $Mods '.managed.json'
$manifest = @{}
if (Test-Path $manifestFile) { (Get-Content $manifestFile -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $manifest[$_.Name] = $_.Value } }
function Set-ManagedJar($key, $src, $name) {
    $old = $manifest[$key]
    if ($old -and $old -ne $name) { Remove-Item (Join-Path $Mods $old) -ErrorAction SilentlyContinue }
    if ($src) { Copy-Item $src (Join-Path $Mods $name) -Force }
    $manifest[$key] = $name
}
foreach ($slug in $Modrinth) {
    try {
        $q = '?loaders=' + [uri]::EscapeDataString('["fabric"]') + '&game_versions=' + [uri]::EscapeDataString("[`"$McVersion`"]")
        $v = Invoke-RestMethod "https://api.modrinth.com/v2/project/$slug/version$q" -Headers $Headers -TimeoutSec 15 | Select-Object -First 1
        if (-not $v) { throw "no $McVersion version" }
        $file = $v.files | Where-Object primary | Select-Object -First 1
        if (-not $file) { $file = $v.files[0] }
        if (-not (Test-Path (Join-Path $Mods $file.filename))) {
            Say "downloading $($file.filename)"
            $tmp = Join-Path $Cache $file.filename
            Invoke-WebRequest $file.url -OutFile $tmp -UseBasicParsing
            Set-ManagedJar $slug $tmp $file.filename
            Remove-Item $tmp
        }
    } catch {
        if (-not $manifest[$slug]) { throw "Could not download $slug : $($_.Exception.Message)" }
        Say "could not update $slug, keeping $($manifest[$slug])"
    }
}

# ---- Gradle (for PartyFiller) -------------------------------------------------------
function Get-Gradle {
    $g = Get-Command gradle -ErrorAction SilentlyContinue
    if ($g) { return $g.Source }
    $found = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.*-bin\*\gradle-9.*\bin\gradle.bat" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($found) { return $found.FullName }
    $local = Join-Path $Cache 'gradle-9.3.0\bin\gradle.bat'
    if (-not (Test-Path $local)) {
        Say 'downloading Gradle 9.3.0 (first run only)'
        $zip = Join-Path $Cache 'gradle.zip'
        Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-9.3.0-bin.zip' -OutFile $zip -UseBasicParsing
        Expand-Archive $zip $Cache -Force
        Remove-Item $zip
    }
    return $local
}
function Invoke-Build($exe, $dir) {
    Push-Location $dir
    $ErrorActionPreference = 'Continue'   # gradle writes warnings to stderr
    try {
        & $exe build --no-daemon --console=plain -q "-Pkotlin.compiler.execution.strategy=in-process" 2>&1 |
            ForEach-Object { [Console]::Error.WriteLine("  $_") }
        if ($LASTEXITCODE -ne 0) { throw "build failed in $dir" }
    } finally { Pop-Location }
}

# ---- PartyFiller (this repo) ----------------------------------------------------------
$pf = Join-Path $Root 'partyfiller'
$pfNewest = Get-ChildItem $pf -Recurse -File | Where-Object { $_.FullName -notmatch '\\(build|\.gradle|run)\\' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
$pfInstalled = if ($manifest['partyfiller']) { Get-Item (Join-Path $Mods $manifest['partyfiller']) -ErrorAction SilentlyContinue }
if (-not $pfInstalled -or $pfNewest.LastWriteTime -gt $pfInstalled.LastWriteTime) {
    Say 'building PartyFiller'
    Invoke-Build (Get-Gradle) $pf
    $jar = Get-ChildItem (Join-Path $pf 'build\libs') -Filter '*.jar' | Where-Object Name -notmatch '-sources' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    Set-ManagedJar 'partyfiller' $jar.FullName $jar.Name
}

# ---- NoammAddons (your fork) ------------------------------------------------------------
$na = Join-Path $Cache 'NoammAddons'
$ErrorActionPreference = 'Continue'   # git writes progress to stderr
if (-not (Test-Path (Join-Path $na '.git'))) {
    Say 'cloning NoammAddons'
    git clone -q --branch $NoammRef $NoammRepo $na 2>&1 | Out-Null
} else {
    git -C $na fetch -q origin $NoammRef 2>&1 | Out-Null
    git -C $na reset -q --hard "origin/$NoammRef" 2>&1 | Out-Null
}
$sha = git -C $na rev-parse HEAD 2>$null
if ($LASTEXITCODE -ne 0) { $sha = $null } else { $sha = "$sha".Trim() }
$ErrorActionPreference = 'Stop'
$shaFile = Join-Path $Cache 'noammaddons.built'
$built = if (Test-Path $shaFile) { (Get-Content $shaFile -Raw).Trim() }
if ($sha -and ($sha -ne $built -or -not $manifest['noammaddons'])) {
    Say 'building NoammAddons (takes a few minutes)'
    Invoke-Build (Join-Path $na 'gradlew.bat') $na
    $jar = Get-ChildItem (Join-Path $na 'build\libs') -Filter '*-cheat.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) {
        $jar = Get-ChildItem (Join-Path $na 'build\libs') -Filter '*.jar' | Where-Object Name -notmatch '-(sources|dev)' |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    }
    Set-ManagedJar 'noammaddons' $jar.FullName $jar.Name
    Set-Content $shaFile $sha
} elseif (-not $sha) { Say 'could not update NoammAddons, keeping the current build' }

$manifest | ConvertTo-Json | Set-Content $manifestFile

# ---- Config ---------------------------------------------------------------------------
$pfConf = Join-Path $Config 'partyfiller.properties'
if (-not (Test-Path $pfConf)) {
    Copy-Item (Join-Path $pf 'partyfiller.properties.example') $pfConf
    Say "set your usernames in $pfConf"
}

Write-Output $HmcJar
Write-Output $Java
