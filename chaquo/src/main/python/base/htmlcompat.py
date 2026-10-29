"""HTML 解析兼容层。

背景：本项目需要在 Android 5.0（API 21）上运行 Python 3.11。Chaquopy 15.0.1
的原生包仓库中，lxml 只有 cp38 版本提供 android_21 标签的 wheel，
cp311（即 Python 3.11）仅有 android_24 标签的 wheel，无法在 API 21 上安装。

因此这里提供一个轻量的兼容实现：
  * 若 lxml 可用（例如用户在 Android 7.0+ 的设备上、或改用 API 24 构建），
    直接使用 lxml.etree，行为与原来完全一致；
  * 若 lxml 不可用，则回退到标准库 html.parser 实现一个最小可用的
    etree.HTML 替身，支持常见用法：
        root.xpath('//a/@href')
        root.findall('.//div')
        root.find('//title')
        el.text / el.attrib / el.get('href')
        el.xpath('./text()')
"""

import re

try:
    from lxml import etree

    HAS_LXML = True
except ImportError:
    etree = None
    HAS_LXML = False


class _Element:
    """最小化的 Element 实现，接口对齐 lxml 元素的常用子集。"""

    def __init__(self, tag='div', attrib=None, text=None):
        self.tag = tag
        self.attrib = dict(attrib or {})
        self.text = text
        self._children = []
        self._parent = None

    # ---- 树结构 ----

    def append(self, child):
        child._parent = self
        self._children.append(child)
        return child

    @property
    def children(self):
        return list(self._children)

    def iter(self, tag=None):
        """深度优先遍历自身及所有后代。"""
        if tag is None or tag == self.tag:
            yield self
        for child in self._children:
            for node in child.iter(tag):
                yield node

    # ---- 查找 ----

    def findall(self, path, namespaces=None):
        """支持 './/tag'、'//tag' 与 'tag' 三种简单路径。"""
        return list(self._findall(path))

    def _findall(self, path):
        path = (path or '').strip()
        if path.startswith('.//'):
            tag = path[3:]
            for node in self.iter(tag):
                yield node
        elif path.startswith('//'):
            tag = path[2:]
            for node in self.iter(tag):
                yield node
        else:
            tag = path
            for node in self.iter(tag):
                yield node

    def find(self, path, namespaces=None):
        for node in self._findall(path):
            return node
        return None

    def findall_(self, path):
        return self.findall(path)

    def getchildren(self):
        return list(self._children)

    # ---- 属性 ----

    def get(self, key, default=None):
        return self.attrib.get(key, default)

    def set(self, key, value):
        self.attrib[key] = value

    # ---- XPath（仅支持常见的子集）----

    def xpath(self, path, namespaces=None):
        """支持常见 XPath 子集：

            //tag              所有后代中名为 tag 的元素
            //tag/@attr        所有 tag 元素的 attr 属性值
            .//tag             同 //tag（从当前元素起）
            ./tag              直接子元素
            ./text()           当前元素的直接文本
            string(.)          当前元素全部文本
        """
        if not isinstance(path, str):
            raise ValueError('unsupported xpath: %r' % (path,))

        expr = path.strip()

        # string(.)
        if expr == 'string(.)':
            return self.text_content()

        # ./text()
        if expr in ('./text()', 'text()'):
            return self.text if self.text else ''

        # 提取 @attr
        attr = None
        m = re.search(r'/@([A-Za-z_][\w:.-]*)\s*$', expr)
        if m:
            attr = m.group(1)
            expr = expr[:m.start()]

        # ./tag
        if expr.startswith('./'):
            tag = expr[2:]
            nodes = [c for c in self._children if c.tag == tag]
        elif expr.startswith('//'):
            tag = expr[2:]
            nodes = [n for n in self.iter(tag)]
        elif expr == '.':
            nodes = [self]
        else:
            tag = expr
            nodes = [n for n in self.iter(tag)]

        if attr is None:
            return nodes

        values = []
        for node in nodes:
            val = node.attrib.get(attr)
            if val is not None:
                values.append(val)
        return values

    # ---- 文本 ----

    def text_content(self):
        parts = []
        if self.text:
            parts.append(self.text)
        for child in self._children:
            parts.append(child.text_content())
        return ''.join(parts)

    def itertext(self):
        if self.text:
            yield self.text
        for child in self._children:
            for t in child.itertext():
                yield t

    def __repr__(self):
        return '<Element %s at 0x%x>' % (self.tag, id(self))


class _HTMLParser:
    """基于标准库 html.parser 的极简 HTML → 树 转换器。"""

    VOID_TAGS = {
        'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input',
        'link', 'meta', 'param', 'source', 'track', 'wbr',
    }

    def __init__(self):
        self.root = _Element('html')
        self._stack = [self.root]

    def handle_starttag(self, tag, attrs):
        attrib = {}
        for k, v in attrs:
            attrib[k] = v if v is not None else ''
        el = _Element(tag, attrib)
        self._stack[-1].append(el)
        if tag not in self.VOID_TAGS:
            self._stack.append(el)

    def handle_startendtag(self, tag, attrs):
        attrib = {}
        for k, v in attrs:
            attrib[k] = v if v is not None else ''
        self._stack[-1].append(_Element(tag, attrib))

    def handle_endtag(self, tag):
        # 容错：向上回溯到匹配的开始标签
        for i in range(len(self._stack) - 1, 0, -1):
            if self._stack[i].tag == tag:
                del self._stack[i:]
                return

    def handle_data(self, data):
        if not data:
            return
        el = self._stack[-1]
        if el.text is None:
            el.text = data
        else:
            el.text += data


def _parse_with_stdlib(content):
    try:
        from html.parser import HTMLParser
    except ImportError:  # pragma: no cover - Python 3 不会走到这里
        raise

    holder = _HTMLParser()

    class _Adapter(HTMLParser):
        def handle_starttag(self, tag, attrs):
            holder.handle_starttag(tag, attrs)

        def handle_startendtag(self, tag, attrs):
            holder.handle_startendtag(tag, attrs)

        def handle_endtag(self, tag):
            holder.handle_endtag(tag)

        def handle_data(self, data):
            holder.handle_data(data)

    parser = _Adapter(convert_charrefs=True)
    if isinstance(content, bytes):
        content = content.decode('utf-8', errors='replace')
    try:
        parser.feed(content)
        parser.close()
    except Exception:
        # 解析失败时返回已解析的部分，保证调用方不崩溃
        pass
    return holder.root


def HTML(content):
    """解析 HTML，返回根元素。

    与 lxml.etree.HTML 的用法保持一致：
        root = etree.HTML(html)
        root.xpath('//a/@href')
    """
    if HAS_LXML:
        return etree.HTML(content)
    return _parse_with_stdlib(content)


def fromstring(content, parser=None):
    if HAS_LXML:
        return etree.fromstring(content, parser)
    root = _parse_with_stdlib(content)
    children = root.children
    return children[0] if children else root
