#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
YSP 央视频直播代理 —— 部署版 PHP（央视频1.php）的完整 Python 移植
监听 127.0.0.1:9979，功能与 PHP 版逐字对齐：
  /ysp?list=live        直播源列表（本服务端口）
  /ysp?id=<频道>        直播 M3U8（逐行补全 + CDN域名改写 + \u0026反转义）
  /ysp?id=<频道>&debug=1  上游原始 JSON

稳定机制（与 PHP 版一致 + Java 版教训）：
  1. playurl 80s 缓存 —— API 频率降 10 倍，避免盒子 IP 高频取址被 CDN 限流
  2. M3U8 每次刷新重新拉取（缓存 playurl 拉，轻量且切片实时）
  3. M3U8 拉取失败 → 清缓存重取（最多 2 次）→ lastGood 兜底（120s）→ 200 占位
  4. 任何异常一律返回 200 占位（绝不给播放器 5xx → 杜绝 Bad HTTP Status）
  5. M3U8Proxy 逐行补全相对 URI（含 #EXT-X-KEY/#EXT-X-MAP 的 URI 属性、../ 归一化）
  6. CDN 域名改写：outlivecloud→hlsliveali（实测 502 坏域）、mobilelive-*→mobilelive-cnc
  7. \u0026 反转义（部分 CDN 节点返回 JSON 风格转义的切片行）
仅依赖 Python 3.10 标准库（urllib/http.server/ssl），无第三方包。
"""
import base64
import json
import random
import re
import ssl
import struct
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ================= 常量 =================
UA = "qqlive"
API = "https://bkliveinfo.ysp.cctv.cn"
PORT = 9979
HOST = "127.0.0.1"
PLAYURL_CACHE_SEC = 240     # token 实测 ≥301s 有效；盒子 IP 场景 240s 缓存把 API 频率压到 4 分钟/次（PHP 80s 是服务器场景保守值）
LAST_GOOD_SEC = 240         # lastGood（playurl / M3U8 内容）兜底窗口，与 playurl 缓存对齐
API_TIMEOUT = 8             # PHP curl TIMEOUT 5s，Java 版 8s 已验证
M3U8_TIMEOUT = 10

# TEA / 加密常量（与 PHP CKeyManager / Java 版完全一致）
ROUNDS_TEA = 16
DELTA = 0x9E3779B9
SALT_LEN = 2
ZERO_LEN = 7
TEA_CKEY = bytes.fromhex("59b2f7cf725ef43c34fdd7c123411ed3")
XOR_KEY = [0x84, 0x2E, 0xED, 0x08, 0xF0, 0x66, 0xE6, 0xEA, 0x48, 0xB4, 0xCA, 0xA9, 0x91, 0xED, 0x6F, 0xF3]
STD_ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
CUS_ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-="

_ssl_ctx = None
try:
    _ctx = ssl.create_default_context()
    _ctx.check_hostname = False
    _ctx.verify_mode = ssl.CERT_NONE
    _ssl_ctx = _ctx
except Exception:
    # Android Chaquopy 个别设备 CA 初始化异常时退化（不影响业务，无验证即可）
    try:
        _ssl_ctx = ssl._create_unverified_context()
    except Exception:
        _ssl_ctx = None

# ================= 频道表（与 Java Channel.java 一致） =================
CHANNELS = {
    "cctv1": ("2024078201", "600001859", "fhd", "CCTV-1"),
    "cctv2": ("2024075401", "600001800", "fhd", "CCTV-2"),
    "cctv3": ("2024068501", "600001801", "fhd", "CCTV-3"),
    "cctv4": ("2029797101", "600001814", "fhd", "CCTV-4"),
    "cctv5": ("2024078401", "600001818", "fhd", "CCTV-5"),
    "cctv5p": ("2024078001", "600001817", "fhd", "CCTV-5+"),
    "cctv6": ("2013693901", "600108442", "fhd", "CCTV-6"),
    "cctv7": ("2024072001", "600004092", "fhd", "CCTV-7"),
    "cctv8": ("2029793001", "600001803", "fhd", "CCTV-8"),
    "cctv9": ("2024078601", "600004078", "fhd", "CCTV-9"),
    "cctv10": ("2024078701", "600001805", "fhd", "CCTV-10"),
    "cctv11": ("2027248701", "600001806", "fhd", "CCTV-11"),
    "cctv12": ("2027248801", "600001807", "fhd", "CCTV-12"),
    "cctv13": ("2029797201", "600001811", "fhd", "CCTV-13"),
    "cctv14": ("2027248901", "600001809", "fhd", "CCTV-14"),
    "cctv15": ("2027249001", "600001815", "fhd", "CCTV-15"),
    "cctv16": ("2027249101", "600098637", "fhd", "CCTV-16"),
    "cctv164k": ("2027249301", "600099502", "fhd", "CCTV-16(4K)"),
    "cctv17": ("2027249401", "600001810", "fhd", "CCTV-17"),
    "cctv4k": ("2029810301", "600002264", "fhd", "CCTV-4K"),
    "cctv8k": ("2026774101", "600156816", "fhd", "CCTV-8K"),
    "cgtn": ("2024181701", "600014550", "fhd", "CGTN"),
    "cgtnfy": ("2024181801", "600084704", "fhd", "CGTN法语频道"),
    "cgtney": ("2024181901", "600084758", "fhd", "CGTN俄语频道"),
    "cgtnalby": ("2024182001", "600084782", "fhd", "CGTN阿拉伯语频道"),
    "cgtnxby": ("2024182101", "600084744", "fhd", "CGTN西班牙语频道"),
    "cgtnwyjl": ("2024182301", "600084781", "fhd", "CGTN外语纪录频道"),
    "cctvfyjc": ("2025637103", "600099658", "shd", "CCTV风云剧场频道"),
    "cctvdyjc": ("2026874203", "600099655", "shd", "CCTV第一剧场频道"),
    "cctvhjjc": ("2026874303", "600099620", "shd", "CCTV怀旧剧场频道"),
    "cctvsjdl": ("2026874403", "600099637", "shd", "CCTV世界地理频道"),
    "cctvfyyy": ("2026874503", "600099660", "shd", "CCTV风云音乐频道"),
    "cctvbqkj": ("2026874603", "600099649", "shd", "CCTV兵器科技频道"),
    "cctvfyzq": ("2026966203", "600099636", "shd", "CCTV风云足球频道"),
    "cctvgeqwq": ("2026874703", "600099659", "shd", "CCTV高尔夫·网球频道"),
    "cctvnxss": ("2026874803", "600099650", "shd", "CCTV女性时尚频道"),
    "cctvyswhjp": ("2026874903", "600099653", "shd", "CCTV央视文化精品频道"),
    "cctvystq": ("2026875003", "600099652", "shd", "CCTV央视台球频道"),
    "cctvdszn": ("2026875103", "600099656", "shd", "CCTV电视指南频道"),
    "cctvwsjk": ("2025637003", "600099651", "shd", "CCTV卫生健康频道"),
    "bjws": ("2024052703", "600002309", "fhd", "北京卫视"),
    "jsws": ("2024171103", "600002521", "fhd", "江苏卫视"),
    "dfws": ("2024054503", "600002483", "fhd", "东方卫视"),
    "zjws": ("2024054703", "600002520", "fhd", "浙江卫视"),
    "hnws": ("2024054803", "600002475", "fhd", "湖南卫视"),
    "hbws": ("2024171203", "600002508", "fhd", "湖北卫视"),
    "gdws": ("2024060903", "600002485", "fhd", "广东卫视"),
    "gxws": ("2024060703", "600002509", "fhd", "广西卫视"),
    "hljws": ("2029797003", "600002498", "fhd", "黑龙江卫视"),
    "hnws2": ("2024055603", "600002506", "fhd", "海南卫视"),
    "cqws": ("2024061103", "600002531", "fhd", "重庆卫视"),
    "szws": ("2024061303", "600002481", "fhd", "深圳卫视"),
    "scws": ("2024061403", "600002516", "fhd", "四川卫视"),
    "henanws": ("2029797303", "600002525", "fhd", "河南卫视"),
    "fjdnhz": ("2024061503", "600002484", "fhd", "福建东南卫视"),
    "gzhws": ("2024061603", "600002490", "fhd", "贵州卫视"),
    "jxws": ("2024061703", "600002503", "fhd", "江西卫视"),
    "lnws": ("2024171303", "600002505", "fhd", "辽宁卫视"),
    "ahws": ("2024171403", "600002532", "fhd", "安徽卫视"),
    "hbws2": ("2024171503", "600002493", "fhd", "河北卫视"),
    "sdws": ("2029787903", "600002513", "fhd", "山东卫视"),
    "tjws": ("2019927003", "600152137", "fhd", "天津卫视"),
    "jlws": ("2025561503", "600190405", "fhd", "吉林卫视"),
    "shanxiws": ("2029795103", "600190400", "fhd", "陕西卫视"),
    "nxws": ("2025608503", "600190737", "fhd", "宁夏卫视"),
    "nmgws": ("2025561203", "600190401", "fhd", "内蒙古卫视"),
    "ynws": ("2025561303", "600190402", "fhd", "云南卫视"),
    "shanxiws2": ("2025560803", "600190407", "fhd", "山西卫视"),
    "qhws": ("2025559103", "600190406", "fhd", "青海卫视"),
    "xzws": ("2025558003", "600190403", "fhd", "西藏卫视"),
    "cetv1": ("2022823801", "600171827", "fhd", "中国教育电视台1频道"),
    "gxpd": ("2029360403", "600213139", "fhd", "国学频道"),
    "xjws": ("2019927403", "600152138", "fhd", "新疆卫视"),
}

# ================= 加密（TEA/PCBC，与 PHP CKeyManager 一致） =================
def _u32(v):
    return v & 0xFFFFFFFF


def _calc_signature(data):
    sig = 0
    for b in data:
        sig = (0x83 * sig + (b & 0xFF)) & 0x7FFFFFFF
    return sig


def _custom_encode(data):
    enc = base64.b64encode(data).decode('ascii')
    return enc.translate(str.maketrans(STD_ALPHA, CUS_ALPHA)).rstrip('=')


def _tea_crypt(inbuf, key, encrypt):
    if len(inbuf) < 8:
        inbuf = inbuf + b'\x00' * (8 - len(inbuf))
    y, z = struct.unpack('>II', inbuf[:8])
    k = struct.unpack('>IIII', key[:16])
    if encrypt:
        s = 0
        for _ in range(ROUNDS_TEA):
            s = _u32(s + DELTA)
            y = _u32(y + (((z << 4) + k[0]) ^ (z + s) ^ ((z >> 5) + k[1])))
            z = _u32(z + (((y << 4) + k[2]) ^ (y + s) ^ ((y >> 5) + k[3])))
    else:
        s = _u32(DELTA << 4)
        for _ in range(ROUNDS_TEA):
            z = _u32(z - (((y << 4) + k[2]) ^ (y + s) ^ ((y >> 5) + k[3])))
            y = _u32(y - (((z << 4) + k[0]) ^ (z + s) ^ ((z >> 5) + k[1])))
            s = _u32(s - DELTA)
    return struct.pack('>II', y, z)


def _oi_encrypt(inbuf, key, rng):
    pad_salt_body_zero = len(inbuf) + 1 + SALT_LEN + ZERO_LEN
    padlen = pad_salt_body_zero % 8
    if padlen:
        padlen = 8 - padlen
    out = bytearray()
    src = [0] * 8
    src[0] = (rng.randint(0, 255) & 0xF8) | padlen
    src_i = 1
    iv_plain = [0] * 8
    iv_crypt = [0] * 8

    def flush_block():
        nonlocal src, src_i, iv_plain, iv_crypt
        xored = [src[j] ^ iv_crypt[j] for j in range(8)]
        enc = _tea_crypt(bytes(xored), key, True)
        tmp = list(enc)
        tmp = [tmp[j] ^ iv_plain[j] for j in range(8)]
        out.extend(tmp)
        iv_plain = xored[:]
        iv_crypt = tmp[:]
        src = [0] * 8
        src_i = 0

    while padlen > 0:
        src[src_i] = rng.randint(0, 255)
        src_i += 1
        padlen -= 1
        if src_i == 8:
            flush_block()
    for _ in range(SALT_LEN):
        if src_i < 8:
            src[src_i] = rng.randint(0, 255)
            src_i += 1
        if src_i == 8:
            flush_block()
    for b in inbuf:
        if src_i < 8:
            src[src_i] = b & 0xFF
            src_i += 1
        if src_i == 8:
            flush_block()
    for _ in range(ZERO_LEN):
        if src_i < 8:
            src[src_i] = 0
            src_i += 1
        if src_i == 8:
            flush_block()
    if src_i > 0:
        for j in range(src_i, 8):
            src[j] = 0
        xored = [src[j] ^ iv_crypt[j] for j in range(8)]
        enc = _tea_crypt(bytes(xored), key, True)
        tmp = list(enc)
        tmp = [tmp[j] ^ iv_plain[j] for j in range(8)]
        out.extend(tmp)
    return bytes(out)


def _xor_array(data):
    return bytes((data[i] & 0xFF) ^ XOR_KEY[i & 0xF] for i in range(len(data)))


def _put_str16(buf, s):
    b = s.encode('utf-8')
    buf.extend(struct.pack('>H', len(b)))
    buf.extend(b)


def _build_packet(params, rng, guid):
    buf = bytearray()
    buf += bytes.fromhex("0000004200000004000004d2")
    buf += struct.pack('>I', params['Platform'])
    buf += struct.pack('>I', 0)
    buf += struct.pack('>I', params['Timestamp'])
    _put_str16(buf, params['Sdtfrom'])
    _put_str16(buf, params['randFlag'])
    _put_str16(buf, params['appVer'])
    _put_str16(buf, params['vid'])
    _put_str16(buf, params['guid'])
    buf += struct.pack('>I', 1)
    buf += struct.pack('>I', 1)
    _put_str16(buf, "2622783A")
    _put_str16(buf, "nil")
    _put_str16(buf, params['uuid4'])
    _put_str16(buf, "nil")
    _put_str16(buf, "v0.1.000")
    _put_str16(buf, "com.cctv.yangshipin.app.iphone")
    _put_str16(buf, "4330403")
    _put_str16(buf, "ex_json_bus")
    _put_str16(buf, "ex_json_vs")
    _put_str16(buf, params['ck_guard_time'])
    data = bytes(buf)
    packet = struct.pack('>H', len(data)) + data
    sig = _calc_signature(packet)
    packet = bytearray(packet)
    packet[18] = (sig >> 24) & 0xFF
    packet[19] = (sig >> 16) & 0xFF
    packet[20] = (sig >> 8) & 0xFF
    packet[21] = sig & 0xFF
    return bytes(packet)


def _generate_guid(rng):
    return ''.join(f'{rng.randint(0, 255):02x}' for _ in range(16))


def _make_uuid(rng):
    return ''.join([
        f'{rng.randint(0, 0xFFFF):04x}{rng.randint(0, 0xFFFF):04x}-',
        f'{rng.randint(0, 0xFFFF):04x}-{rng.randint(0, 0xFFF) | 0x4000:04x}-',
        f'{rng.randint(0, 0x3FFF) | 0x8000:04x}-',
        f'{rng.randint(0, 0xFFFF):04x}{rng.randint(0, 0xFFFF):04x}{rng.randint(0, 0xFFFF):04x}',
    ]).upper()


def _generate_ck_guard_time(timestamp, guid, rng):
    body = struct.pack('>I', timestamp)
    for part in [guid[-5:], "null"[-5:], "null"[-5:], "-1"]:
        pb = part.encode('utf-8')
        body += struct.pack('>H', len(pb)) + pb
    plain = struct.pack('>H', len(body)) + body
    encrypted = _oi_encrypt(plain, bytes.fromhex("110DBEC10C23E7D2E56A1CAD6914EF1B"), rng)
    checksum = _calc_signature(plain)
    with_sum = encrypted + struct.pack('>I', checksum)
    out = bytes((with_sum[i] & 0xFF) ^ [0xB3, 0xC9, 0x53, 0xA0, 0x69, 0x13, 0xAD, 0x4D][i & 7] for i in range(len(with_sum)))
    return out.hex().upper()


def _generate_ckey(cnlid, timestamp, rng, guid):
    params = {
        'Platform': 4330403, 'Timestamp': timestamp, 'Sdtfrom': 'dcgh',
        'vid': cnlid, 'guid': guid, 'appVer': 'V8.22.1035.3031',
        'randFlag': '_zj1A5Gh6QYcxWjIUGos2w==',
        'uuid4': '57eab0c4-2c58-44c6-8ae9-dd2757525dc5',
        'ck_guard_time': _generate_ck_guard_time(timestamp, guid, rng),
    }
    packet = _build_packet(params, rng, guid)
    encrypted = _oi_encrypt(packet, TEA_CKEY, rng)
    checksum = _calc_signature(packet)
    merged = encrypted + struct.pack('>I', checksum)
    return "--01" + _custom_encode(_xor_array(merged))


def _base_params(cnlid, livepid, defn, timestamp, guid, rng):
    return {
        'atime': '120', 'livepid': livepid, 'cnlid': cnlid, 'appVer': 'V8.22.1035.3031',
        'app_version': '300090', 'caplv': '1', 'cmd': '2', 'defn': defn, 'device': 'iPhone',
        'encryptVer': '4.2', 'getpreviewinfo': '0', 'hevclv': '33', 'lang': 'zh-Hans_JP',
        'livequeue': '0', 'logintype': '1', 'nettype': '1', 'newnettype': '1', 'newplatform': '4330403',
        'platform': '4330403', 'sdtfrom': 'v3021', 'spacode': '23', 'spaudio': '1', 'spdemuxer': '6',
        'spdrm': '2', 'spdynamicrange': '7', 'spflv': '1', 'spflvaudio': '1', 'sphdrfps': '60',
        'sphttps': '0',
        'spvcode': 'MSgzMDoyMTYwLDYwOjIxNjB8MzA6MjE2MCw2MDoyMTYwKTsyKDMwOjIxNjAsNjA6MjE2MHwzMDoyMTYwLDYwOjIxNjAp',
        'spvideo': '4', 'stream': '1', 'system': '1', 'sysver': 'ios18.2.1', 'uhd_flag': '4',
        'guid': guid, 'fntick': str(timestamp), 'flowid': _make_uuid(rng) + '_4330403',
    }


def _http_get(url, timeout):
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': 'application/json'})
    try:
        if _ssl_ctx is None:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.getcode(), resp.read()
        with urllib.request.urlopen(req, timeout=timeout, context=_ssl_ctx) as resp:
            return resp.getcode(), resp.read()
    except urllib.error.HTTPError as e:
        body = e.read() if e.fp else b''
        return e.code, body
    except (urllib.error.URLError, TimeoutError, OSError):
        return -1, b''
    except Exception:
        return -2, b''


# ================= 核心逻辑（对齐 PHP + Java 版经验） =================
try:
    # Android Chaquopy 下回调 Java 侧写运行时诊断日志；非 Android 环境为空实现
    from com.fongmi.chaquo import YspServer as _JLog

    def _jlog(msg):
        try:
            _JLog.log(msg)
        except Exception:
            pass
except Exception:
    def _jlog(msg):
        pass


class YspCore:
    def __init__(self):
        self.cache = {}          # id -> (playurl, ts)
        self.last_good = {}      # id -> (playurl, ts)
        self.last_good_m3u8 = {} # id -> (patched_m3u8_bytes, ts)
        self.placeholder_ts = {} # id -> last placeholder ts
        self._rng = random.Random()

    # ---- 取址 ----
    def request_api(self, cnlid, livepid, defn):
        guid = _generate_guid(self._rng)
        timestamp = int(time.time())
        params = _base_params(cnlid, livepid, defn, timestamp, guid, self._rng)
        params['cKey'] = _generate_ckey(cnlid, timestamp, self._rng, guid)
        params['playbacktime'] = '0'
        url = API + '?' + urllib.parse.urlencode(params)
        code, body = _http_get(url, API_TIMEOUT)
        if code == 200 and b'"playurl"' in body:
            m = re.search(rb'"playurl"\s*:\s*"([^"]+)"', body)
            if m:
                pu = m.group(1).decode('utf-8', errors='ignore')
                pu = pu.replace('\\/', '/').replace('\\u0026', '&')
                return pu
        _jlog('API取址失败 http=%s len=%d' % (code, len(body)))
        return None

    def get_playurl(self, cid):
        """playurl 缓存命中 → 直接返回；否则取址；失败 → lastGood 兜底"""
        now = time.time()
        c = self.cache.get(cid)
        if c and now - c[1] < PLAYURL_CACHE_SEC:
            return c[0]
        ch = CHANNELS.get(cid)
        if not ch:
            return None
        pu = self.request_api(ch[0], ch[1], ch[2])
        if pu:
            self.cache[cid] = (pu, now)
            return pu
        lg = self.last_good.get(cid)
        if lg and now - lg[1] < LAST_GOOD_SEC:
            _jlog('取址失败, 用lastGood兜底(age=%ds)' % int(now - lg[1]))
            return lg[0]
        return None

    # ---- M3U8 ----
    def fetch_m3u8(self, url):
        code, body = _http_get(url, M3U8_TIMEOUT)
        if code == 200 and b'#EXTM3U' in body:
            return body.decode('utf-8', errors='ignore')
        _jlog('拉M3U8失败 http=%s len=%d' % (code, len(body)))
        return None

    @staticmethod
    def _resolve_uri(uri, base_dir):
        u = uri.strip()
        if not u:
            return uri
        if re.match(r'^(https?://|data:)', u, re.I):
            return u
        scheme_host = base_dir[:base_dir.index('/', base_dir.index('://') + 3)]
        base_path = base_dir[base_dir.index('/', base_dir.index('://') + 3) + 1:]
        stack = []
        for part in (base_path + u).split('/'):
            if part == '..':
                if stack:
                    stack.pop()
            elif part != '.' and part != '':
                stack.append(part)
        return scheme_host + '/' + '/'.join(stack)

    @staticmethod
    def patch_ts(m3u8, playurl):
        # 防御：部分 CDN 节点返回 JSON 风格转义的切片行（...\u0026cdn=xxx.ts）
        m3u8 = m3u8.replace('\\u0026', '&')
        base_dir = playurl[:playurl.rfind('/') + 1]
        out = []
        for line in m3u8.split('\n'):
            l = line.rstrip('\r')
            low = l.lower()
            if low.startswith('#ext-x-key') or low.startswith('#ext-x-map'):
                out.append(re.sub(r'URI="([^"]*)"',
                                  lambda m: 'URI="' + YspCore._resolve_uri(m.group(1), base_dir) + '"',
                                  l, flags=re.I))
            elif l and l[0] != '#':
                out.append(YspCore._resolve_uri(l, base_dir))
            else:
                out.append(l)
        s = '\n'.join(out)
        # CDN 域名改写（PHP 版最后两条规则）：坏域 → 稳定域（同 token 实测）
        s = s.replace('outlivecloud-cdn.ysp.cctv.cn', 'hlsliveali-cdn.ysp.cctv.cn')
        s = re.sub(r'mobilelive-[^.]+\.ysp\.cctv\.cn', 'mobilelive-cnc-cdn.ysp.cctv.cn', s, flags=re.I)
        return s

    # ---- 响应组装 ----
    def placeholder(self, cid):
        now = time.time()
        last = self.placeholder_ts.get(cid, 0)
        if now - last >= 1:
            self.placeholder_ts[cid] = now
        return ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:3\n"
                "#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:EVENT\n").encode('utf-8')

    def serve_live(self, cid):
        """直播主流程（盒子 IP 稳定性版）：
        1) playurl 240s 缓存命中 → 拉 M3U8（失败不纠缠，直接走兜底）
        2) 取址/M3U8 失败 → lastGood M3U8 内容缓存兜底（旧 token 切片 240s 内有效，播放器不断流）
        3) lastGood playurl 再拉一次 → 仍失败 → 占位 M3U8（空 EVENT，播放器等刷新，不报 5xx）
        """
        for attempt in range(2):
            playurl = self.get_playurl(cid)
            if playurl is None:
                break
            m3u8 = self.fetch_m3u8(playurl)
            if m3u8 is not None:
                body = self.patch_ts(m3u8, playurl).encode('utf-8')
                self.last_good[cid] = (playurl, time.time())
                self.last_good_m3u8[cid] = (body, time.time())
                return body
            # M3U8 拉取失败 → 清缓存，短暂退避后下一轮重取（减少盒子 IP 连续取址触发限流）
            self.cache.pop(cid, None)
            if attempt == 0:
                time.sleep(1.0)
        # 双失败 → lastGood M3U8 内容兜底（最优先：不再发任何外网请求）
        lg_m = self.last_good_m3u8.get(cid)
        if lg_m and time.time() - lg_m[1] < LAST_GOOD_SEC:
            _jlog('双失败, lastGood M3U8 兜底(age=%ds)' % int(time.time() - lg_m[1]))
            return lg_m[0]
        # 再试 lastGood playurl 拉一次（旧 token 可能仍有短暂窗口）
        lg = self.last_good.get(cid)
        if lg and time.time() - lg[1] < LAST_GOOD_SEC:
            m3u8 = self.fetch_m3u8(lg[0])
            if m3u8 is not None:
                body = self.patch_ts(m3u8, lg[0]).encode('utf-8')
                self.last_good_m3u8[cid] = (body, time.time())
                return body
        _jlog('全部兜底失败, 返回占位M3U8')
        return self.placeholder(cid)

    def serve_debug(self, cid):
        """诊断端点：返回当前缓存状态 + 取址结果，断流时便于定位"""
        ch = CHANNELS.get(cid)
        if not ch:
            return "频道不存在".encode('utf-8')
        import json
        now = time.time()
        info = {'cid': cid, 'ts': int(now), 'host': HOST, 'port': PORT}
        c = self.cache.get(cid)
        info['playurl_cache'] = None if not c else {
            'age_s': int(now - c[1]), 'ttl_s': max(0, int(PLAYURL_CACHE_SEC - (now - c[1]))),
            'domain': c[0].split('/')[2] if '/' in c[0] else '?'}
        lg = self.last_good.get(cid)
        info['last_good_playurl'] = None if not lg else {
            'age_s': int(now - lg[1]), 'domain': lg[0].split('/')[2] if '/' in lg[0] else '?'}
        lg_m = self.last_good_m3u8.get(cid)
        info['last_good_m3u8'] = None if not lg_m else {
            'age_s': int(now - lg_m[1]), 'len': len(lg_m[0])}
        # 实时取址一次（用于观察盒子出口是否被限流）
        guid = _generate_guid(self._rng)
        timestamp = int(time.time())
        params = _base_params(ch[0], ch[1], ch[2], timestamp, guid, self._rng)
        params['cKey'] = _generate_ckey(ch[0], timestamp, self._rng, guid)
        params['playbacktime'] = '0'
        url = API + '?' + urllib.parse.urlencode(params)
        code, body = _http_get(url, API_TIMEOUT)
        info['live_api'] = {'http': code, 'bytes': len(body)}
        if code == 200 and b'"playurl"' in body:
            m = re.search(rb'"playurl"\s*:\s*"([^"]+)"', body)
            if m:
                pu = m.group(1).decode('utf-8', errors='ignore').replace('\\/', '/').replace('\\u0026', '&')
                info['live_api']['playurl'] = pu[:200]
                info['live_api']['domain'] = pu.split('/')[2] if '://' in pu else '?'
        return json.dumps(info, ensure_ascii=False).encode('utf-8')

    def list_live(self):
        sb = ["央视频,#genre#"]
        for cid, (_, _, _, name) in CHANNELS.items():
            sb.append(f"{name},http://{HOST}:{PORT}/ysp?id={cid}#")
        return "\n".join(sb).encode('utf-8')


_core = YspCore()


# ================= HTTP 服务 =================
class YspHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        try:
            parsed = urllib.parse.urlsplit(self.path)
            if not parsed.path.startswith('/ysp'):
                self.send_error(404)
                return
            qs = urllib.parse.parse_qs(parsed.query)
            cid = qs.get('id', ['cctv1'])[0]
            if 'list' in qs:
                body = _core.list_live()
                self._send(200, 'text/plain; charset=utf-8', body)
                return
            if qs.get('debug', ['0'])[0] == '1':
                self._send(200, 'application/json; charset=utf-8', _core.serve_debug(cid))
                return
            body = _core.serve_live(cid)
            self._send(200, 'application/vnd.apple.mpegurl', body)
        except Exception:
            try:
                self._send(200, 'application/vnd.apple.mpegurl',
                           b"#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:3\n"
                           b"#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:EVENT\n")
            except Exception:
                pass

    def _send(self, code, ctype, body):
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(body)


_server = None


def start(port=PORT):
    """启动 YSP 代理 HTTP 服务（幂等）。由 Java 侧在应用启动后调用。"""
    global _server
    if _server is not None:
        return True
    try:
        _server = ThreadingHTTPServer((HOST, port), YspHandler)
        t = threading.Thread(target=_server.serve_forever, daemon=True)
        t.start()
        return True
    except Exception:
        _server = None
        return False


def stop():
    global _server
    if _server is not None:
        try:
            _server.shutdown()
        except Exception:
            pass
        _server = None


# ================= 独立运行（服务器长测用） =================
if __name__ == '__main__':
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == '--selftest':
        # 自测：模拟播放器 8s 刷新，跑 ROUNDS 轮（默认 60 轮 ≈ 8 分钟）
        rounds = int(sys.argv[2]) if len(sys.argv) > 2 else 60
        interval = 8
        stats = {'api_slow': 0, 'api_fail': 0, 'm3u8_fail': 0, 'seg_fail': 0, 'ok': 0}
        print(f"自测: {rounds}轮 × {interval}s | {time.strftime('%H:%M:%S')}", flush=True)
        t0 = time.time()
        for rnd in range(1, rounds + 1):
            t_a = time.time()
            pu = _core.get_playurl('cctv1')
            dt = time.time() - t_a
            if dt > 2:
                stats['api_slow'] += 1
                print(f"[{rnd:3d}] ⚠️ API慢 {dt:.1f}s", flush=True)
            if pu is None:
                stats['api_fail'] += 1
                print(f"[{rnd:3d}] ❌ API失败", flush=True)
                time.sleep(interval)
                continue
            m3u8 = _core.fetch_m3u8(pu)
            if m3u8 is None:
                stats['m3u8_fail'] += 1
                print(f"[{rnd:3d}] ❌ M3U8失败", flush=True)
                time.sleep(interval)
                continue
            out = _core.patch_ts(m3u8, pu)
            segs = [l.strip() for l in out.splitlines() if 'http' in l and '.ts' in l.lower()]
            if segs:
                code, data = _http_get(segs[0], 8)
                if code == 200 and len(data) > 100000:
                    stats['ok'] += 1
                    print(f"[{rnd:3d}] OK {len(data)//1024}KB", flush=True)
                else:
                    stats['seg_fail'] += 1
                    print(f"[{rnd:3d}] ❌ SEG {code} {len(data)}B {segs[0][:110]}", flush=True)
            time.sleep(interval)
        print(f"\n=== 自测结果（{int(time.time()-t0)}s） ===", flush=True)
        print(f"成功 {stats['ok']}/{rounds} | API失败 {stats['api_fail']} | API慢 {stats['api_slow']} | M3U8失败 {stats['m3u8_fail']} | 切片失败 {stats['seg_fail']}", flush=True)
    else:
        ok = start()
        print(f"YSP 代理已启动: http://{HOST}:{PORT}/ysp?list=live (ok={ok})", flush=True)
        while True:
            time.sleep(3600)
