# -*- coding: utf-8 -*-
"""局域网扫码绑定服务（TMDB API Key），Chaquopy 内嵌。

手机扫电视上的二维码 → 打开 http://<电视IP>:9980/bind?type=tmdb →
在手机上填 API Key → POST /bind/submit → 回调 Java 写入 Prefers（刮削立即生效）。

注：本服务原先是 migu_server.py 的一部分（同时承担咪咕账号绑定），
咪咕代码整体移除后只保留与咪咕无关的 TMDB 绑定，二维码入口在 TmdbDialog。
"""
import json
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BIND_PORT = 9980

try:
    from com.fongmi.chaquo import YspServer as _J

    def _jlog(msg):
        try:
            _J.log(msg)
        except Exception:
            pass

    def _save_key(key):
        _J.saveTmdbKey(key)
except Exception:
    def _jlog(msg):
        pass

    def _save_key(key):
        raise RuntimeError('java bridge unavailable')


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
  var fd=new FormData();fd.append('key',key);fd.append('type','tmdb');
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

    def do_GET(self):
        try:
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.path != '/bind':
                self.send_error(404)
                return
            # 页面只读，不做 token 拦截（避免手机端打不开页面）；写入本身就是一次性 API Key
            body = _TMDB_PAGE.encode('utf-8')
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
            key = (form.get('key') or [''])[0].strip()
            if not key:
                self._json(400, {'ok': False, 'msg': 'API Key 不能为空'})
                return
            try:
                _save_key(key)
                _jlog('扫码绑定TMDB成功 key=%s' % key[:8])
                self._json(200, {'ok': True})
            except Exception as e:
                _jlog('扫码绑定TMDB失败 %s' % e)
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


_server = None
_start_lock = threading.Lock()


def start(port=BIND_PORT):
    """Chaquopy 启动入口：0.0.0.0:9980 常驻（仅局域网扫码用），幂等。"""
    global _server
    with _start_lock:
        if _server is not None:
            return
        try:
            _server = ThreadingHTTPServer(('0.0.0.0', int(port)), BindHandler)
            threading.Thread(target=_server.serve_forever, daemon=True).start()
            _jlog('扫码绑定服务启动成功 %d' % int(port))
        except Exception as e:
            _server = None
            _jlog('扫码绑定服务启动失败 %s' % e)
