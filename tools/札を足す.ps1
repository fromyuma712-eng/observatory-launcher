# 新しい情報札を端末へ足して、release へ戻すまでを一気に行う。
#
# なぜ往復するのか:
#   release は android:debuggable が下りているので AOT が効く（一覧の掃きが 0.1% まで落ちる）。
#   その代わり run-as が使えず、PC から files/layout.json を書けない。
#   そこで「debug で書いて、release へ戻す」。宣言・世代・既定ホームの指定は
#   署名が同じなので上書きで保たれる。
#
# 使い方:
#   powershell -ExecutionPolicy Bypass -File tools\札を足す.ps1
#   powershell -ExecutionPolicy Bypass -File tools\札を足す.ps1 -Kinds charge,space -Page 1

param(
    [string]$Kinds = 'charge,space,touch',
    [int]$Page = 1
)

$ErrorActionPreference = 'Stop'
$pkg  = 'com.yuma.observatorylauncher'
$proj = Split-Path -Parent $PSScriptRoot
$adb  = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$rel  = Join-Path $env:TEMP 'observatory-build-cli\outputs\apk\release\app-release.apk'

function Line($t) { Write-Host ''; Write-Host "── $t ─────────────────────" }

Line '端末の確認'
$devices = & $adb devices | Select-String -Pattern "\tdevice$"
if (-not $devices) { Write-Host '× 端末が見えない。接続とUSBデバッグを確認すること。'; exit 1 }
Write-Host '端末あり。'

Line 'debug を入れる（宣言を書くため）'
Push-Location $proj
& .\gradlew.bat -PcliBuild :app:installDebug -q
$built = $LASTEXITCODE
Pop-Location
if ($built -ne 0) { Write-Host '× ビルドに失敗した。ここで止める。'; exit 1 }
Start-Sleep -Seconds 2

Line '宣言に札を足す'
$tmp = Join-Path $env:TEMP 'observatory-layout.json'
& $adb exec-out run-as $pkg cat files/layout.json | Out-File -FilePath $tmp -Encoding utf8
if (-not (Test-Path $tmp)) { Write-Host '× layout.json を読めなかった。'; exit 1 }

$json = Get-Content $tmp -Raw | ConvertFrom-Json
$have = @($json.infoTiles | ForEach-Object { $_.kind })
$proto = $json.infoTiles[-1]
$added = @()
foreach ($kind in ($Kinds -split ',')) {
    $k = $kind.Trim()
    if ($k -eq '') { continue }
    if ($have -contains $k) { Write-Host ("  {0,-18} 既にある" -f $k); continue }
    $tile = $proto.PSObject.Copy()
    $tile.id    = [guid]::NewGuid().ToString('N').Substring(0, 12)
    $tile.kind  = $k
    $tile.title = ''
    $tile.value = ''
    $tile.page  = $Page
    $json.infoTiles += $tile
    $added += $k
    Write-Host ("  {0,-18} 足した" -f $k)
}

if ($added.Count -gt 0) {
    # ⚠ BOM 付きの JSON は Android の org.json が読めず、戻した瞬間に宣言が壊れる。
    $out = Join-Path $env:TEMP 'observatory-layout-new.json'
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($out, ($json | ConvertTo-Json -Depth 12), $utf8NoBom)
    & $adb push $out /data/local/tmp/l.json | Out-Null
    & $adb shell run-as $pkg cp /data/local/tmp/l.json files/layout.json
    & $adb shell rm /data/local/tmp/l.json
    & $adb shell am force-stop $pkg
    Write-Host '書き込んだ。'
} else {
    Write-Host '足すものは無かった。'
}

Line 'release へ戻す'
Push-Location $proj
& .\gradlew.bat -PcliBuild :app:installRelease -q
$ok = $LASTEXITCODE
Pop-Location
if ($ok -ne 0) { Write-Host '× release のビルドに失敗した。debug のままなので注意すること。'; exit 1 }

# 機械語へ落とす。入れ直すたびに戻るので毎回行う。
& $adb shell cmd package compile -m speed -f $pkg | Out-Null
$art = & $adb shell pm art dump $pkg
if ($art -match 'status=speed') { Write-Host 'AOT: speed' } else { Write-Host '⚠ AOT が効いていない' }

Line 'PCからしか与えられない権限を与え直す'
# release へ入れ替えると pm grant の分は落ちる。
foreach ($p in @('WRITE_SECURE_SETTINGS','DUMP','BATTERY_STATS','READ_LOGS')) {
    & $adb shell pm grant $pkg "android.permission.$p" 2>$null
    Write-Host ("  {0,-24} 与えた" -f $p)
}

Line '確認'
& $adb shell am force-stop $pkg
& $adb shell input keyevent KEYCODE_HOME
Start-Sleep -Seconds 6
$fatal = (& $adb logcat -d -s 'AndroidRuntime:E' | Select-String 'FATAL').Count
Write-Host "FATAL: $fatal"
Write-Host ''
Write-Host '完了。二ページ目に新しい札が出ているはず。'
