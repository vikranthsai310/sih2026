import io
import os
import subprocess
import sys

import fitz

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

# Working directory holding report4.html and logo.b64. Override with argv[1] or
# REPORT_BUILD_DIR; defaults to build/report beside this checkout.
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
D = (sys.argv[1] if len(sys.argv) > 1
     else os.environ.get('REPORT_BUILD_DIR') or os.path.join(ROOT, 'build', 'report'))
PDF = os.path.join(ROOT, 'docs', 'Taraketu_iTantra_Report.pdf')

REPORT = os.path.join(D, 'report4.html')
RENDER = os.path.join(D, 'render4.html')

src = open(REPORT, encoding='utf-8').read()
if '__LOGO__' in src:
    src = src.replace('__LOGO__', open(os.path.join(D, 'logo.b64')).read().strip())
    open(REPORT, 'w', encoding='utf-8').write(src)
open(RENDER, 'w', encoding='utf-8').write(src)

subprocess.run([r'C:\Program Files\Google\Chrome\Application\chrome.exe',
                '--headless', '--disable-gpu', '--no-sandbox',
                '--run-all-compositor-stages-before-draw',
                '--virtual-time-budget=15000', '--no-pdf-header-footer',
                '--print-to-pdf=' + PDF,
                'file:///' + RENDER.replace('\\', '/')],
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
