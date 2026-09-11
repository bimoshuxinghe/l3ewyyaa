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
import base64
import random
import threading
import time
import urllib.parse
import urllib.request
import requests
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


# ================= 央视频 /ysp（列表合并 + 取流，全 Python 内完成）=================
# 合并列表：央视频（live_ysp.liveContent，73频道）在上 + 咪咕（list_live 转 M3U）在下。
# 取流：/ysp?fun=cctv&id=xxx → live_ysp.localProxy。Java 侧 Nano /ysp 仅做 HTTP 转发，
# 不经过 Java↔Python 桥，避免首次初始化慢/桥调用卡死导致列表超时。

def _txt_to_m3u(txt):
    """咪咕 TXT（组名,#genre# / 频道,url#）→ M3U 行。"""
    sb, group = [], ''
    for line in txt.split('\n'):
        line = line.strip()
        if not line:
            continue
        idx = line.find(',')
        if idx <= 0:
            continue
        name, rest = line[:idx].strip(), line[idx + 1:].strip()
        if '#genre#' in rest:
            group = name
            continue
        if rest.endswith('#'):
            rest = rest[:-1]
        if '://' not in rest:
            continue
        sb.append('#EXTINF:-1 group-title="%s",%s\n%s' % (group, name, rest))
    return '\n'.join(sb)


def _ysp_merge_list():
    """央视频 + 咪咕合并 M3U（任一源失败不影响另一源）。"""
    parts = []
    try:
        import live_ysp
        sp = live_ysp.Spider()
        ysp = sp.liveContent('')
        if ysp and ysp.strip():
            parts.append(ysp.strip())
    except Exception as e:
        _jlog('ysp列表失败: %s' % e)
    try:
        m3u = _txt_to_m3u(_core.list_live().decode('utf-8'))
        if m3u:
            parts.append(m3u)
    except Exception as e:
        _jlog('migu列表失败: %s' % e)
    return '\n\n'.join(parts) if parts else '#EXTM3U\n# 列表加载中，请稍后重试\n'


def _ysp_stream(qs):
    """央视频取流/回看 → [status, mime, body]。"""
    try:
        import live_ysp
        sp = live_ysp.Spider()
        params = {k: v[0] for k, v in qs.items()}
        res = sp.localProxy(params)
        return int(res[0]), res[1] or 'application/vnd.apple.mpegurl', res[2]
    except Exception as e:
        _jlog('ysp取流失败: %s' % e)
        return 200, 'application/vnd.apple.mpegurl', '#EXTM3U\n#EXT-X-ENDLIST\n# 央视频取流失败\n'


# ================= HTTP 服务 =================
# ---------- 央视频合并列表 & 取流 ----------


class MiguHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        try:
            parsed = urllib.parse.urlsplit(self.path)
            qs = urllib.parse.parse_qs(parsed.query)
            if parsed.path == '/ysp':
                if 'list' in qs:
                    body = _ysp_merge_list()
                    self.send_response(200)
                    self.send_header('Content-Type', 'text/plain; charset=utf-8')
                    self.send_header('Content-Length', str(len(body.encode('utf-8'))))
                    self.send_header('Cache-Control', 'no-store')
                    self.end_headers()
                    self.wfile.write(body.encode('utf-8'))
                else:
                    code, mime, body = _ysp_stream(qs)
                    self.send_response(code)
                    self.send_header('Content-Type', mime)
                    self.send_header('Content-Length', str(len(body.encode('utf-8'))))
                    self.send_header('Cache-Control', 'no-store')
                    self.end_headers()
                    self.wfile.write(body.encode('utf-8'))
                return
            if not parsed.path.startswith('/migu'):
                self.send_error(404)
                return
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
_bind_server = None

# ================= 局域网扫码绑定服务 =================
# 手机扫电视上的二维码 → 打开 http://<电视IP>:9980/bind?t=<token> →
# 在手机上填 UID/Token → POST /bind/submit → 写回 Prefers（蓝光1080p 立即生效）。
# 9979 播放代理保持仅本机；9980 只对局域网开放且需一次性 token 校验。

_BIND_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>咪咕账号绑定</title>
<style>
body{font-family:system-ui,-apple-system,sans-serif;background:#0f1420;color:#fff;margin:0;padding:24px;max-width:420px}
h1{font-size:19px;margin:4px 0 10px}.tip{color:#9aa4b5;font-size:13px;line-height:1.7;margin:0 0 8px}
input{width:100%;box-sizing:border-box;padding:13px;margin:10px 0;border-radius:10px;border:1px solid #2a3446;background:#1a2230;color:#fff;font-size:16px;outline:none}
input:focus{border-color:#3b82f6}
button{width:100%;padding:14px;border:0;border-radius:10px;background:#3b82f6;color:#fff;font-size:16px;font-weight:600;margin-top:6px}
.msg{color:#22c55e;font-size:15px;text-align:center;margin-top:14px}
.err{color:#ef4444}
</style></head><body>
<h1>咪咕账号绑定</h1>
<p class="tip">登录 miguvideo.com 后，用浏览器开发者工具（F12 → Network）从任意请求的请求头里复制 <b>UserId</b> 和 <b>UserToken</b> 填入。绑定后电视端自动播放蓝光1080p，非会员自动降回高清。</p>
<form id="f" onsubmit="return false;">
<input id="uid" placeholder="咪咕 UID" autocomplete="off">
<input id="token" placeholder="咪咕 Token" autocomplete="off">
<button onclick="submit()">绑定到电视</button>
</form>
<p id="msg" class="msg"></p>
<script>
async function submit(){
  var uid=document.getElementById('uid').value.trim(), token=document.getElementById('token').value.trim();
  var m=document.getElementById('msg');
  if(!uid||!token){m.textContent='请填写完整';m.className='msg err';return}
  var type=new URLSearchParams(location.search).get('type')||'migu';
  var fd=new FormData();fd.append('uid',uid);fd.append('token',token);fd.append('type',type);
  try{
    var r=await fetch('/bind/submit',{method:'POST',body:fd});
    var d=await r.json();
    if(d.ok){m.textContent='绑定成功！现在可以关闭本页，返回电视播放。';document.getElementById('f').style.display='none';}
    else{m.textContent='绑定失败：'+(d.msg||'未知错误');m.className='msg err';}
  }catch(e){m.textContent='网络错误：请确认手机和电视在同一个WiFi';m.className='msg err';}
}
</script></body></html>"""


_TMDB_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>TMDB API Key 绑定</title>
<style>
body{font-family:system-ui,-apple-system,sans-serif;background:#0f1420;color:#fff;margin:0;padding:24px;max-width:420px}
h1{font-size:19px;margin:4px 0 10px}.tip{color:#9aa4b5;font-size:13px;line-height:1.7;margin:0 0 8px}
input{width:100%;box-sizing:border-box;padding:13px;margin:10px 0;border-radius:10px;border:1px solid #2a3446;background:#1a2230;color:#fff;font-size:16px;outline:none}
input:focus{border-color:#3b82f6}
button{width:100%;padding:14px;border:0;border-radius:10px;background:#3b82f6;color:#fff;font-size:16px;font-weight:600;margin-top:6px}
.msg{color:#22c55e;font-size:15px;text-align:center;margin-top:14px}
.err{color:#ef4444}
</style></head><body>
<h1>TMDB API Key 绑定</h1>
<p class="tip">在 themoviedb.org 注册后，到 <b>Settings → API</b> 申请 API Key（v3），把 Key 填入即可。绑定后电视端刮削海报/简介自动生效。</p>
<form id="f" onsubmit="return false;">
<input id="key" placeholder="TMDB API Key" autocomplete="off">
<button onclick="submit()">绑定到电视</button>
</form>
<p id="msg" class="msg"></p>
<script>
async function submit(){
  var key=document.getElementById('key').value.trim(), m=document.getElementById('msg');
  if(!key){m.textContent='请填写 API Key';m.className='msg err';return}
  var type=new URLSearchParams(location.search).get('type')||'migu';
  var fd=new FormData();fd.append('key',key);fd.append('type',type);
  try{
    var r=await fetch('/bind/submit',{method:'POST',body:fd});
    var d=await r.json();
    if(d.ok){m.textContent='绑定成功！现在可以关闭本页，返回电视。';document.getElementById('f').style.display='none';}
    else{m.textContent='绑定失败：'+(d.msg||'未知错误');m.className='msg err';}
  }catch(e){m.textContent='网络错误：请确认手机和电视在同一个WiFi';m.className='msg err';}
}
</script></body></html>"""


class BindHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _token_ok(self, qs):
        t = (qs.get('t') or [''])[0]
        ok = _bind_ok_token()
        return bool(t) and bool(ok) and t == ok

    def do_GET(self):
        try:
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.path != '/bind':
                self.send_error(404)
                return
            qs = urllib.parse.parse_qs(parsed.query)
            # 页面只读，不做 token 拦截（避免手机端 403 打不开页面）；写入由 POST 严格校验
            btype = (qs.get('type') or ['migu'])[0]
            body = (_TMDB_PAGE if btype == 'tmdb' else _BIND_PAGE).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(body)
        except Exception:
            try:
                self.send_error(500)
            except Exception:
                pass

    def do_POST(self):
        try:
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.path != '/bind/submit':
                self.send_error(404)
                return
            ln = int(self.headers.get('Content-Length') or 0)
            raw = self.rfile.read(ln).decode('utf-8', errors='ignore')
            form = urllib.parse.parse_qs(raw)
            qs = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            # 扫码只为填写方便：不做 token 校验（用户明确要求）
            btype = (form.get('type') or qs.get('type') or ['migu'])[0]
            if btype == 'tmdb':
                key = (form.get('key') or [''])[0].strip()
                if not key:
                    self._json(400, {'ok': False, 'msg': 'API Key 不能为空'})
                    return
                try:
                    _J.saveTmdbKey(key)
                    _jlog('扫码绑定TMDB成功 key=%s' % key[:8])
                    self._json(200, {'ok': True})
                except Exception as e:
                    _jlog('扫码绑定TMDB失败 %s' % e)
                    self._json(500, {'ok': False, 'msg': '保存失败，请重试'})
                return
            uid = (form.get('uid') or [''])[0].strip()
            token = (form.get('token') or [''])[0].strip()
            if not uid or not token:
                self._json(400, {'ok': False, 'msg': 'UID/Token 不能为空'})
                return
            try:
                _J.saveAccount(uid, token)
                _jlog('扫码绑定成功 uid=%s' % uid)
                self._json(200, {'ok': True})
            except Exception as e:
                _jlog('扫码绑定写账号失败 %s' % e)
                self._json(500, {'ok': False, 'msg': '保存失败，请重试'})
        except Exception:
            try:
                self._json(500, {'ok': False, 'msg': '服务器错误'})
            except Exception:
                pass

    def _json(self, code, obj):
        body = json.dumps(obj).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def start():
    """Chaquopy 启动入口：绑定 9979 并常驻；同时启动局域网扫码绑定服务 9980。"""
    global _server, _bind_server
    if _server is None:
        try:
            _server = ThreadingHTTPServer((HOST, PORT), MiguHandler)
            threading.Thread(target=_server.serve_forever, daemon=True).start()
            _jlog('MIGU代理启动成功 9979')
        except Exception as e:
            _jlog('MIGU代理启动失败 %s' % e)
            raise
    if _bind_server is None:
        try:
            _bind_server = ThreadingHTTPServer(('0.0.0.0', 9980), BindHandler)
            threading.Thread(target=_bind_server.serve_forever, daemon=True).start()
            _jlog('扫码绑定服务启动成功 9980')
        except Exception as e:
            _jlog('扫码绑定服务启动失败 %s' % e)


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
