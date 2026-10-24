#!/usr/bin/env python3
import json
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/xaero_exact_selection/lang'
ru = json.loads((root / 'ru_ru.json').read_text())
en = json.loads((root / 'en_us.json').read_text())
assert ru.keys() == en.keys(), f'Localization key mismatch: {ru.keys() ^ en.keys()}'
assert not re.search('[А-Яа-яЁё]', json.dumps(en, ensure_ascii=False)), 'Cyrillic text in English localization'
for key in ru:
    assert re.findall(r'%(?:[0-9]+\$)?[sd]', ru[key]) == re.findall(r'%(?:[0-9]+\$)?[sd]', en[key]), f'Placeholder mismatch: {key}'
print(f'PASS: {len(en)} matching localization keys, placeholders and English text')
