# 実機準備.ps1 ── 帰宅後、端末を繋いでこれ一本を走らせる。
#
#   1. 利用者の layout.json を必ず先に控える（過去に書き換え事故を起こしている）
#   2. 組んで入れる（CLI用の出力先へ。IDEの app\build を壊さないため -PcliBuild は必須）
#   3. PCからしか与えられない権限を与え、与えられたかを実測する
#   4. 電池の隠れた値が本当に取れるかを adb だけで確かめる
#
# 何も壊さない。権限が拒まれても、アプリは今までどおり動く。

$ErrorActionPreference = 'Continue'
$adb  = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$pkg  = 'com.yuma.observatorylauncher'
$proj = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

function Line($t) { Write-Host ''; Write-Host "── $t " -NoNewline; Write-Host ('─' * [Math]::Max(0, 60 - $t.Length)) }

if (-not (Test-Path $adb)) { Write-Host "adb が見つからない: $adb"; exit 1 }
$devices = & $adb devices
if (-not ($devices -match "`tdevice")) { Write-Host '端末が繋がっていない。USBデバッグを確認すること。'; exit 1 }

# ── 1. 控え ────────────────────────────────────────────────
Line '控えを取る'
$backupDir = Join-Path $proj 'backups'
if (-not (Test-Path $backupDir)) { New-Item -ItemType Directory -Path $backupDir | Out-Null }
$backup = Join-Path $backupDir "layout-$stamp.json"
$json = & $adb exec-out run-as $pkg cat files/layout.json
if ($LASTEXITCODE -eq 0 -and $json) {
    # ⚠ Out-File -Encoding utf8 は PowerShell 5.1 では **BOM を付ける**。
    # BOM 付きの JSON は Android の org.json が読めず、戻した瞬間に
    # レイアウトが壊れる。BOM 無しで書くこと。
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($backup, ($json -join "`n"), $utf8NoBom)
    Write-Host "控え: $backup  ($($json.Length) 行)"
} else {
    Write-Host '⚠ layout.json を読めなかった。アプリ未導入なら想定どおり。'
}

# ── 2. 組んで入れる ────────────────────────────────────────
# ⚠ **release を入れる。debug ではない。**
# android:debuggable が立っている間、ART は AOT を諦めて解釈実行のままにする
# （実機で pm art dump が status=verify を返し、cmd package compile を通しても動かない）。
# その結果、一覧を掃くたびに実行時コンパイルの暖まりが起き、
# 同じ操作の繰り返しで 18% → 3% と落ちる曲線が出る。常用するのは冷たい側なので常に重い。
# release（開発用の鍵で署名）にすると 0.11%・90p 6ms まで落ち、曲線自体が消える（実測 2026/8/31）。
Line '組んで入れる'
Push-Location $proj
& .\gradlew.bat -PcliBuild :app:installRelease -q
$built = $LASTEXITCODE
Pop-Location
if ($built -ne 0) { Write-Host '⚠ ビルドに失敗した。ここで止める。'; exit 1 }
Write-Host '導入した。'

# 機械語へ落とす。**入れ直すたびに戻るので、導入の一部として毎回行う。**
& $adb shell cmd package compile -m speed -f $pkg | Out-Null
$art = & $adb shell pm art dump $pkg
if ($art -match 'status=speed') {
    Write-Host 'AOT: speed（機械語へ落ちた）'
} else {
    Write-Host '⚠ AOT が効いていない。debug を入れていないか確認すること。'
}

# ── 3. 解錠 ────────────────────────────────────────────────
Line 'PCからしか与えられない権限'
$perms = @(
    'android.permission.WRITE_SECURE_SETTINGS',
    'android.permission.DUMP',
    'android.permission.BATTERY_STATS',
    'android.permission.READ_LOGS'
)
foreach ($p in $perms) {
    $out = & $adb shell pm grant $pkg $p
    $short = $p -replace 'android\.permission\.', ''
    if ($LASTEXITCODE -eq 0 -and -not $out) {
        Write-Host ("  {0,-24} 与えた" -f $short)
    } else {
        Write-Host ("  {0,-24} 拒まれた → {1}" -f $short, ($out -join ' '))
    }
}

Line '実際に付いているか（これが正本。上の成否ではない）'
$dump = & $adb shell dumpsys package $pkg
foreach ($p in $perms) {
    $short = $p -replace 'android\.permission\.', ''
    $hit = $dump | Select-String -SimpleMatch "$p`: granted=true"
    if ($hit) { Write-Host ("  {0,-24} ○ 付いている" -f $short) }
    else      { Write-Host ("  {0,-24} × 付いていない" -f $short) }
}

# ── 4. 電池の隠れた値 ──────────────────────────────────────
Line '電池（設計の未確定その3）'
$bat = & $adb shell dumpsys battery
foreach ($k in 'level','voltage','temperature','Charge counter','cycle') {
    $hit = $bat | Select-String -SimpleMatch $k
    if ($hit) { $hit | ForEach-Object { Write-Host ('  ' + $_.ToString().Trim()) } }
}
Write-Host ''
Write-Host '  sysfs（shell から読めるか。Shizuku 経路の可否に直結する）:'
foreach ($n in 'cycle_count','charge_full','charge_full_design','health','capacity') {
    $v = & $adb shell "cat /sys/class/power_supply/battery/$n 2>/dev/null"
    if ($v) { Write-Host ("    {0,-20} {1}" -f $n, $v) }
    else    { Write-Host ("    {0,-20} 読めない" -f $n) }
}

# ── 5. 種を書き出す ────────────────────────────────────────
# charge_full と charge_full_design は **shell でしか読めない**。
# アプリからは FileNotFoundException になる（SELinux の立場が違う）。
# ここで読んで種として残し、宣言へ入れれば、以後はアプリだけで
# 正しい健全度を出せる。Shizuku は要らない。
$full   = & $adb shell "cat /sys/class/power_supply/battery/charge_full 2>/dev/null"
$design = & $adb shell "cat /sys/class/power_supply/battery/charge_full_design 2>/dev/null"
$cycles = & $adb shell "cat /sys/class/power_supply/battery/cycle_count 2>/dev/null"
if ($full -and $design) {
    $seed = [ordered]@{
        takenAt          = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
        chargeFullUah    = [int]$full
        chargeDesignUah  = [int]$design
        cycleCount       = [int]$cycles
        healthPercent    = [math]::Round(100.0 * [int]$full / [int]$design, 1)
    }
    $seedPath = Join-Path $backupDir 'battery-seed.json'
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($seedPath, ($seed | ConvertTo-Json), $utf8NoBom)
    Line '電池の種'
    Write-Host ("  健全度 {0}%  ({1} / {2} µAh・{3}サイクル)" -f $seed.healthPercent, $seed.chargeFullUah, $seed.chargeDesignUah, $seed.cycleCount)
    Write-Host "  $seedPath"
}

Line 'まとめ'
Write-Host '  ・上の「付いている」が未確定その2の答え。'
Write-Host '  ・sysfs は shell でのみ読める。アプリからは読めない（実測済）。'
Write-Host '    だからここで読んで種として残す。以後はアプリだけで健全度を出せる。'
Write-Host ''
$check = [System.IO.File]::ReadAllBytes($backup)
if ($check.Length -gt 3 -and $check[0] -eq 0xEF) { Write-Host '  ⚠ 控えに BOM が付いている。戻す前に取り除くこと。' }
Write-Host "  控えを戻すとき:"
Write-Host "    adb push `"$backup`" /data/local/tmp/lb.json"
Write-Host "    adb shell `"run-as $pkg cp /data/local/tmp/lb.json /data/data/$pkg/files/layout.json`""
Write-Host ''
