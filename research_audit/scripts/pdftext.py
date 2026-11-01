"""Standard-library-only PDF text extraction, enough for a Word- or LaTeX-made paper.

Inflates every FlateDecode stream, reads each font's ToUnicode CMap, and decodes the text
shown by Tj, TJ, ' and " operators page by page, in content-stream order.

    python pdftext.py IN.pdf OUT.txt
"""
import re
import sys
import zlib

data = open(sys.argv[1], 'rb').read()

# Every object: number -> raw bytes (dictionary plus any stream).
objects = {}
for m in re.finditer(rb'(\d+)\s+(\d+)\s+obj\b(.*?)\bendobj', data, re.S):
    objects[int(m.group(1))] = m.group(3)


def stream_of(body):
    m = re.search(rb'stream\r?\n(.*?)\r?\n?endstream', body, re.S)
    if not m:
        return None
    raw = m.group(1)
    if b'FlateDecode' in body.split(b'stream', 1)[0]:
        try:
            return zlib.decompress(raw)
        except zlib.error:
            try:
                return zlib.decompressobj().decompress(raw)
            except zlib.error:
                return None
    return raw


# Object streams (PDF 1.5) hold further objects.
for _num, body in list(objects.items()):
    if re.search(rb'/Type\s*/ObjStm', body):
        s = stream_of(body)
        if not s:
            continue
        n = int(re.search(rb'/N\s+(\d+)', body).group(1))
        first = int(re.search(rb'/First\s+(\d+)', body).group(1))
        header = s[:first].split()
        pairs = [(int(header[i]), int(header[i + 1])) for i in range(0, 2 * n, 2)]
        for i, (onum, off) in enumerate(pairs):
            end = pairs[i + 1][1] if i + 1 < len(pairs) else len(s) - first
            objects.setdefault(onum, s[first + off:first + end])


def ref(body, key):
    m = re.search(rb'/' + key + rb'\s+(\d+)\s+0\s+R', body)
    return int(m.group(1)) if m else None


def parse_cmap(text):
    table = {}
    for block in re.findall(rb'beginbfchar(.*?)endbfchar', text, re.S):
        for src, dst in re.findall(rb'<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>', block):
            table[bytes.fromhex(src.decode())] = bytes.fromhex(dst.decode()).decode('utf-16-be', 'replace')
    for block in re.findall(rb'beginbfrange(.*?)endbfrange', text, re.S):
        for m in re.finditer(rb'<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*(\[.*?\]|<[0-9A-Fa-f]+>)', block, re.S):
            lo, hi = int(m.group(1), 16), int(m.group(2), 16)
            width = len(m.group(1)) // 2
            dst = m.group(3)
            if dst.startswith(b'['):
                outs = re.findall(rb'<([0-9A-Fa-f]+)>', dst)
                for i, code in enumerate(range(lo, hi + 1)):
                    if i < len(outs):
                        table[code.to_bytes(width, 'big')] = bytes.fromhex(outs[i].decode()).decode('utf-16-be', 'replace')
            else:
                base = bytes.fromhex(dst[1:-1].decode())
                start = int.from_bytes(base, 'big')
                for i, code in enumerate(range(lo, hi + 1)):
                    table[code.to_bytes(width, 'big')] = (start + i).to_bytes(len(base), 'big').decode('utf-16-be', 'replace')
    return table


# Fonts: object number -> (cmap, code width)
font_maps = {}
for num, body in objects.items():
    if re.search(rb'/Type\s*/Font', body):
        tu = ref(body, b'ToUnicode')
        cmap = {}
        if tu and tu in objects:
            s = stream_of(objects[tu])
            if s:
                cmap = parse_cmap(s)
        width = 2 if b'/Type0' in body else 1
        font_maps[num] = (cmap, width)


def decode_string(raw, font):
    cmap, width = font_maps.get(font, ({}, 1))
    out = []
    i = 0
    while i < len(raw):
        chunk = raw[i:i + width]
        if chunk in cmap:
            out.append(cmap[chunk])
        elif width == 1:
            out.append(chr(chunk[0]))
        else:
            out.append('?')
        i += width
    return ''.join(out)


def literal(s):
    """Unescapes a PDF literal string body."""
    out = bytearray()
    i = 0
    while i < len(s):
        c = s[i]
        if c == 0x5C and i + 1 < len(s):  # backslash
            n = s[i + 1]
            esc = {ord('n'): 10, ord('r'): 13, ord('t'): 9, ord('b'): 8, ord('f'): 12}
            if n in esc:
                out.append(esc[n]); i += 2
            elif 0x30 <= n <= 0x37:
                j = i + 1
                while j < len(s) and j < i + 4 and 0x30 <= s[j] <= 0x37:
                    j += 1
                out.append(int(s[i + 1:j], 8) & 0xFF); i = j
            elif n in (10, 13):
                i += 2
            else:
                out.append(n); i += 2
        else:
            out.append(c); i += 1
    return bytes(out)


token = re.compile(rb'\((?:\\.|[^\\()]|\((?:\\.|[^\\()])*\))*\)|<[0-9A-Fa-f\s]*>|\[|\]|/[^\s/\[\]()<>]+|-?\d*\.?\d+|[A-Za-z\'"*]+', re.S)


def page_text(content, resources):
    fonts = {}
    for name, num in re.findall(rb'/([^\s/]+)\s+(\d+)\s+0\s+R', resources):
        fonts[name] = int(num)
    lines, current, font, stack = [], [], None, []
    last_y = None
    for t in token.findall(content):
        if t.startswith(b'/'):
            stack.append(t)
        elif t == b'Tf':
            font = fonts.get(stack[-2][1:]) if len(stack) >= 2 else None
            stack = []
        elif t in (b'Tj', b"'", b'"'):
            for s in stack:
                if s.startswith(b'('):
                    current.append(decode_string(literal(s[1:-1]), font))
                elif s.startswith(b'<'):
                    current.append(decode_string(bytes.fromhex(re.sub(rb'\s', b'', s[1:-1]).decode()), font))
            stack = []
        elif t == b'TJ':
            for s in stack:
                if s.startswith(b'('):
                    current.append(decode_string(literal(s[1:-1]), font))
                elif s.startswith(b'<'):
                    current.append(decode_string(bytes.fromhex(re.sub(rb'\s', b'', s[1:-1]).decode()), font))
                elif re.fullmatch(rb'-?\d*\.?\d+', s) and float(s) < -200:
                    current.append(' ')
            stack = []
        elif t in (b'Td', b'TD', b'Tm', b'T*', b'ET'):
            y = None
            if t in (b'Td', b'TD') and len(stack) >= 2:
                try:
                    y = float(stack[-1])
                except ValueError:
                    pass
            if t == b'Tm' and len(stack) >= 6:
                try:
                    y = float(stack[-1])
                except ValueError:
                    pass
            new_line = t in (b'T*', b'ET') or (t in (b'Td', b'TD') and y not in (None, 0.0)) or (t == b'Tm' and y != last_y)
            if t == b'Tm':
                last_y = y
            if new_line and current:
                lines.append(''.join(current)); current = []
            elif current and t in (b'Td', b'TD'):
                current.append(' ')
            stack = []
        else:
            stack.append(t)
    if current:
        lines.append(''.join(current))
    return '\n'.join(l.rstrip() for l in lines)


# Pages in order: follow the page tree from the catalog.
catalog = next(b for b in objects.values() if re.search(rb'/Type\s*/Catalog', b))
pages_root = ref(catalog, b'Pages')
ordered = []


def walk(num, inherited):
    body = objects[num]
    res = inherited
    rm = re.search(rb'/Resources\s+(\d+)\s+0\s+R', body)
    if rm:
        res = objects.get(int(rm.group(1)), b'')
    else:
        rm = re.search(rb'/Resources\s*<<(.*?)>>\s*(?:/|>>)', body, re.S)
        if rm:
            res = rm.group(0)
    if re.search(rb'/Type\s*/Pages', body):
        kids = re.search(rb'/Kids\s*\[(.*?)\]', body, re.S).group(1)
        for k in re.findall(rb'(\d+)\s+0\s+R', kids):
            walk(int(k), res)
    else:
        ordered.append((num, res))


walk(pages_root, b'')

out = []
for i, (num, res) in enumerate(ordered, 1):
    body = objects[num]
    fm = re.search(rb'/Font\s+(\d+)\s+0\s+R', res)
    font_res = objects.get(int(fm.group(1)), b'') if fm else res
    cm = re.search(rb'/Contents\s*(\[(.*?)\]|(\d+)\s+0\s+R)', body, re.S)
    nums = [int(x) for x in re.findall(rb'(\d+)\s+0\s+R', cm.group(1))] if cm else []
    content = b''.join(stream_of(objects[n]) or b'' for n in nums if n in objects)
    out.append(f'\n===== PAGE {i} =====\n' + page_text(content, font_res))

open(sys.argv[2], 'w', encoding='utf-8').write(''.join(out))
print(f'{len(ordered)} pages, {sum(len(x) for x in out)} characters')
