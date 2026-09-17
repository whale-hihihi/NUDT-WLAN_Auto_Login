# -*- coding: utf-8 -*-
"""NUDT 校园网自动登录主程序(一次性运行, 由计划任务拉起)。

计划任务触发场景: 开机登录 / 休眠唤醒 / 网络变化 / 每30分钟兜底。
行为:
  已联网(204探测)        → 立即退出
  连着 NUDT-WLAN 未认证  → 自动登录后退出
  不在校园网(门户不可达)  → 等待至总超时后静默退出
日志: login.log(自动裁剪只保留尾部) 并发: .campus_login.lock 文件锁
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import srun  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
LOCK_PATH = os.path.join(HERE, '.campus_login.lock')
LOG_PATH = os.path.join(HERE, 'login.log')

# pythonw 无控制台时 stdout/stderr 为 None, print 会抛 AttributeError
if sys.stdout is None:
    sys.stdout = open(os.devnull, 'w')
if sys.stderr is None:
    sys.stderr = open(os.devnull, 'w')


def load_config():
    with open(os.path.join(HERE, 'config.json'), encoding='utf-8') as f:
        return json.load(f)


def log(cfg, msg):
    # type: (dict, str) -> None
    line = '%s %s\n' % (time.strftime('%Y-%m-%d %H:%M:%S'), msg)
    try:
        with open(LOG_PATH, 'a', encoding='utf-8') as f:
            f.write(line)
        # 超限则只保留尾部
        with open(LOG_PATH, encoding='utf-8') as f:
            lines = f.readlines()
        limit = int(cfg.get('log_max_lines', 500))
        if len(lines) > limit * 2:
            with open(LOG_PATH, 'w', encoding='utf-8') as f:
                f.writelines(lines[-limit:])
    except Exception:
        pass


def acquire_lock(max_age_sec=300):
    # type: (int) -> bool
    if os.path.exists(LOCK_PATH):
        try:
            if time.time() - os.path.getmtime(LOCK_PATH) > max_age_sec:
                os.remove(LOCK_PATH)  # 上一次运行的陈旧锁
            else:
                return False  # 已有实例在跑
        except OSError:
            return False
    try:
        fd = os.open(LOCK_PATH, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        os.write(fd, str(os.getpid()).encode('ascii'))
        os.close(fd)
        return True
    except FileExistsError:
        return False


def release_lock():
    try:
        os.remove(LOCK_PATH)
    except OSError:
        pass


def main():
    cfg = load_config()
    deadline = time.time() + int(cfg.get('total_timeout_sec', 120))
    interval = int(cfg.get('retry_interval_sec', 5))
    max_login_tries = 3
    login_tries = 0
    connect_tries = 0

    while time.time() < deadline:
        # 1. 已联网 → 完成
        if srun.is_internet_ok(cfg.get('check_urls')):
            log(cfg, '已联网, 无需操作')
            return 0

        # 2. WiFi 不在校园网 → 有限次触发连接(开机时网络可能还没就绪)
        w = srun.wifi_state()
        if not (w.get('connected') and w.get('ssid') == cfg.get('ssid')):
            if connect_tries < 3:
                connect_tries += 1
                srun.wifi_connect(cfg['ssid'])
                log(cfg, 'WiFi 未连 %s, 第%d次触发连接' % (cfg['ssid'], connect_tries))
            time.sleep(interval)
            continue

        # 3. 连着校园网 → 查门户认证状态
        try:
            ip = srun.local_ip()
        except Exception:
            time.sleep(interval)
            continue
        try:
            text = srun.rad_user_info(cfg['portal_base'])
        except Exception:
            # 门户不可达: 网络未就绪(继续等)或不在校园网(等到超时静默退出)
            time.sleep(interval)
            continue

        info = srun.parse_online(text)
        if info:
            # 门户显示已在线但 204 不通, 可能刚登录完网络还没生效
            log(cfg, '门户已在线(%s), 等待外网生效' % info['username'])
            time.sleep(interval)
            continue

        # 4. 未认证 → 登录
        if login_tries >= max_login_tries:
            log(cfg, '登录%d次仍未成功, 退出等待下次触发' % max_login_tries)
            return 1
        login_tries += 1
        try:
            res = srun.login(cfg['portal_base'], cfg['username'],
                             cfg['password'], ip, cfg.get('ac_id', '3'))
            msg = res.get('suc_msg') or res.get('error') or str(res)
            log(cfg, '登录第%d次: %s (ecode=%s)' % (
                login_tries, msg, res.get('ecode')))
            if res.get('error') == 'ok':
                time.sleep(2)
                if srun.is_internet_ok(cfg.get('check_urls')):
                    log(cfg, '登录成功, 已联网')
                    return 0
        except Exception as e:
            log(cfg, '登录异常: %r' % e)
        time.sleep(interval)

    log(cfg, '总超时退出(不在校园网或网络异常)')
    return 0


if __name__ == '__main__':
    if not acquire_lock():
        sys.exit(0)  # 已有实例在运行
    try:
        sys.exit(main())
    finally:
        release_lock()
