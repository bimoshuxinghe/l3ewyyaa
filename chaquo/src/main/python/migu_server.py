# -*- coding: utf-8 -*-
"""咪咕直播内置代理（Python 版，Chaquopy 内嵌）。

对齐 github.com/develop202/migu_video（Node 版）核心逻辑：
- 频道列表：咪咕 tv-data 接口按分类拉取（央视/卫视/地方/体育…各自分组，不做大杂烩）
- 取流：Android 720p 签名（md5 + sign + salt）+ ddCalcu 纯字符变换（无需 wasm）
- 播放：302 重定向到咪咕 CDN（播放器直连，与原项目一致）
- 缓存：频道列表 6h / 取流 URL 3h（对齐原项目）

直播源地址：http://127.0.0.1:9979/migu?list=live
频道地址：  http://127.0.0.1:9979/migu/{pid}
回放：      http://127.0.0.1:9979/migu/{pid}?playbackbegin=YYYYMMDDHHmmss&playbackend=YYYYMMDDHHmmss
"""
import hashlib
import json
import random
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = '127.0.0.1'
PORT = 9979
UA = 'okhttp/3.12.1'
APP_VERSION = '2600034600'
CHANNEL_ID_PREFIX = APP_VERSION + '-99000-201600010010028'
API_CATE = 'https://program-sc.miguvideo.com/live/v2/tv-data/%s'
API_PLAY = 'https://play.miguvideo.com/playurl/v1/play/playurl'
# 任意 vomsID 返回全部分类 liveList
CATE_VOMS = '1ff892f2b5ab4a79be6e25b69d2f5d05'
CATE_CACHE_SEC = 6 * 3600
URL_CACHE_SEC = 3 * 3600

# 非 Android 环境（本地自测）为空实现；Chaquopy 下回调 Java 写诊断日志
try:
    from com.fongmi.chaquo import MiguServer as _J

    def _jlog(msg):
        try:
            _J.log(msg)
        except Exception:
            pass

    def _get_account():
        """读 Java 侧保存的咪咕账号：'uid|token'，无账号返回 ('','')。"""
        try:
            s = _J.getAccount()
            if s and '|' in s:
                uid, tok = s.split('|', 1)
                if uid and tok:
                    return uid, tok
        except Exception:
            pass
        return '', ''
except Exception:
    def _jlog(msg):
        pass

    def _get_account():
        return '', ''


def _md5(s):
    return hashlib.md5(s.encode('utf-8')).hexdigest()


def _http_get(url, timeout=12, headers=None):
    h = {'User-Agent': UA}
    if headers:
        h.update(headers)
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout)
        return r.getcode(), r.read()
    except urllib.error.HTTPError as e:
        try:
            return e.code, e.read()
        except Exception:
            return e.code, b''
    except Exception:
        return -1, b''


class MiguCore:
    def __init__(self):
        self._cates_cache = None  # [(name, vomsID, [{'name','pID','logo'},...]), ...]
        self._cates_ts = 0.0
        self.url_cache = {}      # pid -> (playurl, ts)
        self._rng = random.Random()
        self.stat = {'req': 0, 'cate_ok': 0, 'cate_fail': 0,
                     'play_ok': 0, 'play_fail': 0}
        self.last_err = {}

    # ================= 频道数据 =================
    def _fetch_cates(self):
        """拉全部分类及频道。返回 [(name, vomsID, chans), ...]，失败返回 None。"""
        code, body = _http_get(API_CATE % CATE_VOMS, timeout=12)
        if code != 200:
            self.stat['cate_fail'] += 1
            self.last_err['cates'] = '频道接口http=%s' % code
            _jlog('MIGU频道接口失败 http=%s' % code)
            return None
        try:
            d = json.loads(body.decode('utf-8', errors='ignore'))
        except Exception:
            self.stat['cate_fail'] += 1
            return None
        ll = (d.get('body') or {}).get('liveList') or []
        out = []
        for c in ll:
            name = c.get('name')
            if not name or name == '热门':
                continue
            out.append([name, c.get('vomsID', ''), []])
        # 每个分类拉频道（保留接口顺序：央视/卫视/地方/体育…）
        for c in out:
            code2, body2 = _http_get(API_CATE % c[1], timeout=12)
            if code2 == 200:
                try:
                    d2 = json.loads(body2.decode('utf-8', errors='ignore'))
                    dl = (d2.get('body') or {}).get('dataList') or []
                    c[2] = [{'name': x.get('name'), 'pID': str(x.get('pID', '')),
                             'logo': ((x.get('pics') or {}).get('highResolutionH') or '')}
                            for x in dl]
                except Exception:
                    pass
            time.sleep(0.05)
        self.stat['cate_ok'] += 1
        return out

    def get_cates(self):
        now = time.time()
        if self._cates_cache is not None and now - self._cates_ts < CATE_CACHE_SEC:
            return self._cates_cache
        c = self._fetch_cates()
        if c:
            self._cates_cache = c
            self._cates_ts = now
        return self._cates_cache or []

    # ================= 取流 =================
    def play_url(self, pid):
        """有账号 → 登录取流(1080p蓝光, 非会员自动降级)；无账号 → 免费720p。缓存3h。"""
        uid, tok = _get_account()
        if uid and tok:
            return self.play_url_login(pid, uid, tok)
        return self.play_url_720p(pid)

    def play_url_720p(self, pid):
        """未登录 720p 取流（对齐 getAndroidURL720p + getddCalcuURL720p），缓存 3h。"""
        now = time.time()
        c = self.url_cache.get(pid)
        if c and now - c[1] < URL_CACHE_SEC:
            return c[0]
        ts = str(int(time.time() * 1000))
        client_id = _md5(str(int(time.time() * 1000)))
        headers = {
            'AppVersion': APP_VERSION,
            'TerminalId': 'android',
            'X-UP-CLIENT-CHANNEL-ID': CHANNEL_ID_PREFIX,
            'ClientId': client_id,
            'User-Agent': UA,
        }
        # cctv5 / cctv5+ 开 flv 后不能回放，原项目不加 appCode
        if pid not in ('641886683', '641886773'):
            headers['appCode'] = 'miguvideo_default_android'
        m = _md5(ts + pid + APP_VERSION[:8])
        salt = str(self._rng.randint(0, 999999)).zfill(6) + '25'
        sign = _md5(m + '2cac4f2c6c3346a5b34e085725ef7e33migu' + salt[:4])
        qs = ('sign=%s&rateType=3&contId=%s&timestamp=%s&salt=%s'
              '&flvEnable=true&super4k=true&h265N=true' % (sign, pid, ts, salt))
        url = API_PLAY + '?' + qs
        code, body = _http_get(url, timeout=12, headers=headers)
        if code != 200:
            self.stat['play_fail'] += 1
            self.last_err[pid] = '取流http=%s' % code
            _jlog('MIGU取流失败 %s http=%s' % (pid, code))
            return None
        try:
            d = json.loads(body.decode('utf-8', errors='ignore'))
        except Exception:
            self.stat['play_fail'] += 1
            return None
        ui = (d.get('body') or {}).get('urlInfo') or {}
        purl = ui.get('url')
        if not purl:
            self.stat['play_fail'] += 1
            rid = d.get('rid') or d.get('message') or '?'
            self.last_err[pid] = '取流无url:%s' % rid
            _jlog('MIGU取流无url %s %s' % (pid, rid))
            return None
        purl = purl + '&ddCalcu=' + self._dd_calcu_720p(purl, pid) + '&sv=10004&ct=android'
        self.url_cache[pid] = (purl, now)
        self.stat['play_ok'] += 1
        return purl

    def play_url_login(self, pid, user_id, token, rate_type=4):
        """登录取流（对齐 getAndroidURL + getddCalcuURL）：蓝光1080p，非会员自动降级。"""
        now = time.time()
        key = pid + ':' + user_id
        c = self.url_cache.get(key)
        if c and now - c[1] < URL_CACHE_SEC:
            return c[0]
        ts = str(int(time.time() * 1000))
        app_version = '2600037000'
        headers = {
            'AppVersion': app_version,
            'TerminalId': 'android',
            'X-UP-CLIENT-CHANNEL-ID': '2600037000-99000-200300220100002',
            'UserId': user_id,
            'UserToken': token,
            'User-Agent': UA,
        }
        if pid not in ('641886683', '641886773'):
            headers['appCode'] = 'miguvideo_default_android'
        m = _md5(ts + pid + app_version)
        salt = '1230024'
        sign = _md5(m + '3ce941cc3cbc40528bfd1c64f9fdf6c0migu0123')

        def _req(rt):
            qs = ('sign=%s&rateType=%s&contId=%s&timestamp=%s&salt=%s'
                  '&flvEnable=true&super4k=true' % (sign, rt, pid, ts, salt))
            if rt == '9':
                qs += '&ott=true'
            qs += '&h265N=true&4kvivid=true&2Kvivid=true&vivid=2'
            code, body = _http_get(API_PLAY + '?' + qs, timeout=12, headers=headers)
            if code != 200:
                return None, 'http=%s' % code
            try:
                return json.loads(body.decode('utf-8', errors='ignore')), None
            except Exception:
                return None, 'json'

        d, err = _req(rate_type)
        if err is not None:
            self.stat['play_fail'] += 1
            self.last_err[pid] = '登录取流%s' % err
            _jlog('MIGU登录取流失败 %s %s' % (pid, err))
            return None
        # 非会员降级（对齐原项目 TIPS_NEED_MEMBER 三级）
        if d.get('rid') == 'TIPS_NEED_MEMBER':
            rt2 = 4 if int((d.get('body') or {}).get('urlInfo') or {}).get('rateType', 0) > 4 else 3
            d2, err = _req(rt2)
            if d2 and d2.get('rid') == 'TIPS_NEED_MEMBER':
                d2, err = _req(3)
            if d2:
                d = d2
        ui = (d.get('body') or {}).get('urlInfo') or {}
        purl = ui.get('url')
        if not purl:
            self.stat['play_fail'] += 1
            rid = d.get('rid') or d.get('message') or '?'
            self.last_err[pid] = '登录取流无url:%s' % rid
            _jlog('MIGU登录取流无url %s %s' % (pid, rid))
            return None
        purl = purl + '&ddCalcu=' + self._dd_calcu(purl, pid, rate_type, user_id) + '&sv=10004&ct=android'
        self.url_cache[key] = (purl, now)
        self.stat['play_ok'] += 1
        return purl

    def _dd_calcu_720p(self, purl, pid):
        """纯字符变换，对齐 getddCalcuURL720p（旧版无需 wasm）。"""
        if '&puData=' not in purl:
            return ''
        pu = purl.split('&puData=')[1]
        keys = 'cdabyzwxkl'
        date3 = time.strftime('%Y%m%d')[2]
        out = []
        for i in range(len(pu) // 2):
            out.append(pu[len(pu) - i - 1])
            out.append(pu[i])
            if i == 1:
                out.append('v')
            elif i == 2:
                out.append(keys[int(date3)])
            elif i == 3:
                out.append(keys[int(pid[6])])
            elif i == 4:
                out.append('a')
        return ''.join(out)

    def _dd_calcu(self, purl, pid, rate_type, user_id):
        """登录版 ddCalcu（对齐 getddCalcuURL 纯字符变换，无需 wasm）。"""
        if '&puData=' not in purl:
            return ''
        pu = purl.split('&puData=')[1]
        keys = 'cdabyzwxkl'
        words = ['v', 'a', '0', 'a']
        third = 6
        if user_id and len(user_id) > 7 and user_id[7].isdigit():
            words[0] = keys[int(user_id[7])]
        if rate_type == 2:
            words[0] = 'v'
        if user_id and 3 < len(user_id) <= 8:
            words[0] = 'e'
        date0 = time.strftime('%Y%m%d')[0]
        out = []
        for i in range(len(pu) // 2):
            out.append(pu[len(pu) - i - 1])
            out.append(pu[i])
            if i == 1:
                out.append(words[0])
            elif i == 2:
                out.append(keys[int(date0)])
            elif i == 3:
                out.append(keys[int(pid[third])])
            elif i == 4:
                out.append(words[3])
        return ''.join(out)

    # ================= 直播源列表 =================
    def list_live(self):
        """按分类分组输出（央视/卫视/地方…各自独立，不合并）。"""
        lines = ['咪咕,#genre#']
        for name, _v, chans in self.get_cates():
            lines.append('咪咕-%s,#genre#' % name)
            for ch in chans:
                if not ch['pID'] or not ch['name']:
                    continue
                lines.append('%s,http://%s:%d/migu/%s#'
                             % (ch['name'], HOST, PORT, ch['pID']))
        return '\n'.join(lines).encode('utf-8')

    def serve(self, pid, params):
        """频道请求：取流成功 → (302, Location)；失败 → (200, 文本)。"""
        self.stat['req'] += 1
        purl = self.play_url(pid)
        if not purl:
            msg = ('取流失败: ' + self.last_err.get(pid, '?')).encode('utf-8')
            return 200, ('text/plain; charset=utf-8', msg)
        if params:
            purl = purl + '&' + params
        return 302, purl


_core = MiguCore()


# ================= HTTP 服务 =================
class MiguHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        try:
            parsed = urllib.parse.urlsplit(self.path)
            if not parsed.path.startswith('/migu'):
                self.send_error(404)
                return
            qs = urllib.parse.parse_qs(parsed.query)
            if 'list' in qs:
                body = _core.list_live()
                self.send_response(200)
                self.send_header('Content-Type', 'text/plain; charset=utf-8')
                self.send_header('Content-Length', str(len(body)))
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                self.wfile.write(body)
                return
            pid = parsed.path[len('/migu/'):].split('/')[0]
            if not pid or not pid.isdigit():
                self.send_error(404)
                return
            params = parsed.query
            code, resp = _core.serve(pid, params)
            if code == 302:
                self.send_response(302)
                self.send_header('Location', resp)
                self.send_header('Content-Length', '0')
                self.end_headers()
            else:
                body = resp[1]
                self.send_response(200)
                self.send_header('Content-Type', 'text/plain; charset=utf-8')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        except Exception:
            try:
                self.send_error(500)
            except Exception:
                pass


_server = None


def start():
    """Chaquopy 启动入口：绑定 9979 并常驻。"""
    global _server
    if _server is not None:
        return
    try:
        _server = ThreadingHTTPServer((HOST, PORT), MiguHandler)
        t = threading.Thread(target=_server.serve_forever, daemon=True)
        t.start()
        _jlog('MIGU代理启动成功 9979')
    except Exception as e:
        _jlog('MIGU代理启动失败 %s' % e)
        raise


def _selftest():
    """本地自测：频道列表 + 取流 + master 拉取。"""
    c = MiguCore()
    cs = c.get_cates()
    print('分类数:', len(cs))
    for name, _v, chans in cs:
        print('  %s: %d个频道' % (name, len(chans)))
    if cs:
        pid = cs[0][2][0]['pID'] if cs[0][2] else '608807420'
        print('首个频道:', cs[0][2][0] if cs[0][2] else '?')
        u = c.play_url(pid)
        print('取流:', (u or 'FAIL')[:120])
        if u:
            code, body = _http_get(u, timeout=12)
            print('master m3u8 http=%s len=%d' % (code, len(body)))
            if code == 200:
                print(body.decode(errors='ignore')[:300])
    print('stat:', c.stat)


if __name__ == '__main__':
    import sys
    if '--selftest' in sys.argv:
        _selftest()
    else:
        start()
        print('MIGU 代理: http://%s:%d/migu?list=live' % (HOST, PORT))
        threading.Event().wait()
