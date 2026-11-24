#!/usr/bin/env python3
"""
Adds an English text for a Kotlin file and prints the accessor to use.

    python3 scripts/i18n/add_text.py app/src/main/java/ai/antigem/ui/notes/NotesScreen.kt "Delete {name}?"
      -> L.notes.screen.deleteName(name)        (en.json gets "notes.screen.deleteName": "Delete {name}?")
    python3 scripts/i18n/add_text.py <file.kt> --plural "{count} note" "{count} notes"
      -> L.notes.screen.countNote(count)
    --common          put it under common.* (short texts used all over the app)
    --key NAME        pick the last part of the key yourself
    --to FILE.json    write to this JSON object instead of en.json (merged later)

The same text in the same file reuses its key. {placeholders} become the accessor's parameters, in order of first use.
Then use the printed accessor in the code and add `import ai.antigem.i18n.L` if the file lacks it.
"""
import json
import os
import re
import sys
from collections import OrderedDict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extract_strings import EN_JSON, PH, KEYWORDS, namespace, slug  # noqa: E402

args = sys.argv[1:]
if len(args) < 2:
    print(__doc__)
    sys.exit(1)
common = '--common' in args
if common:
    args.remove('--common')
key_leaf = None
if '--key' in args:
    i = args.index('--key')
    key_leaf = args[i + 1]
    del args[i:i + 2]
target = EN_JSON
if '--to' in args:
    i = args.index('--to')
    target = args[i + 1]
    del args[i:i + 2]
plural = '--plural' in args
if plural:
    args.remove('--plural')
path = args[0]
value = OrderedDict([('one', args[1]), ('other', args[2])]) if plural else args[1]

en = json.load(open(EN_JSON, encoding='utf-8'), object_pairs_hook=OrderedDict)
extra = OrderedDict()
if target != EN_JSON and os.path.exists(target):
    extra = json.load(open(target, encoding='utf-8'), object_pairs_hook=OrderedDict)
everything = OrderedDict(en['strings'])
everything.update(extra)

ns = 'common' if common else namespace(os.path.abspath(path))
key = next((k for k, v in everything.items() if k.rsplit('.', 1)[0] == ns and v == value), None)
if key is None:
    text_for_slug = value if isinstance(value, str) else value['other']
    base = key_leaf or slug(text_for_slug)
    if not re.fullmatch(r'[a-z][A-Za-z0-9]*', base) or base in KEYWORDS:
        sys.exit('bad key name: %s' % base)
    leaves = {k.rsplit('.', 1)[1] for k in everything if k.rsplit('.', 1)[0] == ns}
    leaf, n = base, 2
    while leaf in leaves:
        leaf = '%s%d' % (base, n)
        n += 1
    key = '%s.%s' % (ns, leaf)
    if target == EN_JSON:
        en['strings'][key] = value
        with open(EN_JSON, 'w', encoding='utf-8') as f:
            json.dump(en, f, ensure_ascii=False, indent=2)
            f.write('\n')
    else:
        extra[key] = value
        with open(target, 'w', encoding='utf-8') as f:
            json.dump(extra, f, ensure_ascii=False, indent=2)
            f.write('\n')
texts = value.values() if isinstance(value, dict) else [value]
names = list(OrderedDict.fromkeys(n for t in texts for n in PH.findall(t)))
if plural:
    names = ['count'] + [n for n in names if n != 'count']
print('L.%s' % key + ('(%s)' % ', '.join(names) if names else ''))
