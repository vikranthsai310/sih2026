import io
import re
import subprocess
import sys

import fitz

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
D = r'C:\Users\DELL\AppData\Local\Temp\claude\D--Downloads-PROJECTS-SIH2026\dd5256fc-5e29-4899-bff1-2a9972911c8a\scratchpad'
PDF = r'D:\Downloads\PROJECTS\SIH2026\docs\iTantra Project Report.pdf'

src = open(D + r'\report3.html', encoding='utf-8').read()
src = re.sub(r' <span>Page \d+</span>', '', src)      # folios belong at the foot
open(D + r'\report3.html', 'w', encoding='utf-8').write(src)
open(D + r'\render3.html', 'w', encoding='utf-8').write(src)

subprocess.run([r'C:\Program Files\Google\Chrome\Application\chrome.exe',
                '--headless', '--disable-gpu', '--no-sandbox',
                '--run-all-compositor-stages-before-draw',
                '--virtual-time-budget=14000', '--no-pdf-header-footer',
                '--print-to-pdf=' + PDF,
                'file:///' + (D + r'\render3.html').replace('\\', '/')],
               capture_output=True)

d = fitz.open(PDF)
print('pages:', d.page_count)
for i, page in enumerate(d):
    if i == 0:
        continue                                   # title page carries no number
    page.insert_text((page.rect.width / 2 - 3, page.rect.height - 30), str(i + 1),
                     fontname='times-roman', fontsize=10.5, color=(0, 0, 0))
d.saveIncr()
d.close()

d = fitz.open(PDF)
tails = ['Keywords: speech recognition', 'disclosed together with its mitigation',
         'single-byte identifiers', 'walkie-talkie, already familiar',
         'before any model exists', 'flush on reconnection',
         'not the handset software', 'Principal test cases', 'GPL-3.0']
ok = d.page_count == 10
print()
for i in range(1, 10):
    t = ' '.join(d[i].get_text().split())
    good = tails[i - 1] in t
    folio = t.rstrip().endswith(str(i + 1))
    ok &= good
    print('  p%2d  %s | folio %s' % (i + 1, 'complete' if good else 'CLIPPED ',
                                     'ok' if folio else 'missing'))

cols = set()
for page in d:
    for b in page.get_text('dict')['blocks']:
        for l in b.get('lines', []):
            for sp in l['spans']:
                cols.add(sp['color'])
print()
print('text colours in the document:', sorted(cols), '(0 = black)')
print('images:', sum(len(d[i].get_images()) for i in range(d.page_count)), '(the institute logo)')
print()
print('*** 10 PAGES, ONE SECTION PER PAGE, NOTHING CLIPPED ***' if ok else '*** PROBLEM ***')
