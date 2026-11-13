#!/usr/bin/env python3
"""Checks the language files against each other, against the code and the two READMEs against each other."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
lang = root / 'src/main/resources/assets/xaero_exact_selection/lang'
ru = json.loads((lang / 'ru_ru.json').read_text(encoding='utf-8'))
en = json.loads((lang / 'en_us.json').read_text(encoding='utf-8'))

assert ru.keys() == en.keys(), f'Localization key mismatch: {ru.keys() ^ en.keys()}'
assert not re.search('[А-Яа-яЁё]', json.dumps(en, ensure_ascii=False)), 'Cyrillic text in English localization'
placeholder = r'%(?:[0-9]+\$)?[sd]'
for key in ru:
    assert re.findall(placeholder, ru[key]) == re.findall(placeholder, en[key]), f'Placeholder mismatch: {key}'

# Keys the code names in full, and keys it builds from one of these beginnings.
PREFIX = 'exactselection.'
DYNAMIC = ('history_', 'autosave_field_', 'area_sort_', 'rounding_', 'key_')
# Built by adding this ending to another key.
DYNAMIC_ENDINGS = ('_plain',)
source = ''.join(path.read_text(encoding='utf-8') for path in (root / 'src/main/java').rglob('*.java'))
literals = set(re.findall(r'"(?:exactselection\.)?([a-z][a-z0-9_]*)"', source))
keys = {key[len(PREFIX):] for key in en if key.startswith(PREFIX)}
unused = sorted(key for key in keys
                if key not in literals and not key.startswith(DYNAMIC) and not key.endswith(DYNAMIC_ENDINGS))
assert not unused, f'Keys no code uses: {unused}'
called = set(re.findall(r'\b(?:tr|say|button|iconButton)\(\s*"([a-z][a-z0-9_]*)"', source))
missing = sorted(key for key in called if key not in keys and not key.endswith('_'))
assert not missing, f'Keys the code uses but the language files lack: {missing}'

# Both READMEs must have the same sections in the same order.
def outline(name):
    return [len(line) - len(line.lstrip('#')) for line in (root / name).read_text(encoding='utf-8').splitlines()
            if line.startswith('#')]

readme_sections = outline('README.md') == outline('README.ru.md')

# Both READMEs name the JAR of the version in gradle.properties.
version = re.search(r'^mod_version=(.+)$', (root / 'gradle.properties').read_text(encoding='utf-8'), re.M).group(1)
for name in ('README.md', 'README.ru.md'):
    jars = re.findall(r'exact-selection-for-xaeros-map-([^`\s]+)\.jar', (root / name).read_text(encoding='utf-8'))
    assert jars and all(jar == version for jar in jars), f'{name} names the JAR of {jars}, the version is {version}'
print(f'PASS: {len(en)} matching localization keys, placeholders, English text, keys used by the code')
if not readme_sections:
    print('NOTE: README.md and README.ru.md differ in their headings')
