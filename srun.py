# -*- coding: utf-8 -*-
"""深澜(SRun)认证协议实现 — NUDT 民网统一认证 (10.20.69.103)

加密算法从门户自身 JS 逐行移植（原始文件存档于 portal_js/ 目录）:
  - all.min.js 捆绑 js-md5 v2.10.0 : md5(message, key) 即 HMAC-MD5
  - all.min.js 捆绑 js-sha1 v0.6.0 : 标准 SHA-1
  - Portal.js _encodeUserInfo      : XXTEA(btea) + jquery-base64(自定义字母表, 字节直编, '='补位)

仅使用 Python 标准库。
"""
import base64
import hashlib
import hmac
import json
import re
import socket
import struct
import subprocess
import urllib.parse
import urllib.request

# jquery-base64 v1.0 setAlpha() 设置的自定义字母表
_B64_ALPHA = 'LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA'
_B64_STD = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
_DELTA = 0x9E3779B9
_MASK = 0xFFFFFFFF
_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) SrunAutoLogin/1.0'


# ---------------------------------------------------------------- 加密算法

def _custom_b64(data):
    # type: (bytes) -> str
    """jquery-base64 _encode(): 对字节直接查自定义字母表, '=' 补位, 不做 UTF-8 转换"""
    std = base64.b64encode(data).decode('ascii')
    return std.translate(str.maketrans(_B64_STD, _B64_ALPHA))


def hmac_md5_hex(message, key):
    # type: (str, str) -> str
    """js-md5 v2.10.0 的双参数形式 md5(message, key) = HMAC-MD5"""
    return hmac.new(key.encode('utf-8'), message.encode('utf-8'), hashlib.md5).hexdigest()


def sha1_hex(text):
    # type: (str) -> str
    return hashlib.sha1(text.encode('utf-8')).hexdigest()


def _to_words(data, append_len):
    # type: (bytes, bool) -> list
    """Portal.js s(): UTF-16 码元按小端打包为 uint32 数组, 缺位补零; append_len 时附加原始长度"""
    words = []
    for i in range(0, len(data), 4):
        words.append(struct.unpack('<I', data[i:i + 4].ljust(4, b'\x00'))[0])
    if append_len:
        words.append(len(data))
    return words


def _xxtea_btea(data, key):
    # type: (bytes, bytes) -> bytes
    """Portal.js encode(): XXTEA btea 加密; 输出经 l(v,false) 原样拼接(不裁剪长度)"""
    v = _to_words(data, True)
    k = _to_words(key, False)
    if len(k) < 4:  # JS 中此处补 undefined(NaN), 但 token 恒为 64 字节=16word, 不会触发
        k += [0] * (4 - len(k))
    n = len(v) - 1
    z, y = v[n], v[0]
    q = 6 + 52 // (n + 1)
    d = 0
    p = 0
    while q > 0:
        q -= 1
        d = (d + _DELTA) & _MASK
        e = (d >> 2) & 3
        p = 0
        while p < n:
            y = v[p + 1]
            m = (z >> 5) ^ ((y << 2) & _MASK)
            m += (((y >> 3) ^ ((z << 4) & _MASK)) ^ d ^ y)
            m += k[(p & 3) ^ e] ^ z
            z = v[p] = (v[p] + m) & _MASK
            p += 1
        # 注意: JS 循环结束后 p == n, 最后一轮的密钥下标用的是 n 而非 n-1
        y = v[0]
        m = (z >> 5) ^ ((y << 2) & _MASK)
        m += (((y >> 3) ^ ((z << 4) & _MASK)) ^ d ^ y)
        m += k[(p & 3) ^ e] ^ z
        z = v[n] = (v[n] + m) & _MASK
    return b''.join(struct.pack('<I', w) for w in v)


def encode_info(username, password, ip, ac_id, token):
    # type: (str, str, str, str, str) -> str
    """构造 {SRBX1}xxx 形式的 info 参数。

    JSON 键序与 JS 对象字面量一致(username/password/ip/acid/enc_ver),
    acid 沿用门户 CONFIG.acid 的字符串类型; JSON.stringify 默认转义非 ASCII,
    对应 json.dumps 的 ensure_ascii=True。"""
    plain = json.dumps({
        'username': username,
        'password': password,
        'ip': ip,
        'acid': ac_id,
        'enc_ver': 'srun_bx1',
    }, separators=(',', ':')).encode('ascii')
    return '{SRBX1}' + _custom_b64(_xxtea_btea(plain, token.encode('ascii')))


# ---------------------------------------------------------------- HTTP 层

def _http_get(url, timeout=6):
    # type: (str, float) -> tuple
    req = urllib.request.Request(url, headers={'User-Agent': _UA})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read().decode('utf-8', 'replace')


def _unwrap_jsonp(text):
    # type: (str) -> dict
    m = re.search(r'\((\{.*\})\)\s*;?\s*$', text.strip(), re.S)
    return json.loads(m.group(1) if m else text.strip())


# ---------------------------------------------------------------- 协议接口

def get_challenge(base_url, username, ip, timeout=6):
    # type: (str, str, str, float) -> str
    qs = urllib.parse.urlencode({'callback': 'sdf', 'username': username, 'ip': ip})
    _, text = _http_get('%s/cgi-bin/get_challenge?%s' % (base_url, qs), timeout)
    res = _unwrap_jsonp(text)
    if res.get('error') != 'ok' or not res.get('challenge'):
        raise RuntimeError('get_challenge 失败: %s' % res)
    return res['challenge']


def login(base_url, username, password, ip, ac_id='3', timeout=8):
    # type: (str, str, str, str, str, float) -> dict
    token = get_challenge(base_url, username, ip)
    hmd5 = hmac_md5_hex(password, token)
    info = encode_info(username, password, ip, ac_id, token)
    # chksum 拼接顺序 = Portal.js _loginAccount 中 str 的构造顺序
    chk_str = ''.join([token, username, token, hmd5, token, ac_id, token, ip,
                       token, '200', token, '1', token, info])
    params = {
        'callback': 'sdf',
        'action': 'login',
        'username': username,
        'password': '{MD5}' + hmd5,
        'os': 'Windows NT',
        'name': 'Windows',
        'double_stack': '0',
        'chksum': sha1_hex(chk_str),
        'info': info,
        'ac_id': ac_id,
        'ip': ip,
        'n': '200',
        'type': '1',
    }
    qs = urllib.parse.urlencode(params)
    _, text = _http_get('%s/cgi-bin/srun_portal?%s' % (base_url, qs), timeout)
    return _unwrap_jsonp(text)


def logout(base_url, username, ip, ac_id='3', timeout=6):
    # type: (str, str, str, str, float) -> dict
    params = {'callback': 'sdf', 'action': 'logout', 'username': username,
              'ip': ip, 'ac_id': ac_id}
    qs = urllib.parse.urlencode(params)
    _, text = _http_get('%s/cgi-bin/srun_portal?%s' % (base_url, qs), timeout)
    return _unwrap_jsonp(text)


def rad_user_info(base_url, timeout=6):
    # type: (str, float) -> str
    """不带任何参数查询; 已认证时返回 CSV(首字段用户名, 第9字段IP), 未认证时返回空或错误文本"""
    _, text = _http_get('%s/cgi-bin/rad_user_info' % base_url, timeout)
    return text.strip()


def parse_online(text):
    # type: (str) -> dict
    """把 rad_user_info 的 CSV 解析为在线信息; 未认证返回 None"""
    if not text:
        return None
    parts = text.split(',')
    if len(parts) >= 9 and parts[0]:
        return {'username': parts[0], 'online_ip': parts[8], 'raw': text}
    return None


# ---------------------------------------------------------------- 检测辅助

def local_ip(host='10.20.69.103'):
    # type: (str) -> str
    """通过 UDP connect 探路由获取本机 IPv4(不发真实报文), 失败抛异常"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((host, 53))
        return s.getsockname()[0]
    finally:
        s.close()


def is_internet_ok(urls, timeout=4):
    # type: (list, float) -> bool
    """国内 generate_204 探测: 任一返回 204 即已联网"""
    for url in urls:
        try:
            status, _ = _http_get(url, timeout)
            if status == 204:
                return True
        except Exception:
            continue
    return False


def wifi_state(timeout=10):
    # type: (float) -> dict
    """netsh wlan show interfaces 解析(兼容中英文系统及 UTF-8/GBK 代码页)"""
    try:
        out = subprocess.run(['netsh', 'wlan', 'show', 'interfaces'],
                             capture_output=True, timeout=timeout).stdout
        try:
            text = out.decode('utf-8')
        except UnicodeDecodeError:
            text = out.decode('gbk', 'replace')
    except Exception as e:
        return {'connected': False, 'ssid': None, 'error': str(e)}
    m = re.search(r'^\s*SSID\s*:\s*(\S.*?)\s*$', text, re.M)
    ssid = m.group(1) if m else None
    m = re.search(r'^\s*(?:State|状态)\s*:\s*(\S.*?)\s*$', text, re.M)
    state = m.group(1) if m else ''
    connected = state in ('connected', '已连接')
    return {'connected': connected, 'ssid': ssid, 'state': state}


def wifi_connect(ssid, timeout=15):
    # type: (str, float) -> bool
    """触发连接已保存的 WiFi 配置文件"""
    try:
        r = subprocess.run(['netsh', 'wlan', 'connect', 'name=%s' % ssid],
                           capture_output=True, timeout=timeout)
        return r.returncode == 0
    except Exception:
        return False
