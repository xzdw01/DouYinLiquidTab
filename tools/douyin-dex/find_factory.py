"""Re-locate Douyin's feed output factory after a Douyin update.

`DouyinPlayerTextureHooks` forces the feed's video output to a TextureView so the glass
can sample it on the GPU instead of paying for a PixelCopy readback. It does that by
hooking a factory method on an *obfuscated* class, and Douyin re-maps obfuscated names
between builds — sometimes without even changing versionName. When that happens the hook
logs `feed output factory not found` and silently falls back to the laggy capture path.

This script finds the new name.

Why the anchors work: obfuscation renames classes, methods and most fields, but it does
not rename the *strings the code itself uses* — `sIsEnableSurfaceviewForGeneralDevice`
and friends are declared by Douyin's own source, so they survive every build. They live on
the same class as the factory method, which makes them a stable landmark.

Usage:
  python find_factory.py <dex-file-or-directory> [...]

Then add the printed class name to the candidate list at the top of
DouyinPlayerTextureHooks.install(), newest first:

  val factory = listOf("X.0GO1", "X.1KBI", "X.0wMt").firstNotNullOfOrNull { ... }

The lookup there is by *capability* (the class must really declare the method), so a stale
name that has been recycled onto an unrelated class is skipped automatically.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexlib import Dex  # noqa: E402

# Declared by Douyin's own source, therefore not obfuscated. Any one of these identifies
# the factory class; the first is the one observed in every build so far.
ANCHOR_FIELDS = (
    "sIsEnableSurfaceviewForGeneralDevice",
    "sIsEnableSurfaceviewForGeneralDevicesFetched",
    "sIsSurfaceViewEnabledWhenInOppoBlackList",
    "sIsSurfaceViewEnabledWhenInOppoBlackListFetched",
)

# The factory's signature fingerprint: (ViewGroup, boolean) returning its own type.
FACTORY_METHOD = "LJIILIIL"
FACTORY_PARAMS = ("Landroid/view/ViewGroup;", "Z")

# Methods the same class exposes to decide SurfaceView vs TextureView.
SWITCH_METHODS = ("LJIJJ", "LJJI", "LJJIIJZLJL")

FACTORY_FIELD = "wrapper"


def dex_files(paths):
    for p in paths:
        if os.path.isdir(p):
            for name in sorted(os.listdir(p)):
                if name.endswith(".dex"):
                    yield os.path.join(p, name)
        else:
            yield p


def matches_factory(methods):
    """The factory method must exist, take (ViewGroup, boolean) and return the class.

    Methods come through as (name, return, params, access, code_off, class).
    """
    for name, ret, params, _acc, _code, _cls in methods:
        if name == FACTORY_METHOD and tuple(params) == FACTORY_PARAMS:
            return ret
    return None


def main():
    paths = sys.argv[1:]
    if not paths:
        print(__doc__)
        return 1

    hits = []
    for path in dex_files(paths):
        try:
            dx = Dex(path)
        except Exception as exc:
            print("skip %s: %s" % (path, exc))
            continue
        for i in range(dx.class_defs_size):
            fields, methods = dx.class_members(i)
            field_names = {f[0] for f in fields}
            anchors = [a for a in ANCHOR_FIELDS if a in field_names]
            if not anchors:
                continue
            hits.append((path, dx.class_name(i), anchors, fields, methods))

    if not hits:
        print("No class declares any anchor field.")
        print("The anchors themselves may have been removed in this build — see the")
        print("'when this stops working' section in tools/douyin-dex/README.md.")
        return 2

    for path, name, anchors, fields, methods in hits:
        # "Lcom/x/Y;" -> "com.x.Y" and "LX/0GO1;" -> "X.0GO1", the form findClass takes.
        java_name = name[1:-1].replace("/", ".")
        if "$" in java_name.rsplit(".", 1)[-1]:
            continue  # nested helpers inherit the static switches; not the factory itself
        print("=" * 70)
        print("class    : %s   (%s)" % (java_name, os.path.basename(path)))
        print("anchors  : %s" % ", ".join(anchors))

        ret = matches_factory(methods)
        if ret:
            print("factory  : %s(%s)%s  OK" % (FACTORY_METHOD, "".join(FACTORY_PARAMS), ret))
        else:
            print("factory  : !! %s(%s) NOT found on this class" %
                  (FACTORY_METHOD, "".join(FACTORY_PARAMS)))
            print("           the factory moved elsewhere; see README.md")

        wrappers = [f for f in fields if f[0] == FACTORY_FIELD]
        if wrappers:
            print("wrapper  : %s  (getView() is called on this)" % wrappers[0][1])

        present = sorted({m[0] for m in methods} & set(SWITCH_METHODS))
        print("switches : %s" % (", ".join(present) if present else "(none found)"))

        print()
        print("Add to the candidate list in DouyinPlayerTextureHooks.kt, newest first:")
        print('    listOf("%s", ...)' % java_name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
