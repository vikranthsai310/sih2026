"""Two-pass pagination for the project report.

Chrome cannot paint page numbers on flowed pages (it has no support for @page
margin boxes), and the table of contents cannot know the real page numbers until
the document has been laid out.  So: render, read back where every chapter and
figure actually landed, rewrite the contents pages with the true numbers,
re-render, then stamp folios -- roman for front matter, arabic for the body.
"""
import io
import os
import re
import subprocess
import sys

import fitz

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
D = r'C:\Users\DELL\AppData\Local\Temp\claude\D--Downloads-PROJECTS-SIH2026\dd5256fc-5e29-4899-bff1-2a9972911c8a\scratchpad'
CHROME = r'C:\Program Files\Google\Chrome\Application\chrome.exe'
PDF = r'D:\Downloads\PROJECTS\SIH2026\docs\iTantra Project Report.pdf'
SRC = os.path.join(D, 'report2.html')
RND = os.path.join(D, 'render2.html')


def render(html_path, out_pdf):
    subprocess.run([CHROME, '--headless', '--disable-gpu', '--no-sandbox',
                    '--run-all-compositor-stages-before-draw',
                    '--virtual-time-budget=14000', '--no-pdf-header-footer',
                    '--print-to-pdf=' + out_pdf,
                    'file:///' + html_path.replace('\\', '/')],
                   capture_output=True)


# ---- pass 1 -------------------------------------------------------------
html = open(SRC, encoding='utf-8').read()
open(RND, 'w', encoding='utf-8').write(html)
render(RND, PDF)
doc = fitz.open(PDF)

# The body begins on the page carrying "CHAPTER 1"; everything before is front matter.
def find(needle, start=0):
    for i in range(start, doc.page_count):
        if needle in ' '.join(doc[i].get_text().split()):
            return i
    return None

body0 = find('CHAPTER 1')
print(f'total {doc.page_count} pages; front matter = {body0}; body starts at PDF page {body0+1}')


def arabic(pdf_index):
    """PDF page index -> printed arabic page number."""
    return pdf_index - body0 + 1


# where each numbered heading actually landed
targets = {
    '1': 'CHAPTER 1', '1.1': '1.1 Overview', '1.2': '1.2 Problem Statement',
    '1.3': '1.3 Objectives', '1.4': '1.4 Scope and Limitations',
    '2': 'CHAPTER 2', '2.1': '2.1 Speech Recognition for Indian Languages',
    '2.2': '2.2 On-Device Speech Synthesis', '2.3': '2.3 Messaging over Low-Bitrate Radio',
    '2.4': '2.4 Summary of the Gap',
    '3': 'CHAPTER 3', '3.1': '3.1 Existing System', '3.2': '3.2 Proposed System',
    '3.3': '3.3 Advantages of the Proposed System', '3.4': '3.4 Feasibility Study',
    '4': 'CHAPTER 4', '4.1': '4.1 Hardware Requirements', '4.2': '4.2 Software Requirements',
    '4.3': '4.3 Functional Requirements', '4.4': '4.4 Non-Functional Requirements',
    '5': 'CHAPTER 5', '5.1': '5.1 System Architecture', '5.2': '5.2 Module Description',
    '5.3': '5.3 Wire Protocol Design',
    '6': 'CHAPTER 6', '6.1': '6.1 Speech Recognition Module',
    '6.2': '6.2 Speech Synthesis Module', '6.3': '6.3 Compression Module',
    '6.4': '6.4 Transport and Security Modules',
    '7': 'CHAPTER 7', '7.1': '7.1 Evaluation Methodology', '7.2': '7.2 Test Cases',
    '8': 'CHAPTER 8', '8.1': '8.1 Compression Results',
    '8.2': '8.2 Accuracy, Latency and Efficiency Targets', '8.3': '8.3 Discussion',
    '9': 'CHAPTER 9', 'REF': 'REFERENCES',
    'F1.1': 'Figure 1.1:', 'F5.1': 'Figure 5.1:', 'F5.2': 'Figure 5.2:', 'F6.1': 'Figure 6.1:',
    'T3.1': 'Table 3.1:', 'T4.1': 'Table 4.1:', 'T4.2': 'Table 4.2:', 'T5.1': 'Table 5.1:',
    'T5.2': 'Table 5.2:', 'T6.1': 'Table 6.1:', 'T6.2': 'Table 6.2:', 'T7.1': 'Table 7.1:',
    'T8.1': 'Table 8.1:', 'T8.2': 'Table 8.2:', 'T8.3': 'Table 8.3:',
}
page_of = {}
for key, needle in targets.items():
    i = find(needle, body0 if key not in ('REF',) else 0)
    page_of[key] = arabic(i) if i is not None else '?'
doc.close()

# ---- rewrite the contents with the real numbers --------------------------
# every TOC / list row ends "<td class="p">NN</td>" -- key them by their label text
rows = {
    'INTRODUCTION': '1', 'Overview': '1.1', 'Problem Statement': '1.2', 'Objectives': '1.3',
    'Scope and Limitations': '1.4',
    'LITERATURE SURVEY': '2', 'Speech Recognition for Indian Languages': '2.1',
    'On-Device Speech Synthesis': '2.2', 'Messaging over Low-Bitrate Radio': '2.3',
    'Summary of the Gap': '2.4',
    'SYSTEM ANALYSIS': '3', 'Existing System': '3.1', 'Proposed System': '3.2',
    'Advantages of the Proposed System': '3.3', 'Feasibility Study': '3.4',
    'SYSTEM REQUIREMENTS': '4', 'Hardware Requirements': '4.1', 'Software Requirements': '4.2',
    'Functional Requirements': '4.3', 'Non-Functional Requirements': '4.4',
    'SYSTEM DESIGN': '5', 'System Architecture': '5.1', 'Module Description': '5.2',
    'Wire Protocol Design': '5.3',
    'IMPLEMENTATION': '6', 'Speech Recognition Module': '6.1', 'Speech Synthesis Module': '6.2',
    'Compression Module': '6.3', 'Transport Module': '6.4',
    'TESTING AND EVALUATION': '7', 'Evaluation Methodology': '7.1', 'Test Cases': '7.2',
    'RESULTS AND DISCUSSION': '8', 'Compression Results': '8.1',
    'Accuracy, Latency and Efficiency Targets': '8.2', 'Discussion': '8.3',
    'CONCLUSION AND FUTURE SCOPE': '9', 'REFERENCES': 'REF',
    'Comparison of data rates on a logarithmic scale': 'F1.1',
    'System architecture and end-to-end signal path': 'F5.1',
    'Frame format of the iTantra wire protocol': 'F5.2',
    'Demonstration and deployment topologies': 'F6.1',
    'Size of a three-second sentence in each representation': 'T3.1',
    'Hardware requirements': 'T4.1', 'Software requirements and licences': 'T4.2',
    'Module description': 'T5.1', 'Frame field description': 'T5.2',
    'Effect of quantisation on model size and speed': 'T6.1',
    'Comparison of the four transport implementations': 'T6.2',
    'Test cases and expected results': 'T7.1',
    'Frame size and time on a 300 bps link': 'T8.1',
    'Word error rate targets at four noise levels': 'T8.2',
    'Latency and efficiency targets': 'T8.3',
}
fixed = 0
for label, key in rows.items():
    pat = re.compile(r'(<td class="dots">' + re.escape(label) + r'</td><td class="p">)[^<]*(</td>)')
    html, n = pat.subn(lambda m: m.group(1) + str(page_of[key]) + m.group(2), html)
    fixed += n
print('contents rows updated:', fixed)

open(SRC, 'w', encoding='utf-8').write(html)
open(RND, 'w', encoding='utf-8').write(html)

# ---- pass 2 --------------------------------------------------------------
render(RND, PDF)
doc = fitz.open(PDF)
body0b = find('CHAPTER 1')
assert body0b == body0, f'pagination shifted: {body0} -> {body0b}'

# ---- stamp folios --------------------------------------------------------
ROMAN = ['', 'i', 'ii', 'iii', 'iv', 'v', 'vi', 'vii', 'viii', 'ix', 'x']
for i, page in enumerate(doc):
    if i == 0:
        continue                       # title page carries no printed number
    label = ROMAN[i + 1] if i < body0 else str(i - body0 + 1)
    page.insert_text((page.rect.width / 2 - len(label) * 2.6, page.rect.height - 34),
                     label, fontname='times-roman', fontsize=11, color=(0, 0, 0))
doc.saveIncr()
print('folios stamped: front matter ii..' + ROMAN[body0] + ', body 1..' + str(doc.page_count - body0))
print('pages:', doc.page_count)
