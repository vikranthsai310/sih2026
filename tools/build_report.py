import io
import subprocess
import sys

import fitz

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
D = r'C:\Users\DELL\AppData\Local\Temp\claude\D--Downloads-PROJECTS-SIH2026\dd5256fc-5e29-4899-bff1-2a9972911c8a\scratchpad'
PDF = r'D:\Downloads\PROJECTS\SIH2026\docs\iTantra Project Report.pdf'

src = open(D + r'\report4.html', encoding='utf-8').read()
if '__LOGO__' in src:
    src = src.replace('__LOGO__', open(D + r'\logo.b64').read().strip())
    open(D + r'\report4.html', 'w', encoding='utf-8').write(src)
open(D + r'\render4.html', 'w', encoding='utf-8').write(src)

subprocess.run([r'C:\Program Files\Google\Chrome\Application\chrome.exe',
                '--headless', '--disable-gpu', '--no-sandbox',
                '--run-all-compositor-stages-before-draw',
                '--virtual-time-budget=15000', '--no-pdf-header-footer',
                '--print-to-pdf=' + PDF,
                'file:///' + (D + r'\render4.html').replace('\\', '/')],
               capture_output=True)

d = fitz.open(PDF)
n = d.page_count
for i, page in enumerate(d):
    if i == 0:
        continue                                   # title page carries no number
    lbl = str(i + 1)
    page.insert_text((page.rect.width / 2 - 3 * len(lbl), page.rect.height - 26),
                     lbl, fontname='times-roman', fontsize=10.5, color=(0, 0, 0))
d.saveIncr()
d.close()

d = fitz.open(PDF)
print('pages:', d.page_count, '| A4' if abs(d[0].rect.height - 842) < 2 else '| NOT A4')
print()
for i, p in enumerate(d):
    b = [x for x in p.get_text('blocks') if x[4].strip()]
    b.sort(key=lambda x: x[1])
    head = ' '.join(b[0][4].split())[:56] if b else '(blank)'
    left = min((x[0] for x in b), default=0)
    right = max((x[2] for x in b), default=0)
    print('  p%2d  x %3.0f-%3.0f  %s' % (i + 1, left, right, head))

cols = set()
for page in d:
    for b in page.get_text('dict')['blocks']:
        for l in b.get('lines', []):
            for sp in l['spans']:
                cols.add(sp['color'])
print()
print('text colours:', sorted(cols), '(0 = black)')
print('images:', sum(len(d[i].get_images()) for i in range(d.page_count)))
