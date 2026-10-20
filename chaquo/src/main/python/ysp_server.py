# -*- coding: utf-8 -*-
"""内置央视频直播服务（Chaquopy 启动入口）。

内嵌 ysp-live v9.5 全频道直播代理（Gary's Club 方案，纯标准库实现）：
- 订阅地址：http://127.0.0.1:8767/all.m3u（64 路央视/卫视独立频道）
- 播放地址：http://127.0.0.1:8767/<slug>.m3u8（由 v9.5 服务自行取流/兜底/时移）
- 诊断地址：http://127.0.0.1:8767/health 与 /diag

同时保留 9980 局域网扫码绑定服务（TMDB API Key 绑定页，
手机扫码打开 http://<电视IP>:9980/bind?type=tmdb 提交后写回本机）。
"""
import json
import os
import socket
import sys
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

YSP_PORT = 8767
YSP_HOST = '127.0.0.1'
BIND_PORT = 9980

# 非 Android 环境（本地自测）为空实现；Chaquopy 下回调 Java 写诊断日志
try:
    from com.fongmi.chaquo import YspServer as _J

    def _jlog(msg):
        try:
            _J.log(msg)
        except Exception:
            pass

    def _data_dir():
        """App 私有可写目录（v9.5 状态持久化用）。"""
        try:
            from com.github.catvod import Init
            ctx = Init.context()
            d = ctx.getExternalFilesDir(None)
            if d is None:
                d = ctx.getFilesDir()
            p = os.path.join(str(d), 'ysp')
            os.makedirs(p, exist_ok=True)
            return p
        except Exception:
            return ''
except Exception:
    def _jlog(msg):
        pass

    def _data_dir():
        return os.environ.get('YSP_DATA_DIR', '')


# ================= ysp-live v9.5 内嵌启动 =================

_v95_ready = False


def _run_v95():
    """在独立线程中启动 v9.5 代理（main 内部 serve_forever 常驻）。"""
    global _v95_ready
    try:
        import ysp_live_v95 as core
        old_argv = sys.argv
        argv = ['ysp-live', str(YSP_PORT), '--bind', YSP_HOST, '--no-engine', '--cache-mb', '96']
        data_dir = _data_dir()
        if data_dir:
            argv += ['--data-dir', data_dir]
        sys.argv = argv
        try:
            core.main()  # 阻塞常驻
        finally:
            sys.argv = old_argv
    except Exception as e:
        _jlog('ysp v9.5 启动失败: %s' % e)


def _wait_ysp_ready(timeout=20.0):
    """轮询等待 v9.5 端口可连接（模块导入 + 服务绑定完成）。"""
    global _v95_ready
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection((YSP_HOST, YSP_PORT), timeout=0.5):
                _v95_ready = True
                return True
        except Exception:
            time.sleep(0.2)
    _jlog('ysp v9.5 就绪等待超时 (%ss)' % timeout)
    return False


# ================= TMDB 扫码绑定服务（9980） =================

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
                _J.saveTmdbKey(key)
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


_bind_server = None


def _start_bind():
    """启动 9980 局域网扫码绑定服务（TMDB Key）。"""
    global _bind_server
    if _bind_server is not None:
        return
    try:
        _bind_server = ThreadingHTTPServer(('0.0.0.0', BIND_PORT), BindHandler)
        threading.Thread(target=_bind_server.serve_forever, daemon=True).start()
        _jlog('扫码绑定服务启动成功 9980')
    except Exception as e:
        _jlog('扫码绑定服务启动失败 %s' % e)


# ================= Chaquopy 启动入口 =================

def start():
    """Java 侧 YspServer.start() 调用：拉起 v9.5 内嵌服务并等待就绪后返回。

    就绪后 Java 侧才会置 started=true（LiveConfig 默认源加载前 awaitReady）。
    """
    _start_bind()
    threading.Thread(target=_run_v95, daemon=True, name='ysp-v95').start()
    _wait_ysp_ready(20.0)
    if _v95_ready:
        _jlog('ysp v9.5 就绪: http://%s:%d/all.m3u' % (YSP_HOST, YSP_PORT))


if __name__ == '__main__':
    start()
    print('ysp 内置服务: http://%s:%d/all.m3u' % (YSP_HOST, YSP_PORT))
    threading.Event().wait()
