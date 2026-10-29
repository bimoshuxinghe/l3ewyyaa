# -*- coding: utf-8 -*-
"""PyQuery 纯 Python 兼容层（兜底实现）。

背景：大量 TV 站源使用 `from pyquery import PyQuery as pq` 解析 HTML。
真实的 pyquery 硬依赖 lxml，而 Chaquopy 15.0.1 的原生包仓库中 lxml
没有 Python 3.11 / android_21 (Android 5.0) 的 wheel，无法随包安装。

本文件实现站源常用的 PyQuery 子集：
    pq(html)                      解析（支持 str / bytes）
    pq(html)('script')            CSS 选择器（tag、.class、#id、[attr]、
                                  [attr=v]、[attr^=]、[attr$=]、[attr*=]，
                                  支持后代空格、'>' 子级、',' 分组）
    .items() / __iter__ / __len__ / .length / __getitem__
    .text() / .html() / .attr('x') / .eq(i) / .find(sel) / .filter(sel)
    .children() / .parent() / .each(fn) / .remove() / .val()
    .text(v) / .html(v) / .attr(k, v) / .remove_attr(k) 写操作

注意：
  * 若设备上通过 pip 安装了真实 pyquery（lxml 可用的构建），真实包
    优先于本模块（Python 的包目录优先于同名 .py 模块），本文件自动失效；
  * 站源用不到的 pyquery 高级 API（ajax 等）不在此实现。
"""

import re

try:
    from base import htmlcompat
except Exception:
    # 站源可能以 SourceFileLoader 方式单独加载本文件（无包上下文），
    # 按文件路径动态加载 base/htmlcompat.py。
    import os
    from importlib.machinery import SourceFileLoader
    from importlib.util import spec_from_loader, module_from_spec

    _here = os.path.dirname(os.path.abspath(__file__))
    _candidates = [
        os.path.join(_here, 'base', 'htmlcompat.py'),
        os.path.join(os.path.dirname(_here), 'base', 'htmlcompat.py'),
    ]
    htmlcompat = None
    for _p in _candidates:
        if os.path.exists(_p):
            _loader = SourceFileLoader('htmlcompat', _p)
            _spec = spec_from_loader('htmlcompat', _loader)
            htmlcompat = module_from_spec(_spec)
            _loader.exec_module(htmlcompat)
            break
    if htmlcompat is None:
        raise ImportError('pyquery fallback: base/htmlcompat.py not found')

_no_default = object()


# ---------------------------------------------------------------------------
# 元素操作辅助（同时兼容 lxml._Element 与 htmlcompat._Element）
# ---------------------------------------------------------------------------

def _tc(el):
    """元素的全部文本内容。"""
    if hasattr(el, 'text_content'):
        return el.text_content()
    return ''.join(el.itertext())


def _kids(el):
    """直接子元素列表。"""
    return list(el)


def _parent_of(el):
    p = el.getparent() if hasattr(el, 'getparent') else getattr(el, '_parent', None)
    return p




# ---------------------------------------------------------------------------
# CSS 选择器（迷你实现）
# ---------------------------------------------------------------------------

_ATTR_RE = re.compile(
    r'^\[([A-Za-z_:][\w:.-]*)\s*([~^$*|]?=)?\s*(.*?)\s*\]$')


def _unescape(v):
    v = v.strip()
    if len(v) >= 2 and v[0] == v[-1] and v[0] in ('"', "'"):
        return v[1:-1]
    return v


def _match_simple(el, simple):
    """单个简单选择器是否匹配元素。"""
    if not simple:
        return True
    for part in re.findall(
            r'\[.*?\]|[.#][\w-]+|\*|[A-Za-z][\w-]*|\*', simple):
        if part == '*':
            continue
        if part.startswith('.'):
            cls = el.get('class') or ''
            if part[1:] not in cls.split():
                return False
        elif part.startswith('#'):
            if el.get('id') != part[1:]:
                return False
        elif part.startswith('['):
            m = _ATTR_RE.match(part)
            if not m:
                return False
            name, op, val = m.group(1), m.group(2), _unescape(m.group(3) or '')
            actual = el.get(name)
            if actual is None:
                return False
            if op:
                if op == '=' and actual != val:
                    return False
                elif op == '~=' and val not in actual.split():
                    return False
                elif op == '^=' and not actual.startswith(val):
                    return False
                elif op == '$=' and not actual.endswith(val):
                    return False
                elif op == '*=' and val not in actual:
                    return False
                elif op == '|=' and actual != val and not actual.startswith(val + '-'):
                    return False
        else:  # tag
            if el.tag != part.lower():
                return False
    return True


def _match_selector(el, selector):
    """完整选择器（可含后代空格与 '>'）是否匹配，从 el 向上回溯。"""
    tokens = re.findall(r'[^>\s]+|>', selector.strip())
    if not tokens:
        return True

    # 解析为 simples[i] 与 combs[i]（combs[i] 连接 simples[i] 与 simples[i+1]）
    simples = [tokens[0]]
    combs = []
    i = 1
    while i < len(tokens):
        if tokens[i] == '>':
            combs.append('>')
            i += 1
            if i < len(tokens):
                simples.append(tokens[i])
                i += 1
        else:
            combs.append(' ')
            simples.append(tokens[i])
            i += 1

    def match_up(node, idx):
        """node 匹配 simples[idx]，其祖先匹配 simples[idx-1..0]。

        CSS 语义：候选元素对应最后一个简单选择器，祖先逐级向前匹配。
        """
        if not _match_simple(node, simples[idx]):
            return False
        if idx == 0:
            return True
        comb = combs[idx - 1]
        parent = _parent_of(node)
        if comb == '>':
            return parent is not None and match_up(parent, idx - 1)
        while parent is not None:
            if match_up(parent, idx - 1):
                return True
            parent = _parent_of(parent)
        return False

    return match_up(el, len(simples) - 1)


def _select(root, selector):
    """按 ',' 分组的选择器在文档中选取元素（保持文档顺序）。"""
    groups = [g.strip() for g in selector.split(',') if g.strip()]
    hits = []
    for el in root.iter():
        for g in groups:
            if _match_selector(el, g):
                hits.append(el)
                break
    return hits


# ---------------------------------------------------------------------------
# PyQuery
# ---------------------------------------------------------------------------

class PyQuery(object):

    def __init__(self, *args, **kwargs):
        self._elements = []
        if not args:
            return
        first = args[0]
        if isinstance(first, str) and first.strip().startswith('<'):
            # 直接传入 HTML 片段
            self._elements = [htmlcompat.fromstring(first)]
        elif isinstance(first, (bytes, bytearray)):
            self._elements = [htmlcompat.fromstring(bytes(first).decode('utf-8', 'replace'))]
        elif isinstance(first, str):
            # 当作 URL/文档文本按 HTML 解析（站源通常传 fetch().text() 的正文）
            self._elements = [htmlcompat.fromstring(first)]
        else:
            # 元素或元素列表
            if isinstance(first, (list, tuple)):
                self._elements = [e for e in first if e is not None]
            else:
                self._elements = [first]

    # ---- 基础 ----

    @property
    def length(self):
        return len(self._elements)

    def __len__(self):
        return len(self._elements)

    def __iter__(self):
        for el in self._elements:
            yield el

    def __getitem__(self, key):
        if isinstance(key, slice):
            return self._wrap(self._elements[key])
        return self._wrap([self._elements[key]])

    def __bool__(self):
        return bool(self._elements)

    def __repr__(self):
        return '<PyQuery [%d elements]>' % len(self._elements)

    def _wrap(self, elements):
        obj = PyQuery.__new__(PyQuery)
        obj._elements = list(elements)
        return obj

    # ---- 选择 ----

    def __call__(self, selector=None, **kwargs):
        if selector is None:
            return self
        return self.find(selector)

    def find(self, selector):
        hits = []
        for el in self._elements:
            for e in _select(el, selector):
                if e not in hits:
                    hits.append(e)
        return self._wrap(hits)

    def filter(self, selector):
        return self._wrap([e for e in self._elements if _match_selector(e, selector)])

    def not_(self, selector):
        return self._wrap([e for e in self._elements if not _match_selector(e, selector)])

    def eq(self, index):
        try:
            return self._wrap([self._elements[index]])
        except IndexError:
            return self._wrap([])

    def first(self):
        return self.eq(0)

    def last(self):
        return self.eq(-1)

    def items(self):
        for el in self._elements:
            yield self._wrap([el])

    def each(self, func):
        for i, el in enumerate(self._elements):
            func(i, self._wrap([el]))
        return self

    def children(self, selector=None):
        hits = []
        for el in self._elements:
            for c in el.children:
                if selector is None or _match_selector(c, selector):
                    if c not in hits:
                        hits.append(c)
        return self._wrap(hits)

    def parent(self):
        hits = []
        for el in self._elements:
            p = _parent_of(el)
            if p is not None and p not in hits:
                hits.append(p)
        return self._wrap(hits)

    def parents(self):
        hits = []
        for el in self._elements:
            p = _parent_of(el)
            while p is not None:
                if p not in hits:
                    hits.append(p)
                p = p._parent
        return self._wrap(hits)

    def closest(self, selector=None):
        return self.parents().filter(selector) if selector else self.parent()

    def siblings(self, selector=None):
        hits = []
        for el in self._elements:
            p = _parent_of(el)
            if p is None:
                continue
            for c in p.children:
                if c is el:
                    continue
                if selector is None or _match_selector(c, selector):
                    if c not in hits:
                        hits.append(c)
        return self._wrap(hits)

    def add(self, selector):
        for e in self.find(selector)._elements:
            if e not in self._elements:
                self._elements.append(e)
        return self

    # ---- 文本 / HTML ----

    def text(self, value=_no_default):
        if value is _no_default:
            return ' '.join(
                t for t in (_tc(el).strip() for el in self._elements) if t)
        for el in self._elements:
            el.text = str(value)
            el._children = []
        return self

    def html(self, value=_no_default):
        if value is _no_default:
            if not self._elements:
                return ''
            el = self._elements[0]
            parts = []
            if el.text:
                parts.append(el.text)
            for c in _kids(el):
                parts.append(self._serialize(c))
            return ''.join(parts)
        for el in self._elements:
            inner = htmlcompat.fromstring('<div>%s</div>' % value)
            if hasattr(el, '_children'):
                el._children = list(inner.children)
            else:  # lxml：append 新子元素
                for c in inner.children:
                    el.append(c)
        return self

    def _serialize(self, el):
        attrs = ''.join(
            ' %s="%s"' % (k, v) for k, v in el.attrib.items())
        inner = el.text or ''
        for c in _kids(el):
            inner += self._serialize(c)
        if el.tag in ('br', 'img', 'input', 'hr', 'meta', 'link'):
            return '<%s%s/>' % (el.tag, attrs)
        return '<%s%s>%s</%s>' % (el.tag, attrs, inner, el.tag)

    # ---- 属性 ----

    def attr(self, *args):
        if not self._elements:
            return None
        if len(args) >= 2:
            self._elements[0].set(args[0], str(args[1]))
            return self
        name = args[0] if args else None
        if isinstance(name, dict):
            for k, v in name.items():
                self._elements[0].set(k, str(v))
            return self
        return self._elements[0].get(name)

    def remove_attr(self, name):
        for el in self._elements:
            el.attrib.pop(name, None)
        return self

    def val(self, value=_no_default):
        if value is _no_default:
            return self.attr('value')
        return self.attr('value', value)

    # ---- 结构修改 ----

    def remove(self):
        for el in list(self._elements):
            p = _parent_of(el)
            if p is not None and el in p._children:
                p._children.remove(el)
        self._elements = []
        return self

    def empty(self):
        for el in self._elements:
            el._children = []
        return self

    def append(self, other):
        if isinstance(other, PyQuery):
            for el in self._elements:
                for c in other._elements:
                    el.append(c)
        elif isinstance(other, str):
            for el in self._elements:
                frag = htmlcompat.fromstring('<div>%s</div>' % other)
                for c in _kids(frag):
                    el.append(c)
        return self


def fromstring(html):
    """快捷入口：解析 HTML 并返回 PyQuery。"""
    return PyQuery(html)


# 兼容部分站源直接引用的 attr 对象用法（仅占位，不序列化）
class _AttrNamespace(object):
    pass


PyQuery.attrNS = _AttrNamespace()
