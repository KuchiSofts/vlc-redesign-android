#!/usr/bin/env python3
"""Fails when a translation's placeholders differ from the English string's.

A translation without the English %s loses what it shows (a code, a name, a number: "Enter this code: %s" read
as "Code"), and one that gives %d to a text argument throws IllegalFormatConversionException where it is shown
(the Hindi duplicate-items plural crashed adding items to a playlist). Compares every strings.xml of values-*
against its module's values/strings.xml: the same positions and kinds (a plural's "one", "two" and "zero" may
leave the number out). Run before a release build:

    python3 buildsystem/redesign/check_string_formats.py      # exit 1 on any difference
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'application')
SPEC = re.compile(r'%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([sdfxXc])')


def specs(text):
    """the (position, kind) of each placeholder; %s takes any argument, so it is one kind with the others"""
    found = set()
    for n, m in enumerate(SPEC.finditer(text or ''), 1):
        found.add((m.group(1) or str(n), m.group(2)))
    return found


def strings(path):
    table = {}
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        print('%s: %s' % (path, e))
        return None
    for e in root:
        if e.tag == 'string' and e.get('formatted') != 'false':
            table[e.get('name')] = ''.join(e.itertext())
        elif e.tag == 'plurals':
            for item in e:
                table['%s#%s' % (e.get('name'), item.get('quantity'))] = ''.join(item.itertext())
    return table


def allowed(base, spec):
    """a translated placeholder takes an argument the English has, of a kind that takes it (%s takes any;
    %d or %f given to an argument the English shows with %s would throw)"""
    pos, kind = spec
    kinds = {k for p, k in base if p == pos}
    return bool(kinds) and (kind == 's' or kind in kinds)


def compatible(base, translated):
    """every placeholder allowed, and every argument of the English shown"""
    return all(allowed(base, s) for s in translated) and {p for p, _ in base} <= {p for p, _ in translated}


def main():
    bad = 0
    for base_path in sorted(glob.glob(os.path.join(ROOT, '**', 'res', 'values', 'strings.xml'), recursive=True)):
        base = strings(base_path)
        if base is None:
            bad += 1
            continue
        res = os.path.dirname(os.path.dirname(base_path))
        for path in sorted(glob.glob(os.path.join(res, 'values-*', 'strings.xml'))):
            table = strings(path)
            if table is None:
                bad += 1
                continue
            for key, text in table.items():
                name, _, quantity = key.partition('#')
                if quantity:
                    # the code gives every quantity the arguments of "other" (the count): "one" may
                    # show it (Ukrainian "one" is also 21, 31...) or leave it out
                    ref = base.get(name + '#other')
                    if ref is None:
                        continue
                    want, got = specs(ref), specs(text)
                    ok = compatible(want, got) if quantity == 'other' else all(allowed(want, s) for s in got)
                    if ok:
                        continue
                    bad += 1
                    print('%s: %s: English %s, translated %s' % (os.path.relpath(path, ROOT), key,
                                                                sorted(want), sorted(got)))
                    continue
                else:
                    ref = base.get(key)
                    if ref is None:
                        continue
                    want, got = specs(ref), specs(text)
                    # an English string with no placeholder is not formatted ("100%" as is)
                    if not want:
                        continue
                if not compatible(want, got):
                    bad += 1
                    print('%s: %s: English %s, translated %s' % (os.path.relpath(path, ROOT), key,
                                                                sorted(want), sorted(got)))
    if bad:
        print('%d translated string(s) whose placeholders differ from the English' % bad)
        return 1
    print('check_string_formats: every translation keeps the English placeholders')
    return 0


if __name__ == '__main__':
    sys.exit(main())
