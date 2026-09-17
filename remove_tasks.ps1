# 卸载 CampusAutoLogin 计划任务
param([string]$TaskName = 'CampusAutoLogin')
$ErrorActionPreference = 'Stop'

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "已删除计划任务 [$TaskName]"
} else {
    Write-Host "计划任务 [$TaskName] 不存在, 无需删除"
}
Write-Host "注意: config.json(含账号密码)和其余脚本仍保留在本目录。"
