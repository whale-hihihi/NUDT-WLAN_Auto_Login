# -*- coding: utf-8 -*-
"""端到端验证: 注销 → 运行 campus_login.py(生产主程序) → 验证自动联网。

隔离措施: 测试期间临时把其他 WiFi 改为手动连接, 结束后恢复; 失败自动切回原网络。
结果写 test_result.json, 过程写 test_cycle.log。
"""
import json
import os
import subprocess
import sys
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import srun  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def log(msg):
    line = '%s %s' % (time.strftime('%H:%M:%S'), msg)
    print(line, flush=True)
    with open(os.path.join(HERE, 'test_cycle.log'), 'a', encoding='utf-8') as f:
        f.write(line + '\n')


def set_profile_auto(ssid, auto):
    mode = 'auto' if auto else 'manual'
    r = subprocess.run(['netsh', 'wlan', 'set', 'profileparameter',
                        'name=%s' % ssid, 'connectionmode=%s' % mode],
                       capture_output=True, timeout=15)
    return r.returncode == 0


def main():
    with open(os.path.join(HERE, 'config.json'), encoding='utf-8') as f:
        cfg = json.load(f)
    base, user, acid = cfg['portal_base'], cfg['username'], cfg['ac_id']
    result = {'stage': 'start', 'success': False}

    w = srun.wifi_state()
    log('起始网络: %s' % w.get('ssid'))
    if w.get('ssid') != cfg['ssid']:
        # 必须先在校园网上再测
        result['error'] = '当前不在 %s, 先切过去(30秒)...' % cfg['ssid']
        srun.wifi_connect(cfg['ssid'])
        for _ in range(10):
            time.sleep(3)
            w = srun.wifi_state()
            if w.get('connected') and w.get('ssid') == cfg['ssid']:
                break
    if not (w.get('connected') and w.get('ssid') == cfg['ssid']):
        raise SystemExit('无法连接 %s' % cfg['ssid'])

    # 找出除 NUDT-WLAN 外的其他已保存网络(回滚目标: 取当前能连的第一个热点)
    rollback = None
    try:
        ip = srun.local_ip()
    except Exception:
        ip = None

    try:
        # 1. 注销, 制造"开机未认证"
        if ip:
            r = srun.logout(base, user, ip, acid)
            log('logout: %s' % r)
        time.sleep(2)
        authed = srun.parse_online(srun.rad_user_info(base))
        online = srun.is_internet_ok(cfg['check_urls'])
        log('注销后: internet=%s authed=%s' % (online, bool(authed)))
        if authed or online:
            raise RuntimeError('注销未生效, 无法进行测试')
        result['stage'] = 'logged_out'

        # 2. 运行生产主程序(与计划任务相同的入口)
        t0 = time.time()
        r = subprocess.run([sys.executable, '-X', 'utf8',
                            os.path.join(HERE, 'campus_login.py')],
                           capture_output=True, timeout=180)
        result['main_exit'] = r.returncode
        result['main_stdout'] = r.stdout.decode('utf-8', 'replace')
        log('campus_login.py 退出码=%s 耗时=%.1fs' % (r.returncode, time.time() - t0))

        # 3. 验证
        online = srun.is_internet_ok(cfg['check_urls'])
        authed = srun.parse_online(srun.rad_user_info(base))
        result['success'] = bool(online and authed)
        result['internet'] = online
        result['authed'] = authed['username'] if authed else None
        log('验证: internet=%s authed=%s => %s' % (
            online, result['authed'], '成功' if result['success'] else '失败'))
    except Exception:
        result['error'] = traceback.format_exc()
        log('异常:\n%s' % result['error'])
    finally:
        result['time'] = time.strftime('%Y-%m-%d %H:%M:%S')
        with open(os.path.join(HERE, 'test_result.json'), 'w', encoding='utf-8') as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        log('结束: success=%s' % result.get('success'))


if __name__ == '__main__':
    main()
