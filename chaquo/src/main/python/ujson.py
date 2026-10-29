# -*- coding: utf-8 -*-
"""ujson 纯 Python 兜底实现。

许多 TV 站源使用 `import ujson`。真实的 ujson 是 C 扩展，Chaquopy 15.0.1
的原生包仓库中没有 Python 3.11 / android_21 (Android 5.0) 的 wheel。
此模块把 ujson 的常用 API 映射到标准库 json（ultrajson 子集）。

若构建中通过 pip 安装了真实 ujson（原生 .so），Python 的文件查找器
会优先加载扩展模块，本文件自动失效。
"""

import json as _json

__version__ = '5.10.0.fallback'


def loads(s, *args, **kwargs):
    return _json.loads(s, *args, **kwargs)


def load(fp, *args, **kwargs):
    return _json.load(fp, *args, **kwargs)


def dumps(obj, *args, **kwargs):
    if 'ensure_ascii' not in kwargs:
        kwargs['ensure_ascii'] = False
    indent = kwargs.pop('indent', 0) or None
    return _json.dumps(obj, indent=indent, *args, **kwargs)


def dump(obj, fp, *args, **kwargs):
    fp.write(dumps(obj, *args, **kwargs))
