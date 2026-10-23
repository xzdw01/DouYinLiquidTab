"""Minimal DEX parser: class / field / method tables, fast enough for the 359MB APK.

Usage:
  python dexlib.py <dex> classes <pattern>      list matching class descriptors
  python dexlib.py <dex> fields <class>         fields of a class (name : type)
  python dexlib.py <dex> methods <class>        methods of a class (name sig)
  python dexlib.py <dex> bytype <type>          classes owning a field of that type
  python dexlib.py <dex> sig <substr>           methods whose signature contains substr
"""
import struct
import sys


class Dex:
    def __init__(self, path):
        with open(path, "rb") as fh:
            self.d = fh.read()
        self._parse_header()

    # ---------- primitives ----------
    def u32(self, off):
        return struct.unpack_from("<I", self.d, off)[0]

    def u16(self, off):
        return struct.unpack_from("<H", self.d, off)[0]

    def uleb(self, off):
        result = 0
        shift = 0
        while True:
            b = self.d[off]
            off += 1
            result |= (b & 0x7F) << shift
            if not (b & 0x80):
                return result, off
            shift += 7

    def _parse_header(self):
        if self.d[:4] not in (b"dex\n", b"dex\n"):
            raise ValueError("not a dex")
        self.string_ids_size = self.u32(0x38)
        self.string_ids_off = self.u32(0x3C)
        self.type_ids_size = self.u32(0x40)
        self.type_ids_off = self.u32(0x44)
        self.proto_ids_size = self.u32(0x48)
        self.proto_ids_off = self.u32(0x4C)
        self.field_ids_size = self.u32(0x50)
        self.field_ids_off = self.u32(0x54)
        self.method_ids_size = self.u32(0x58)
        self.method_ids_off = self.u32(0x5C)
        self.class_defs_size = self.u32(0x60)
        self.class_defs_off = self.u32(0x64)
        self._str_cache = {}

    # ---------- tables ----------
    def string(self, idx):
        if idx in self._str_cache:
            return self._str_cache[idx]
        off = self.u32(self.string_ids_off + idx * 4)
        n, off = self.uleb(off)
        end = self.d.index(b"\x00", off)
        s = self.d[off:end].decode("utf-8", errors="replace")
        self._str_cache[idx] = s
        return s

    def type_desc(self, idx):
        return self.string(self.u32(self.type_ids_off + idx * 4))

    def proto(self, idx):
        base = self.proto_ids_off + idx * 12
        shorty = self.string(self.u32(base))
        ret = self.type_desc(self.u32(base + 4))
        params_off = self.u32(base + 8)
        params = []
        if params_off:
            size = self.u32(params_off)
            for i in range(size):
                params.append(self.type_desc(self.u16(params_off + 4 + i * 2)))
        return ret, params

    def field(self, idx):
        base = self.field_ids_off + idx * 8
        cls = self.u16(base)
        typ = self.u16(base + 2)
        name = self.u32(base + 4)
        return self.type_desc(cls), self.type_desc(typ), self.string(name)

    def method(self, idx):
        base = self.method_ids_off + idx * 8
        cls = self.u16(base)
        proto = self.u16(base + 2)
        name = self.u32(base + 4)
        ret, params = self.proto(proto)
        return self.type_desc(cls), self.string(name), ret, params

    def class_def(self, i):
        base = self.class_defs_off + i * 32
        return {
            "class_idx": self.u32(base),
            "access": self.u32(base + 4),
            "superclass_idx": self.u32(base + 8),
            "class_data_off": self.u32(base + 24),
        }

    def class_name(self, i):
        return self.type_desc(self.class_def(i)["class_idx"])

    def class_members(self, i):
        """(fields, methods) declared by class index i, as dicts.

        A malformed or unusual entry is skipped rather than aborting the whole scan.
        """
        try:
            return self._class_members(i)
        except Exception:
            return [], []

    def _class_members(self, i):
        cd = self.class_def(i)
        off = cd["class_data_off"]
        if off == 0:
            return [], []
        sf, off = self.uleb(off)
        inf, off = self.uleb(off)
        dm, off = self.uleb(off)
        vm, off = self.uleb(off)
        fields, methods = [], []
        fidx = 0
        for _ in range(sf + inf):
            diff, off = self.uleb(off)
            acc, off = self.uleb(off)
            fidx += diff
            c, t, n = self.field(fidx)
            fields.append((n, t, acc, c))
        midx = 0
        for _ in range(dm + vm):
            diff, off = self.uleb(off)
            acc, off = self.uleb(off)
            code, off = self.uleb(off)
            midx += diff
            c, n, r, p = self.method(midx)
            methods.append((n, r, p, acc, code, c))
        return fields, methods


def main():
    dex_path = sys.argv[1]
    cmd = sys.argv[2]
    arg = sys.argv[3] if len(sys.argv) > 3 else ""
    dx = Dex(dex_path)
    n = dx.class_defs_size

    if cmd == "classes":
        for i in range(n):
            nm = dx.class_name(i)
            if arg in nm:
                print(nm)
    elif cmd in ("fields", "methods"):
        for i in range(n):
            nm = dx.class_name(i)
            if nm != arg:
                continue
            f, m = dx.class_members(i)
            for name, typ, acc, _c in f:
                print(f"field  {name} : {typ}")
            for name, ret, params, acc, code, _c in m:
                ps = "".join(params)
                print(f"method {name}({ps}){ret}")
            break
    elif cmd == "bytype":
        for i in range(n):
            f, _m = dx.class_members(i)
            for name, typ, acc, _c in f:
                if typ == arg:
                    print(f"{dx.class_name(i)}  field {name}")
    elif cmd == "sig":
        for i in range(n):
            f, m = dx.class_members(i)
            for name, ret, params, acc, code, _c in m:
                ps = "".join(params)
                if arg in ps or arg in ret:
                    print(f"{dx.class_name(i)}  {name}({ps}){ret}")


if __name__ == "__main__":
    main()
