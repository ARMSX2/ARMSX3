# SPDX-License-Identifier: GPL-2.0-only
"""Synthetic builders for the game-update guards; a helper, not a test. Every version list, hostile body, PARAM.SFO
and folder is built here from the standard library with synthetic title IDs, so no Sony answer reaches a test run."""

import hashlib
from pathlib import Path
from xml.sax.saxutils import escape, quoteattr

from test_param_sfo import INTEGER, STRING, build_sfo

OMIT = object()
PACKAGE_HOST = "b0.ww.np.dl.playstation.net"


def package(version, size, sha1, url=None, firmware=None):
    return {"version": version, "size": size, "sha1sum": sha1, "url": url, "ps3_system_ver": firmware}


def package_url(title_id, version):
    return "http://{0}/tppkg/np/{1}/{1}-A{2}-V0100.pkg".format(PACKAGE_HOST, title_id, version.replace(".", ""))


def ver_xml(title_id, packages, title=None, self_closing=False, root="titlepatch"):
    body = []
    for index, entry in enumerate(packages):
        attributes = []
        for key in ("version", "size", "sha1sum", "url", "ps3_system_ver"):
            value = entry.get(key)
            if key == "url" and value is None:
                value = package_url(title_id, str(entry["version"]))
            if value is None or value is OMIT:
                continue
            attributes.append("{0}={1}".format(key, quoteattr(str(value))))
        head = "<package " + " ".join(attributes)
        if title is not None and index == len(packages) - 1:
            body.append(head + "><paramsfo><TITLE>" + escape(title) + "</TITLE></paramsfo></package>")
        elif self_closing:
            body.append(head + "/>")
        else:
            body.append(head + "></package>")
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<{0} status="alive" titleid={1}><tag name={2} popup="true" signoff="true">\n'
            '{3}\n</tag></{0}>\n').format(root, quoteattr(title_id), quoteattr(title_id + "_T1"),
                                          "\n".join(body)).encode("utf-8")


def sfo(entries):
    """entries: [(key, fmt, value)], value bytes for a string and int for a u32, as build_sfo takes them."""
    return build_sfo(entries)


def title_sfo(title_id, category, app_ver=None, title="Synthetic Title"):
    entries = [("CATEGORY", STRING, category.encode() + b"\0"),
               ("TITLE", STRING, title.encode() + b"\0"),
               ("TITLE_ID", STRING, title_id.encode() + b"\0"),
               ("ATTRIBUTE", INTEGER, 0)]
    if app_ver is not None:
        entries.insert(0, ("APP_VER", STRING, app_ver.encode() + b"\0"))
    return sfo(entries)


def refused_variants(title_id):
    """{name: document}: one good package beside one that breaks exactly one rule, plus the whole-list cases."""
    sha = "ab" * 20
    good = package("01.01", 1000, sha)
    base = "/tppkg/np/{0}/".format(title_id)
    file_name = "{0}-A0102-V0100.pkg".format(title_id)

    def bad(**fields):
        entry = package("01.02", 2000, sha)
        entry.update(fields)
        return ver_xml(title_id, [good, entry])

    def url(rest, prefix="http://" + PACKAGE_HOST):
        return bad(url=prefix + rest)

    return {
        "version-short": bad(version="1.5"),
        "version-long": bad(version="123.00"),
        "version-letters": bad(version="a.bc"),
        "version-empty": bad(version=""),
        "size-zero": bad(size="0"),
        "size-header": bad(size="32"),
        "size-negative": bad(size="-1"),
        "size-float": bad(size="1e9"),
        "size-huge": bad(size=str((32 << 30) + 1)),
        "size-text": bad(size="big"),
        "nosha": bad(sha1sum=""),
        "sha-missing": bad(sha1sum=OMIT),
        "sha-39": bad(sha1sum="a" * 39),
        "sha-41": bad(sha1sum="a" * 41),
        "sha-nonhex": bad(sha1sum="g" * 40),
        "nourl": bad(url=OMIT),
        "url-list-host": url(base + file_name, "http://a0.ww.np.dl.playstation.net"),
        "url-host-suffix": url(base + file_name, "http://" + PACKAGE_HOST + ".evil.example"),
        "url-port": url(base + file_name, "http://" + PACKAGE_HOST + ":80"),
        "url-user": url(base + file_name, "http://u@" + PACKAGE_HOST),
        "url-password": url(base + file_name, "http://u:p@" + PACKAGE_HOST),
        "url-query": url(base + file_name + "?x=1"),
        "url-fragment": url(base + file_name + "#f"),
        "url-ftp": url(base + file_name, "ftp://" + PACKAGE_HOST),
        "url-other-title": url("/tppkg/np/WXYZ99999/WXYZ99999-A0102-V0100.pkg"),
        "url-dotdot": url(base + "../WXYZ99999/" + file_name),
        "url-encoded-dotdot": url(base + "%2e%2e/WXYZ99999/" + file_name),
        "url-dot": url(base + "./" + file_name),
        "url-not-pkg": url(base + file_name + ".txt"),
        "too-many": ver_xml(title_id, [package("01.{0:02d}".format(i), 1000, sha) for i in range(65)]),
        "root-patch": ver_xml(title_id, [good], root="patch"),
        "other-title": ver_xml("WXYZ99999", [package("01.01", 1000, sha)]),
    }


def billion_laughs():
    lines = ['<?xml version="1.0"?>', '<!DOCTYPE lolz [', '<!ENTITY lol "lol">']
    for level in range(1, 10):
        lines.append('<!ENTITY lol{0} "{1}">'.format(level, "&lol{0};".format(level - 1 if level > 1 else "") * 10))
    lines += [']>', '<titlepatch titleid="ABCD12345"><TITLE>&lol9;</TITLE></titlepatch>']
    return "\n".join(lines).encode("utf-8")


def oversized(body, size):
    """body padded with trailing spaces to exactly size bytes."""
    return body + b" " * (size - len(body))


def fuzz_list(title_id):
    """A valid list of about 3 KB: thirteen packages and a title."""
    versions = ["01.{0:02d}".format(i + 1) for i in range(13)]
    packages = [package(v, 1000000 + i, hashlib.sha1(v.encode()).hexdigest(), firmware="04.6000")
                for i, v in enumerate(versions)]
    return ver_xml(title_id, packages, title="Synthetic Title")


def game_folder(root, serial, sfo_bytes):
    """root/game/<serial>/, holding sfo_bytes as PARAM.SFO unless it is None."""
    folder = Path(root) / "game" / serial
    folder.mkdir(parents=True)
    if sfo_bytes is not None:
        (folder / "PARAM.SFO").write_bytes(sfo_bytes)
    return folder


def disc_sfo(root, app_ver, title_id="ABCD12345"):
    """root/disc/PS3_GAME/PARAM.SFO for a disc game at app_ver. Returns its path."""
    path = Path(root) / "disc" / "PS3_GAME" / "PARAM.SFO"
    path.parent.mkdir(parents=True)
    path.write_bytes(title_sfo(title_id, "DG", app_ver))
    return path


def exdata(root, names):
    """root/exdata/ holding an empty file per licence name."""
    folder = Path(root) / "exdata"
    folder.mkdir(parents=True, exist_ok=True)
    for name in names:
        (folder / name).write_bytes(b"\0" * 16)
    return folder
