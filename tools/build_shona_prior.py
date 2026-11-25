"""Build the Shona speech recognizer's character counts using local source data.

Inputs: google/fleurs data/sn_zw/train.tsv, the Leipzig sna-zw_web_2018_100K
archive, and the installed MMS asr/sna.tokens.json. See asr_language/NOTICE.txt.
"""
import argparse
from collections import Counter
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import tarfile
import unicodedata


FLEURS_SHA256 = '7f347d4951b63922957524695b35ca2e3f6ecc35f2a58e1e9d6a78d26cb71be2'
LEIPZIG_SHA256 = 'a821d2f8f69fb8a43b471f2fde27279992e39df7e9775defab92ebfa89def958'
ORDER = 5
MIN_COUNTS = (0, 1, 1, 2, 3, 6)


def normalize(sentence):
    sentence = unicodedata.normalize('NFC', sentence.lower())
    return ' '.join(re.sub(r"[^\w\s'ʼ-]+", ' ', sentence).split())


def add_counts(counts, sentences, alphabet, weight):
    for sentence in sentences:
        text = ' ' * (ORDER - 1) + ''.join(c for c in sentence if c in alphabet)
        for end in range(ORDER, len(text) + 1):
            for length in range(1, ORDER + 1):
                counts[text[end - length:end]] += weight


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('fleurs_train', type=Path)
    parser.add_argument('leipzig_archive', type=Path)
    parser.add_argument('tokens_json', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    for path, expected in ((args.leipzig_archive, LEIPZIG_SHA256),
                           (args.fleurs_train, FLEURS_SHA256)):
        with path.open('rb') as handle:
            if hashlib.file_digest(handle, 'sha256').hexdigest() != expected:
                raise ValueError(f'Unexpected SHA-256 for {path}')
    with tarfile.open(args.leipzig_archive) as data:
        name = 'sna-zw_web_2018_100K/sna-zw_web_2018_100K-sentences.txt'
        with io.TextIOWrapper(data.extractfile(name), encoding='utf-8') as text:
            web = {normalize(row.split('\t', 1)[1]) for row in text}
    with args.fleurs_train.open(encoding='utf-8', newline='') as text:
        train = {normalize(row[3]) for row in csv.reader(text, delimiter='\t') if len(row) > 3}
    vocab = json.loads(args.tokens_json.read_text(encoding='utf-8'))['tokens']
    alphabet = {c for c in vocab if len(c) == 1} | {' '}
    counts = Counter()
    add_counts(counts, web, alphabet, 1)
    add_counts(counts, train, alphabet, 4)
    compact = {k: v for k, v in sorted(counts.items()) if v >= MIN_COUNTS[len(k)]}
    args.output.write_text(json.dumps(compact, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')


if __name__ == '__main__':
    main()
