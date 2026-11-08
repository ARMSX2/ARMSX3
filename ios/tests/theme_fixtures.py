# SPDX-License-Identifier: GPL-2.0-only
"""Synthetic builders for the PS3 theme guards; a helper, not a test. Every zlib stream, P3T, GIM, GTF, DXT, RAF scene,
Edge skeleton and clip and VSMX script is built here in memory; the only file read is the committed mesh hex in
Android's own test sources."""

import re
import struct
import zlib


def zlib_stream(data, level):
    """RFC 1950 zlib: header, DEFLATE, big-endian Adler-32."""
    return zlib.compress(data, level)


def raw_deflate(data):
    """RFC 1951 DEFLATE with no header and no trailer."""
    packer = zlib.compressobj(9, zlib.DEFLATED, -15)
    return packer.compress(data) + packer.flush()


def zlib_with_header(cmf, flg, raw):
    """Two header bytes chosen by the caller, then `raw`, as given (no trailer is added)."""
    return bytes([cmf, flg]) + raw


def valid_flg(cmf, fdict=False):
    """The smallest FLG whose (CMF * 256 + FLG) is a multiple of 31, with FDICT set or clear as asked."""
    for flg in range(256):
        if (cmf * 256 + flg) % 31 == 0 and bool(flg & 0x20) == fdict:
            return flg
    raise AssertionError("no FLG for CMF {0:#x}".format(cmf))


def p3t(elements, strings, files, string_offset_override=None):
    """A P3T file: a 64-byte header (magic, version 0x110, six (offset, size) pairs), then the element, string
    and file tables, the id, integer and float tables left empty, as P3tThemeTest.Theme.bytes() writes it."""
    header = 64
    e_off = header
    i_off = e_off + len(elements)
    s_off = i_off
    n_off = s_off + len(strings)
    f_off = n_off
    s_at = s_off if string_offset_override is None else string_offset_override
    out = b"P3TF" + struct.pack(">I", 0x110)
    out += struct.pack(">II", e_off, len(elements))                   # elements
    out += struct.pack(">II", i_off, 0)                               # ids
    out += struct.pack(">II", s_at, len(strings))                     # strings
    out += struct.pack(">IIII", n_off, 0, n_off, 0)                   # integers, floats
    out += struct.pack(">II", f_off, len(files))                      # files
    out += bytes(header - len(out))
    return out + bytes(elements) + bytes(strings) + bytes(files)


class Theme:
    """P3tThemeTest.kt's `Theme`: a name table, elements of 28 bytes with 16-byte attributes, and a file table."""

    def __init__(self):
        self.strings = bytearray()
        self.names = {}
        self.elements = bytearray()
        self.files = bytearray()

    def name(self, text):
        if text not in self.names:
            self.names[text] = len(self.strings)
            self.strings += text.encode("utf-8") + b"\0"
        return self.names[text]

    def file(self, data):
        at = len(self.files)
        self.files += data
        return at, len(data)

    def file_attr(self, name, data):
        at, size = self.file(data)
        return (name, 6, at, size)

    def string_attr(self, name, value):
        """A string attribute: UTF-8 at (offset, length) in the string table."""
        return (name, 3, self.name(value), len(value.encode("utf-8")))

    def element(self, name, *attrs, count=None):
        count = len(attrs) if count is None else count
        out = struct.pack(">II", self.name(name), count & 0xFFFFFFFF) + bytes(20)
        for attr_name, kind, a, b in attrs:
            out += struct.pack(">IIII", self.name(attr_name), kind, a & 0xFFFFFFFF, b & 0xFFFFFFFF)
        self.elements += out

    def bytes(self, string_offset_override=None):
        return p3t(self.elements, self.strings, self.files, string_offset_override)


def jpeg(width, height):
    """Enough of a JPEG for the frame header to be found: SOI, SOF0 with the size, EOI (17 bytes)."""
    return (b"\xFF\xD8" + b"\xFF\xC0" + struct.pack(">HB", 11, 8) + struct.pack(">HH", height, width)
            + bytes([1, 1, 0x11, 0]) + b"\xFF\xD9")


def gim_rgba(width, height, pixels, pitch_align=16):
    """A big-endian RGBA8888 GIM whose rows are padded to 16 bytes, as P3tThemeTest.packedPreview lays it out.
    `pitch_align` is the value written into the pitch-align field; the rows are padded to 16 whatever it says."""
    row = width * 4
    pitch = (row + 15) // 16 * 16
    image = 0x10 + 0x40 + pitch * height
    gim = b".GIM1.00" + b"\0" + b"PSP" + bytes(4)
    gim += struct.pack(">HHIII", 2, 0, 0x20 + image, 0x10, 0x10)      # root
    gim += struct.pack(">HHIII", 3, 0, 0x10 + image, 0x10, 0x10)      # picture
    gim += struct.pack(">HHIII", 4, 0, image, image, 0x10)            # image
    head = struct.pack(">HHHH", 0x30, 0, 3, 0)                         # size, ref, RGBA8888, linear
    head += struct.pack(">HHHH", width, height, 32, pitch_align)
    head += struct.pack(">HHHH", 1, 2, 0, 0)
    head += struct.pack(">III", 0x30, 0x40, 0x40 + pitch * height)
    gim += head + bytes(0x40 - len(head))
    for y in range(height):
        gim += bytes(pixels[y * row:(y + 1) * row]) + bytes(pitch - row)
    return gim


def packed_preview(width, height, pixels, pitch_align=16):
    """The GIM, zlib-packed as a theme's `info preview` is (Java's Deflater default, header 78 9c)."""
    return zlib.compress(gim_rgba(width, height, pixels, pitch_align))


def edge_block(reused, bias, flag_bytes, width, *streams):
    """RafSceneTest.kt's `block`: the 8-byte Edge index header (reused count, bias, flag-stream bytes, value width,
    a zero byte), then the stream bytes as given."""
    head = struct.pack(">HHHBB", reused & 0xFFFF, bias & 0xFFFF, flag_bytes & 0xFFFF, width & 0xFF, 0)
    return head + bytes(s & 0xFF for s in streams)


KOTLIN_DECL = r"\b(?:fun|val)\s+{0}\b"
KOTLIN_STRING = re.compile(r'"([0-9a-fA-F]*)"')


def kotlin_hex(path, name):
    """The bytes of the first `hex(...)` call after `fun <name>` or `val <name>` in the Kotlin source at `path`: every
    string literal inside that call, joined, as hex. Raises when the declaration, the call or any hex is missing,
    so a moved Android test fails the guard rather than feeding it nothing."""
    text = path.read_text(encoding="utf-8")
    decl = re.search(KOTLIN_DECL.format(re.escape(name)), text)
    if decl is None:
        raise AssertionError("{0} declares no `{1}`".format(path, name))
    call = text.find("hex(", decl.end())
    if call < 0:
        raise AssertionError("{0}: no hex( call after `{1}`".format(path, name))
    depth, end = 0, None
    in_string = False
    for at in range(call + 3, len(text)):
        ch = text[at]
        if ch == '"':
            in_string = not in_string
        elif not in_string and ch == "(":
            depth += 1
        elif not in_string and ch == ")":
            depth -= 1
            if depth == 0:
                end = at
                break
    if end is None:
        raise AssertionError("{0}: the hex( call after `{1}` is not closed".format(path, name))
    digits = "".join(KOTLIN_STRING.findall(text[call:end]))
    if not digits or len(digits) % 2:
        raise AssertionError("{0}: `{1}` holds no whole hex string".format(path, name))
    return bytes.fromhex(digits)


def gtf(format, width, height, block, pitch=None):
    """P3tAnimationTest.kt's `gtf`: a GTF file of one `width` x `height` texture in `format`, every block `block`.
    `pitch`, when given, is written in place of the true row pitch (the data is laid out at the true pitch)."""
    wide = (width + 3) // 4
    high = (height + 3) // 4
    data = bytes(block) * (wide * high)
    out = struct.pack(">III", 0x020101FF, len(data), 1)               # version, size, texture count
    out += struct.pack(">III", 0, 0x80, len(data))                    # id, offset, size
    out += struct.pack(">BBBBI", format & 0xFF, 1, 2, 0, 0xAAE4)      # format, mips, 2D, not a cube, remap
    out += struct.pack(">HHHBB", width & 0xFFFF, height & 0xFFFF, 1, 0, 0)
    out += struct.pack(">II", (wide * len(block) if pitch is None else pitch) & 0xFFFFFFFF, 0)  # pitch, offset
    return out + bytes(0x80 - len(out)) + data


def dxt1(c0, c1, indices):
    """One DXT1 colour block: two little-endian RGB565 colours and 32 bits of 2-bit indexes."""
    return struct.pack("<HHI", c0 & 0xFFFF, c1 & 0xFFFF, indices & 0xFFFFFFFF)


def dxt5(a0, a1, alpha_bits, color):
    """One DXT5 block: two alpha end points, 48 bits of 3-bit alpha indexes (little-endian), then `color`."""
    return bytes([a0 & 0xFF, a1 & 0xFF]) + (alpha_bits & 0xFFFFFFFFFFFF).to_bytes(6, "little") + bytes(color)


def raf_entry(scene):
    """P3tAnimationTest.kt's `raf`: the `_RAF` word, the scene's size as a big-endian u32, then the scene zlib-packed
    as Java's Deflater packs it by default (level 6, header 78 9c)."""
    return b"_RAF" + struct.pack(">I", len(scene)) + zlib.compress(bytes(scene), 6)


class RafBuilder:
    """P3tAnimationTest.kt's `SceneBuilder`: a RAF scene in the P3T table layout, with parent links, an id table
    (a u32, then the name and a NUL, per entry), floats and files. An attribute is a (name, type, a, b) tuple."""

    def __init__(self):
        self.names = bytearray()
        self.name_at = {}
        self.ids = bytearray()
        self.id_at = {}
        self.floats = bytearray()
        self.files = bytearray()
        self.elements = []

    def name(self, text):
        if text not in self.name_at:
            self.name_at[text] = len(self.names)
            self.names += text.encode("utf-8") + b"\0"
        return self.name_at[text]

    def id(self, text):
        if text not in self.id_at:
            self.id_at[text] = len(self.ids)
            self.ids += bytes(4) + text.encode("utf-8") + b"\0"
        return self.id_at[text]

    def own(self, text):
        return (self.name("id"), 7, self.id(text), 0)

    def ref(self, key, text):
        return (self.name(key), 8, self.id(text), 0)

    def int(self, key, value):
        return (self.name(key), 1, value, 0)

    def vector(self, key, *values):
        at = len(self.floats) // 4
        for v in values:
            self.floats += struct.pack(">f", v)
        return (self.name(key), 5, at, len(values))

    def file(self, key, data):
        at = len(self.files)
        self.files += data
        return (self.name(key), 6, at, len(data))

    def element(self, tag, parent, *attrs):
        self.elements.append((self.name(tag), parent, list(attrs)))
        return len(self.elements) - 1

    def layer(self, scene, root, name, gtf_bytes, z=-8.0, mesh=None):
        attrs = [self.own(name), self.ref("material", "mtrl_" + name), self.vector("position", 15.0, 9.0, z)]
        if mesh is not None:
            attrs.append(self.ref("model", "mdl_" + name))
            model = self.element("model", root, self.own("mdl_" + name))
            self.element("geometry", model, self.ref("fileref", name + ".edge"))
            self.element("file", root, self.own(name + ".edge"), self.file("src", mesh), self.int("type", 3))
        self.element("actor", scene, *attrs)
        material = self.element("material", root, self.own("mtrl_" + name))
        self.element("_texture", material, self.ref("texref", "_" + name + ".gtf"))
        self.element("texture", root, self.own("_" + name + ".gtf"), self.ref("fileref", name + ".gtf"))
        self.element("file", root, self.own(name + ".gtf"), self.file("src", gtf_bytes), self.int("type", 4))

    def string(self, key, value):
        """A type-3 string attribute: an offset into the names table."""
        return (self.name(key), 3, self.name(value), 0)

    def scalar(self, key, value):
        """A type-2 float attribute: the float's bits in the attribute itself."""
        return (self.name(key), 2, struct.unpack(">I", struct.pack(">f", value))[0], 0)

    def _file(self, root, name, data, kind):
        self.element("file", root, self.own(name), self.file("src", data), self.int("type", kind))

    def material(self, root, name, effect, texture=None):
        """A material with its effect, and, given GTF bytes, a `_texture` child naming a texture and its file."""
        material = self.element("material", root, self.own(name), self.string("effect", effect))
        if texture is not None:
            self.element("_texture", material, self.ref("texref", "tex_" + name))
            self.element("texture", root, self.own("tex_" + name), self.ref("fileref", name + ".gtf"))
            self._file(root, name + ".gtf", texture, 4)
        return material

    def model(self, root, name, geometry=None, skeleton=None, inverse_bind=None, animations=()):
        """A model with `geometry`, `skeleton` and `inv-bind` children for the bytes given, then one `animation`
        child per (animation id, bytes) in `animations`, in slot order."""
        model = self.element("model", root, self.own(name))
        for tag, data, ext in (("geometry", geometry, ".edge"), ("skeleton", skeleton, ".skel"),
                               ("inv-bind", inverse_bind, ".invbind")):
            if data is not None:
                self.element(tag, model, self.ref("fileref", name + ext))
                self._file(root, name + ext, data, 3)
        for anim_id, data in animations:
            self.element("animation", model, self.own(anim_id), self.ref("fileref", anim_id + ".anim"))
            self._file(root, anim_id + ".anim", data, 3)
        return model

    def actor(self, scene, name, *attrs, material=None, model=None):
        refs = [self.own(name)]
        if material is not None:
            refs.append(self.ref("material", material))
        if model is not None:
            refs.append(self.ref("model", model))
        return self.element("actor", scene, *(refs + list(attrs)))

    def light(self, scene, name, *attrs):
        return self.element("light", scene, self.own(name), *attrs)

    def camera(self, scene, *attrs):
        return self.element("camera", scene, *attrs)

    def script(self, root, code):
        self.element("script", root, self.ref("fileref", "show.jsx"))
        self.element("file", root, self.own("show.jsx"), self.file("src", code), self.int("type", 5))

    def bytes(self, element_count_override=None, files_offset_override=None):
        offsets, size = [], 0
        for _, _, attrs in self.elements:
            offsets.append(size)
            size += 28 + 16 * len(attrs)
        el = bytearray()
        for i, (name, parent, attrs) in enumerate(self.elements):
            count = element_count_override if (i == 0 and element_count_override is not None) else len(attrs)
            el += struct.pack(">II", name, count & 0xFFFFFFFF)
            el += struct.pack(">i", -1 if parent < 0 else offsets[parent]) + struct.pack(">iiii", -1, -1, -1, -1)
            for a in attrs:
                el += struct.pack(">IIII", *(v & 0xFFFFFFFF for v in a))
        tables = [bytes(el), bytes(self.ids), bytes(self.names), b"", bytes(self.floats), bytes(self.files)]
        out = b"RAFO" + struct.pack(">I", 0x110)
        at = 56
        for i, table in enumerate(tables):
            offset = files_offset_override if (i == 5 and files_offset_override is not None) else at
            out += struct.pack(">II", offset & 0xFFFFFFFF, len(table))
            at += len(table)
        return out + b"".join(tables)


class VsmxAsm:
    """VsmxAsm.kt: writes VSMX scripts, instructions with the name, property and text tables built as used. `names`
    pre-seeds the name table in the order given, so a test can place an entry (an empty one, say) at an index."""

    def __init__(self, names=None):
        self.names = list(names or [])
        self.props = []
        self.text = []
        self.code = []

    @property
    def here(self):
        return len(self.code)

    def op(self, op, arg=0):
        self.code.append([op, arg])
        return len(self.code) - 1

    def patch(self, at, target):
        self.code[at][1] = target

    @staticmethod
    def _index(table, text):
        if text in table:
            return table.index(text)
        table.append(text)
        return len(table) - 1

    def global_(self, name):
        return self.op(0x2E, self._index(self.names, name))

    def local(self, slot):
        return self.op(0x2D, slot)

    def int_(self, value):
        return self.op(0x26, value)

    def float_(self, value):
        return self.op(0x27, struct.unpack("<i", struct.pack("<f", value))[0])

    def float_bits(self, bits):
        """An OP_FLOAT whose operand is the raw 32 bits given: NaN and infinity, which `float_` cannot spell."""
        return self.op(0x27, struct.unpack("<i", struct.pack("<I", bits & 0xFFFFFFFF))[0])

    def string(self, text):
        return self.op(0x28, self._index(self.text, text))

    def get(self, prop):
        return self.op(0x2F, self._index(self.props, prop))

    def get_keep(self, prop):
        return self.op(0x30, self._index(self.props, prop))

    def set_(self, prop):
        return self.op(0x31, self._index(self.props, prop))

    def literal_set(self, prop):
        return self.op(0x33, self._index(self.props, prop))

    def element_get(self, i):
        return self.op(0x4A | (i << 8))

    def element_set(self, prop, i):
        return self.op(0x4D | (i << 8), self._index(self.props, prop))

    def call(self, argc):
        return self.op(0x3C, argc)

    def call_method(self, argc):
        return self.op(0x3D, argc)

    def new(self, argc):
        return self.op(0x3E, argc)

    def array(self, n):
        return self.op(0x49, n)

    def pop(self):
        return self.op(0x22)

    def end(self):
        return self.op(0x45)

    def assign(self, name, value):
        """`name = <value>;` with `value()` pushing the value."""
        self.global_(name)
        value()
        self.op(0x01)
        self.pop()

    def call_global(self, name, *args):
        self.global_(name)
        for arg in args:
            arg()
        self.call(len(args))
        self.pop()

    def function(self, name, params, locals_, body):
        """`name = function (params) { body }`, skipped over where it is defined, as the compiler lays it out."""
        self.global_(name)
        fn = self.op(0x2A | (params << 8) | (locals_ << 24))
        self.op(0x01)
        self.pop()
        skip = self.op(0x39)
        self.patch(fn, self.here)
        body()
        self.op(0x23)
        self.op(0x3F)
        self.patch(skip, self.here)

    def method_call(self, receiver, method, first, time, interpolation=None):
        """`receiver.method(first, time[, interpolation]);`, as P3tAnimationTest.kt's `Ops.call` lays it out."""
        self.global_(receiver)
        self.get_keep(method)
        first()
        time()
        if interpolation is not None:
            self.global_(interpolation)
        self.call_method(2 if interpolation is None else 3)
        self.pop()

    def timer(self, every, callback):
        """`new IntervalTimer(every, callback);`, as `Ops.timer` lays it out."""
        self.global_("IntervalTimer")
        every()
        self.global_(callback)
        self.new(2)
        self.pop()

    def assemble(self):
        text = "".join(s + "\0" for s in self.text).encode("utf-16-le")
        props = "".join(s + "\0" for s in self.props).encode("utf-16-le")
        names = "".join(s + "\0" for s in self.names).encode("latin-1")
        code_at = 52
        text_at = code_at + len(self.code) * 8
        prop_at = text_at + len(text)
        name_at = prop_at + len(props)
        out = b"VSMX" + struct.pack("<III", 0x20000, code_at, len(self.code) * 8)
        out += struct.pack("<III", text_at, len(text), len(self.text))
        out += struct.pack("<III", prop_at, len(props), len(self.props))
        out += struct.pack("<III", name_at, len(names), len(self.names))
        for op, arg in self.code:
            out += struct.pack("<ii", struct.unpack("<i", struct.pack("<I", op & 0xFFFFFFFF))[0],
                               struct.unpack("<i", struct.pack("<I", arg & 0xFFFFFFFF))[0])
        return out + text + props + names


def slideshow_script(duration=10.0, fade=1.5, names=None):
    """The PS4 On PS3 template: `duration = 10; fade = 1.5;` and a timer whose callback fades the slides."""
    a = VsmxAsm(names)
    a.assign("duration", lambda: a.float_(duration) if isinstance(duration, float) else a.float_bits(duration))
    a.assign("fade", lambda: a.float_(fade) if isinstance(fade, float) else a.float_bits(fade))
    a.function("change_bg", 0, 0, lambda: a.method_call(
        "bg", "setColor", lambda: a.global_("color"), lambda: a.global_("fade"), "INTERPOLATION_LINEAR"))
    a.timer(lambda: a.global_("duration"), "change_bg")
    return a.assemble()


def zoom_script(end_z_bits=None, move_bits=None):
    """The JUJU7U template: integer timings, plus a camera moving between z 45 and 48. `end_z_bits` and
    `move_bits`, when given, replace the far camera z and the move time with those raw float bits."""
    a = VsmxAsm()

    def camera():
        a.global_("Camera")
        a.string("camera")
        a.new(1)
    a.assign("camera", camera)

    def position(z_value, z_bits=None):
        def push():
            a.float_(15.5)
            a.float_(9.5)
            if z_bits is None:
                a.int_(z_value)
            else:
                a.float_bits(z_bits)
            a.array(3)
        return push
    a.assign("camera_start", position(45))
    a.assign("camera_end", position(48, end_z_bits))
    a.assign("duration4", lambda: a.int_(7))
    a.assign("fade4", lambda: a.int_(1))
    a.function("changeAnim4", 0, 0, lambda: a.method_call(
        "slide", "setColor", lambda: a.global_("color"), lambda: a.global_("fade4"), "INTERPOLATION_LINEAR"))

    def move():
        return a.float_(4.0) if move_bits is None else a.float_bits(move_bits)

    def zoom():
        a.method_call("camera", "setPosition", lambda: a.global_("camera_end"), move, "INTERPOLATION_BEZIER")
        a.method_call("camera", "setPosition", lambda: a.global_("camera_start"), move, "INTERPOLATION_BEZIER")
    a.function("camera_zoom", 0, 0, zoom)
    a.timer(lambda: a.global_("duration4"), "changeAnim4")
    a.timer(lambda: a.float_(5.0), "camera_zoom")
    return a.assemble()


def es01(parents, rest):
    """An Edge ES01 skeleton: the tag, the joint count at 0x0C, self-relative offsets to the rest and parent tables
    at 0x14 and 0x18, then 48 bytes of rest pose a joint (a quaternion, a translation and a scale, 16 bytes each,
    each 3-float part padded with a zero float) and a 16-bit parent a joint. `rest` is 10 floats a joint."""
    n = len(parents)
    rest_at = 0x30
    parents_at = rest_at + 48 * n
    head = bytearray(0x30)
    head[0:4] = b"ES01"
    struct.pack_into(">H", head, 0x0C, n & 0xFFFF)
    struct.pack_into(">I", head, 0x14, rest_at - 0x14)
    struct.pack_into(">I", head, 0x18, parents_at - 0x18)
    body = bytearray()
    for j in range(n):
        r = rest[10 * j:10 * j + 10]
        body += struct.pack(">4f", *r[0:4]) + struct.pack(">4f", *(list(r[4:7]) + [0.0]))
        body += struct.pack(">4f", *(list(r[7:10]) + [0.0]))
    for p in parents:
        body += struct.pack(">h", p)
    return bytes(head) + bytes(body)


def ea03_empty(frame_rate):
    """EdgeAnimTest.kt's rest-pose clip: 0x90 bytes, `EA03`, the rate at 8, one joint, one frame, one frame set."""
    b = bytearray(0x90)
    b[0:4] = b"EA03"
    struct.pack_into(">f", b, 8, frame_rate)
    b[0x0F] = 1
    b[0x11] = 1
    b[0x13] = 1
    return bytes(b)


def edge_field(s, e, m):
    """EdgeAnimTest.kt's `field`: a 10-bit spec field of sign, exponent and mantissa bit counts."""
    return (s << 9) | (e << 5) | m


def _bits_to_bytes(bits):
    out = bytearray((len(bits) + 7) // 8)
    for i, c in enumerate(bits):
        if c == "1":
            out[i // 8] |= 0x80 >> (i % 8)
    return bytes(out)


def _signed10(value):
    """A signed fixed-point component of field(1, 0, 10): the sign bit, then 10 mantissa bits, over 2^10."""
    n = int(round(value * 1024)) & 0x7FF
    return format(n, "011b")


def ea03_bend():
    """An EA03 clip of two joints and 11 frames at 10 frames a second: a constant translation moves joint 1 to x = 1.0,
    and an animated rotation about z has keys at frames 0, 5 and 9 with stored z 0, 0.5 and 0.25."""
    fixed = edge_field(1, 0, 10)
    rot_spec = (fixed << 22) | (fixed << 12) | (fixed << 2) | 3
    trans_spec = edge_field(0, 0, 10) << 22
    b = bytearray(0x60)
    b[0:4] = b"EA03"
    struct.pack_into(">ff", b, 4, 1.0, 10.0)
    struct.pack_into(">HHH", b, 0x0E, 2, 11, 1)
    struct.pack_into(">8H", b, 0x16, 0, 1, 0, 0, 1, 0, 0, 0)
    # Joint tables at 0x60: const T [1] padded to 4, anim R [1] padded to 4.
    b += struct.pack(">4H", 1, 2, 2, 2) + struct.pack(">4H", 1, 2, 2, 2)
    spec_at = len(b)
    b += struct.pack(">IIII", 0, rot_spec, trans_spec, 0)  # const R, anim R, const T, const S
    const_at = len(b)
    b += _bits_to_bytes("1111111111")  # x = 1023 / 1023
    b += bytes((4 - len(b) % 4) % 4)
    key = _bits_to_bytes(_signed10(0) * 3)
    masks = _bits_to_bytes("000010001")
    data = _bits_to_bytes(_signed10(0) + _signed10(0) + _signed10(0.5) + _signed10(0) + _signed10(0) + _signed10(0.25))
    frame_set_at = len(b)
    b += struct.pack(">4H4H", len(key), 0, 0, 0, len(data), 0, 0, 0) + key + masks + data
    dma_at = len(b)
    b += struct.pack(">II", len(b) - frame_set_at, frame_set_at)
    info_at = len(b)
    b += struct.pack(">HH", 0, 9)
    for at, target in ((0x34, dma_at), (0x38, info_at), (0x3C, const_at), (0x40, const_at), (0x44, const_at),
                       (0x4C, spec_at)):
        struct.pack_into(">I", b, at, target - at)
    return bytes(b)


def vsmx_sample():
    """A valid VSMX script for the VM's fuzz that uses every kind of instruction the VM knows, so a flipped byte lands in each."""
    a = VsmxAsm()

    def add_body():
        a.local(1)
        a.local(2)
        a.op(0x02)
        a.op(0x3F)
    a.function("add", 2, 3, add_body)

    def foo_body():
        a.op(0x2C)
        a.local(1)
        a.set_("x")
        a.pop()
    a.function("Foo", 1, 2, foo_body)
    a.global_("Foo")
    a.get("prototype")
    fn = a.op(0x2A | (1 << 24))
    skip = a.op(0x39)
    a.patch(fn, a.here)
    a.op(0x2C)
    a.get("x")
    a.op(0x3F)
    a.patch(skip, a.here)
    a.set_("get")
    a.pop()

    def new_foo():
        a.global_("Foo")
        a.int_(5)
        a.new(1)
    a.assign("o", new_foo)

    def o_get():
        a.global_("o")
        a.get_keep("get")
        a.call_method(0)
    a.call_global("report", o_get)

    def literal():
        a.op(0x29)
        a.int_(3)
        a.literal_set("n")
        a.string("t")
        a.literal_set("s")
    a.assign("lit", literal)

    def vectors():
        a.int_(1)
        a.int_(2)
        a.int_(3)
        a.array(3)
        a.float_(0.5)
        a.op(0x04)
    a.assign("v", vectors)
    a.global_("o")
    a.global_("v")
    a.set_("p")
    a.pop()
    a.global_("o")
    a.get_keep("p")
    a.element_get(0)
    a.int_(5)
    a.op(0x02)
    a.element_set("p", 0)
    a.pop()

    a.assign("s", lambda: a.string(""))
    a.assign("i", lambda: a.int_(0))
    top = a.here
    a.global_("i")
    a.int_(10)
    a.op(0x12)
    done = a.op(0x3B)
    a.global_("i")
    a.op(0x0C)
    a.pop()

    def grow():
        a.global_("s")
        a.string("ab")
        a.op(0x02)
    a.assign("s", grow)
    a.op(0x39, top)
    a.patch(done, a.here)

    try_at = a.op(0x41)
    a.global_("nothing")
    a.call(0)
    a.pop()
    a.op(0x42)
    skip = a.op(0x43)
    a.patch(try_at, skip)
    a.global_("e")
    a.op(0x21)
    a.op(0x01)
    a.pop()
    a.call_global("report", lambda: a.global_("e"))
    a.patch(skip, a.op(0x44))

    def made():
        a.global_("Array")
        a.int_(4)
        a.new(1)
    a.assign("arr", made)
    a.global_("arr")
    a.get_keep("push")
    a.int_(7)
    a.call_method(1)
    a.pop()

    def length():
        a.global_("arr")
        a.get("length")
    a.call_global("report", length)

    def twice():
        a.global_("obj")
        a.get_keep("twice")
        a.call_method(0)
    a.call_global("report", twice)
    a.call_global("keep", lambda: a.global_("add"), lambda: a.global_("Foo"))

    def sum_():
        a.global_("add")
        a.int_(2)
        a.global_("s")
        a.call(2)
    a.call_global("report", sum_)
    a.end()
    return a.assemble()
