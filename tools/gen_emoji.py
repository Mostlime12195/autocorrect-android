"""app/src/main/assets/emoji.tsv from Unicode's emoji-test.txt (fully-qualified emoji, no skin-tone variants).
Data: Unicode Emoji, (c) Unicode, Inc., Unicode License v3 (https://www.unicode.org/license.txt).
  python3 tools/gen_emoji.py emoji-test.txt app/src/main/assets/emoji.tsv
"""
import sys
src, dst = sys.argv[1], sys.argv[2]
TONES = {chr(c) for c in range(0x1F3FB, 0x1F400)}
GROUPS = {'Smileys & Emotion': 'smileys', 'People & Body': 'people', 'Animals & Nature': 'nature', 'Food & Drink': 'food',
          'Travel & Places': 'travel', 'Activities': 'activities', 'Objects': 'objects', 'Symbols': 'symbols', 'Flags': 'flags'}
out, group = [], None
for line in open(src, encoding='utf-8'):
    if line.startswith('# group:'):
        group = GROUPS.get(line.split(':', 1)[1].strip()); continue
    if not group or line.startswith('#') or ';' not in line: continue
    codes, rest = line.split(';', 1)
    if 'fully-qualified' not in rest.split('#')[0]: continue
    emo = ''.join(chr(int(c, 16)) for c in codes.split())
    if any(ch in TONES for ch in emo): continue
    name = rest.split('#', 1)[1].strip().split(' ', 2)[-1]
    out.append(f'{group}\t{emo}\t{name}')
open(dst, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print(len(out), 'emoji ->', dst)
