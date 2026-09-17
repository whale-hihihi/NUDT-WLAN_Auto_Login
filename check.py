# -*- coding: utf-8 -*-
"""阶段1验证脚本: 只检测、不登录。

输出三层状态并给出综合判定:
  1. WiFi 是否连着校园网 NUDT-WLAN
  2. 是否已联网(国内 204 探测)
  3. 门户认证状态(rad_user_info)
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import srun  # noqa: E402


def main():
    cfg_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'config.json')
    with open(cfg_path, encoding='utf-8') as f:
        cfg = json.load(f)
    base = cfg['portal_base']
    ssid = cfg['ssid']

    print('=== 1. WiFi 状态 ===')
    w = srun.wifi_state()
    print('    connected : %s' % w.get('connected'))
    print('    SSID      : %s' % w.get('ssid'))
    on_wifi = w.get('connected') and w.get('ssid') == ssid
    print('    判定      : %s' % ('已连接 %s' % ssid if on_wifi else '未连接 %s' % ssid))
    if w.get('error'):
        print('    (netsh 出错: %s)' % w['error'])

    print('=== 2. 联网探测 (国内 generate_204) ===')
    online = srun.is_internet_ok(cfg['check_urls'])
    print('    判定      : %s' % ('已联网' if online else '未联网'))

    print('=== 3. 门户认证状态 (%s) ===' % base)
    try:
        text = srun.rad_user_info(base)
        info = srun.parse_online(text)
        if info:
            print('    认证状态  : 已认证在线')
            print('    用户名    : %s' % info['username'])
            print('    在线 IP   : %s' % info['online_ip'])
        else:
            print('    认证状态  : 未认证')
            print('    原始响应  : %r' % (text[:200] if text else '(空)'))
        authed = bool(info)
    except Exception as e:
        print('    门户不可达: %s' % e)
        authed = None

    print('=== 综合判定 ===')
    if online:
        print('    [状态A] 已联网, 无需任何操作')
    elif authed:
        print('    [状态B] 门户显示已在线但 204 不通 (可能是认证服务器与外网间的临时故障)')
    elif authed is False:
        print('    [状态C] 已连校园网但未认证 → 主程序将执行自动登录')
    else:
        print('    [状态D] 门户不可达 → 不在校园网环境(如手机热点/家里), 静默跳过')


if __name__ == '__main__':
    main()
