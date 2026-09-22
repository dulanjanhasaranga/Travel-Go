import zipfile
import xml.etree.ElementTree as ET
import sys

docx_path = r"c:\Users\User\Downloads\ISE\ISE\TravelGo_IE3121_Business_Architecture_Assessment (2).docx"
out_path = r"c:\Users\User\Downloads\ISE\ISE\extracted_doc.txt"

with zipfile.ZipFile(docx_path, 'r') as zf:
    xml_content = zf.read('word/document.xml')

tree = ET.fromstring(xml_content)

text_list = []
for p in tree.findall('.//{http://schemas.openxmlformats.org/wordprocessingml/2006/main}p'):
    texts = [node.text for node in p.findall('.//{http://schemas.openxmlformats.org/wordprocessingml/2006/main}t') if node.text]
    if texts:
        text_list.append(''.join(texts))

with open(out_path, 'w', encoding='utf-8') as f:
    f.write('\n'.join(text_list))
