param(
    [string]$Adb = 'C:\Users\86189\AppData\Local\Android\Sdk\platform-tools\adb.exe',
    [int]$Rounds = 2
)
$ErrorActionPreference = 'Stop'
$appPackage = 'com.leap8.offline'
function Read-Ui {
    & $Adb shell uiautomator dump /sdcard/leap8-window.xml | Out-Null
    [xml]((& $Adb shell cat /sdcard/leap8-window.xml) -join "`n")
}
function Find-Node($doc, [string]$label, [bool]$BelowToolbar = $false) {
    foreach ($node in $doc.SelectNodes('//node')) {
        if ($node.package -ne $appPackage) { continue }
        if ($node.text -ne $label -and $node.'content-desc' -ne $label) { continue }
        if ($node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $left, $top, $right, $bottom = $Matches[1..4] | ForEach-Object { [int]$_ }
            if ($right -le $left -or $bottom -le $top) { continue }
            if ($BelowToolbar -and $top -lt 400) { continue }
            return @{X=[int](($left+$right)/2);Y=[int](($top+$bottom)/2)}
        }
    }
    return $null
}
function Tap-Label([string]$label, [bool]$Scroll = $false) {
    for ($attempt=0; $attempt -lt 7; $attempt++) {
        $point = Find-Node (Read-Ui) $label $Scroll
        if ($null -ne $point) {
            & $Adb shell input tap $point.X $point.Y | Out-Null
            Start-Sleep -Milliseconds 650
            return
        }
        if (!$Scroll) { break }
        & $Adb shell input swipe 720 2500 720 1000 300 | Out-Null
    }
    throw "Cannot find app control: $label"
}
function Assert-Screen([string]$title) {
    $point = Find-Node (Read-Ui) $title
    if ($null -eq $point) { throw "Expected screen: $title" }
    $currentPid = (& $Adb shell pidof $appPackage).Trim()
    if ($currentPid -ne $script:mainPid) { throw "Main PID changed: $script:mainPid -> $currentPid" }
    Write-Output "PASS $title (main PID $currentPid)"
}
& $Adb shell am start -n com.leap8.offline/.MainActivity | Out-Null
Start-Sleep -Seconds 2
$script:mainPid = (& $Adb shell pidof $appPackage).Trim()
if (!$script:mainPid) { throw 'App did not start' }
Tap-Label '练习'
for ($round=1; $round -le $Rounds; $round++) {
    foreach ($title in @('记忆工坊','精听训练','口语实验室','阅读训练','写作评阅','AI 精修对比','离线 AI 教练','语法诊所','模考中心')) {
        # Bring the hub back to its first item, then locate the real visible row.
        & $Adb shell input swipe 720 900 720 2550 200 | Out-Null
        & $Adb shell input swipe 720 900 720 2550 200 | Out-Null
        Tap-Label $title $true
        Assert-Screen $title
        if ($title -eq '记忆工坊') {
            Tap-Label '查看总词本'
            Assert-Screen 'IELTS 总词本'
            & $Adb shell input keyevent 4 | Out-Null
            Start-Sleep -Milliseconds 650
        }
        & $Adb shell input keyevent 4 | Out-Null
        Start-Sleep -Milliseconds 650
        Assert-Screen '专项练习'
    }
    Write-Output "PASS round $round"
}
foreach ($tab in @(@('路径','学习路径'),@('进度','能力进度'),@('我的','AI 与离线中心'),@('今日','跃升英语'))) {
    Tap-Label $tab[0]
    Assert-Screen $tab[1]
}
Write-Output 'All routes passed without main process restart.'
