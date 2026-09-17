# -*- coding: utf-8 -*-
"""自愈式完整登录测试 v2。

流程:
  1. 临时把当前网络(回滚目标)的配置改为"手动连接", 阻止 Windows 自动切回
  2. 切到 NUDT-WLAN, 等待拿到校园网 IP 且门户可达
  3. (在线则先注销) → 自动登录 → 验证
  4. 结束后恢复回滚目标为"自动连接"; 失败时切回原网络
结果写入 test_result.json, 过程写入 test_cycle.log。
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


def portal_reachable(base):
    try:
        srun.rad_user_info(base, timeout=3)
        return True
    except Exception:
        return False


def campus_ip():
    """等待本机 IPv4 进入校园网段(10.x)且门户可达"""
    for _ in range(20):
        try:
            ip = srun.local_ip()
            if ip.startswith('10.'):
                return ip
        except Exception:
            pass
        time.sleep(2)
    return None


def main():
    with open(os.path.join(HERE, 'config.json'), encoding='utf-8') as f:
        cfg = json.load(f)
    base, user, pwd, acid = cfg['portal_base'], cfg['username'], cfg['password'], cfg['ac_id']
    result = {'stage': 'start', 'success': False}

    w = srun.wifi_state()
    rollback = w.get('ssid') if w.get('ssid') != cfg['ssid'] else None
    log('起始网络: %s (回滚目标: %s)' % (w.get('ssid'), rollback))
    if rollback:
        set_profile_auto(rollback, False)
        log('已临时把 %s 改为手动连接' % rollback)

    try:
        # 1. 硬抢 NUDT-WLAN: 断开当前网络, 反复重连对抗 Windows 自动切回
        deadline = time.time() + 60
        ip = None
        while time.time() < deadline:
            w = srun.wifi_state()
            on_campus = w.get('connected') and w.get('ssid') == cfg['ssid']
            if on_campus:
                try:
                    maybe_ip = srun.local_ip()
                    if maybe_ip.startswith('10.') and portal_reachable(base):
                        ip = maybe_ip
                        break
                except Exception:
                    pass
            # 不在校园网(或没拿到IP) → 断开再连, 反复抢
            subprocess.run(['netsh', 'wlan', 'disconnect'], capture_output=True, timeout=10)
            time.sleep(1)
            srun.wifi_connect(cfg['ssid'])
            time.sleep(3)
        if ip is None:
            w = srun.wifi_state()
            raise RuntimeError('无法在 %s 上取得校园网IP (当前: %s)' % (cfg['ssid'], w))
        log('已连接 %s, 本机IP=%s, 门户可达' % (cfg['ssid'], ip))
        result['stage'] = 'wifi_ok'
        result['ip'] = ip

        # 2. 已在线则先注销, 制造"开机未认证"状态
        authed = srun.parse_online(srun.rad_user_info(base))
        if authed:
            log('当前已在线(%s), 先注销' % authed['username'])
            r = srun.logout(base, user, ip, acid)
            log('logout: %s' % r)
            time.sleep(2)
        authed = srun.parse_online(srun.rad_user_info(base))
        online = srun.is_internet_ok(cfg['check_urls'])
        log('登录前状态: internet=%s authed=%s' % (online, bool(authed)))
        result.update(before_internet=online, before_authed=bool(authed))

        # 3. 自动登录
        t0 = time.time()
        res = srun.login(base, user, pwd, ip, acid)
        log('login(%.1fs): %s' % (time.time() - t0, res))
        result['stage'] = 'login_done'
        result['login_response'] = res

        # 4. 验证
        time.sleep(2)
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
        # 恢复 + 自愈
        if rollback:
            set_profile_auto(rollback, True)
            log('已恢复 %s 为自动连接' % rollback)
            if not result.get('success'):
                log('失败回滚: 切回 %s' % rollback)
                srun.wifi_connect(rollback)
                time.sleep(4)
                w = srun.wifi_state()
                result['rollback_ssid'] = w.get('ssid')
                result['rollback_internet'] = srun.is_internet_ok(cfg['check_urls'])
                log('回滚后: %s internet=%s' % (result.get('rollback_ssid'),
                                                result.get('rollback_internet')))
        result['time'] = time.strftime('%Y-%m-%d %H:%M:%S')
        with open(os.path.join(HERE, 'test_result.json'), 'w', encoding='utf-8') as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        log('结束: success=%s' % result.get('success'))


if __name__ == '__main__':
    main()
