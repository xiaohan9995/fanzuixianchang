#!/usr/bin/env python3
"""Validate Chinese names against immutable source records; emit coverage counts."""
import csv, hashlib, json
from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'src/main/resources/nutrition'
source = root / 'usda-sr2018.tsv'
provenance = json.loads((root / 'provenance.json').read_text())
assert hashlib.sha256(source.read_bytes()).hexdigest() == provenance['catalogSha256'], 'Nutrition source changed'
foods = {r['fdcId']: r for r in csv.DictReader(source.open(), delimiter='\t')}
rows = list(csv.DictReader((root / 'food-names-zh.tsv').open(), delimiter='\t'))
seen, aliases = set(), set()
for row in rows:
    ident = row['fdcId']
    assert ident not in seen, f'Duplicate ID: {ident}'
    assert ident in foods, f'Missing food: {ident}'
    assert row['sourceDescription'] == foods[ident]['description'], f'Source mismatch: {ident}'
    assert any('\u4e00' <= c <= '\u9fff' for c in row['nameZh']), f'Missing Chinese name: {ident}'
    assert row['aliases'].strip(), f'Missing aliases: {ident}'
    seen.add(ident)
    aliases.update(row['aliases'].split())
print(json.dumps({'sourceRecords': len(foods), 'chineseRecords': len(rows), 'uniqueChineseAliases': len(aliases), 'sourceUnchanged': True}, ensure_ascii=False, indent=2))
