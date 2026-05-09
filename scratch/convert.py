import json
import re
import sys

def convert_md_to_jsonl(md_path, jsonl_path):
    examples = []
    current_category = "UNCERTAIN"
    
    with open(md_path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line.startswith('##'):
                current_category = line.replace('##', '').strip().upper()
            elif line and line[0].isdigit():
                text = re.sub(r'^\d+\.\s*', '', line).strip()
                if text:
                    examples.append({"category": current_category, "text": text})
                    
    with open(jsonl_path, 'w', encoding='utf-8') as f:
        for ex in examples:
            f.write(json.dumps(ex, ensure_ascii=False) + '\n')
            
if __name__ == '__main__':
    convert_md_to_jsonl(sys.argv[1], sys.argv[2])
