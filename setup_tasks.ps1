# 注册开机自启计划任务: CampusAutoLogin
# 触发器:
#   1. 用户登录(延迟20秒)                         → 开机
#   2. 系统事件 Power-Troubleshooter ID 1          → 休眠唤醒
#   3. 系统事件 NetworkProfile/Operational ID 10000 → 网络连接/重连
#   4. 每30分钟(兜底)                              → 服务器踢线等无网络事件的掉线
# 动作: pythonw 无窗口运行 campus_login.py
param([string]$TaskName = 'CampusAutoLogin')

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$mainScript = Join-Path $scriptDir 'campus_login.py'
if (-not (Test-Path $mainScript)) { throw "找不到主程序: $mainScript" }

# 解析 pythonw.exe 绝对路径(计划任务里不依赖 PATH)
$pyw = Get-Command pythonw.exe -ErrorAction SilentlyContinue
if (-not $pyw) {
    $py = Get-Command python.exe -ErrorAction Stop
    $pyw = Join-Path (Split-Path -Parent $py.Source) 'pythonw.exe'
}
if (-not (Test-Path $pyw.Source)) { throw "找不到 pythonw.exe (来自 $($pyw.Source))" }
$pythonwPath = $pyw.Source
Write-Host "使用解释器: $pythonwPath"

$action = New-ScheduledTaskAction -Execute $pythonwPath `
    -Argument ('"{0}"' -f $mainScript) -WorkingDirectory $scriptDir

# 触发器1: 用户登录, 延迟20秒等网络栈就绪
$tLogon = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$tLogon.Delay = 'PT20S'

# 事件触发器需通过 CIM 实例构造
$cimEvent = Get-CimClass -Namespace 'ROOT\Microsoft\Windows\TaskScheduler' -ClassName MSFT_TaskEventTrigger

# 触发器2: 休眠唤醒 (System 日志 Power-Troubleshooter 事件1)
$wakeSub = "<QueryList><Query Id='0' Path='System'><Select Path='System'>*[System[Provider[@Name='Microsoft-Windows-Power-Troubleshooter'] and EventID=1]]</Select></Query></QueryList>"
$tWake = New-CimInstance -CimClass $cimEvent -Property @{Subscription = $wakeSub} -ClientOnly

# 触发器3: 网络连接 (NetworkProfile/Operational 事件10000)
$netSub = "<QueryList><Query Id='0' Path='Microsoft-Windows-NetworkProfile/Operational'><Select Path='Microsoft-Windows-NetworkProfile/Operational'>*[System[EventID=10000]]</Select></Query></QueryList>"
$tNet = New-CimInstance -CimClass $cimEvent -Property @{Subscription = $netSub} -ClientOnly

# 触发器4: 每30分钟兜底
$tTimer = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
    -RepetitionInterval (New-TimeSpan -Minutes 30) `
    -RepetitionDuration (New-TimeSpan -Days 3650)

$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 10) -MultipleInstances IgnoreNew

Register-ScheduledTask -TaskName $TaskName -Action $action `
    -Trigger @($tLogon, $tWake, $tNet, $tTimer) -Settings $settings -Force | Out-Null

Write-Host ""
Write-Host "已注册计划任务 [$TaskName], 触发器:"
Write-Host "  - 用户登录(延迟20秒)"
Write-Host "  - 休眠唤醒事件"
Write-Host "  - 网络连接事件"
Write-Host "  - 每30分钟兜底"
Write-Host ""
Write-Host "立即测试运行一次..."
Start-ScheduledTask -TaskName $TaskName
Start-Sleep -Seconds 5
$info = Get-ScheduledTask -TaskName $TaskName
Write-Host ("任务状态: {0}" -f $info.State)
Write-Host "完成。日志见 login.log"
