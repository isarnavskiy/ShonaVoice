"""Replay the published dev/test comparison with both production Kotlin decoders.

Requires Python 3.11+, numpy, scipy, onnxruntime==1.22.0, git, Kotlin/JVM and JDK 17.
The data directory must contain {dev,test}.tsv and audio/{dev,test}.tar.gz from
sn_zw in the FLEURS revision recorded in docs/quality/shona-asr.json.
"""
import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unicodedata

import numpy as np
import onnxruntime as ort
from scipy.io import wavfile


ROOT = Path(__file__).resolve().parents[1]
REPORT = json.loads((ROOT / 'docs/quality/shona-asr.json').read_text())
SOURCE = Path('app/src/main/java/dev/nikita/voice/CtcDecoder.kt')
RESOURCES = Path('app/src/main/resources')
PRIOR = RESOURCES / 'asr_language/sna.json'


def check_file(path, digest):
    with path.open('rb') as file:
        if hashlib.file_digest(file, 'sha256').hexdigest() != digest:
            raise ValueError(f'SHA-256 mismatch for {path}')


def normalize(text):
    lower = unicodedata.normalize('NFC', text.lower())
    return ' '.join(''.join(' ' if unicodedata.category(c).startswith('P') else c for c in lower).split())


def distance(reference, result):
    row = list(range(len(result) + 1))
    for index, token in enumerate(reference):
        previous = row
        row = [index + 1]
        for j, actual in enumerate(result):
            row.append(min(previous[j] + (token != actual), previous[j + 1] + 1, row[-1] + 1))
    return row[-1]


def write_logits(model_root, archive, fixtures, target):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    options.enable_mem_pattern = False
    options.enable_cpu_mem_arena = False
    session = ort.InferenceSession(str(model_root / 'asr/sna.onnx'), options, providers=['CPUExecutionProvider'])
    remaining = {row['file']: row['id'] for row in fixtures}
    with tarfile.open(archive, 'r|gz') as stream:
        for member in stream:
            name = Path(member.name).name
            if name not in remaining:
                continue
            rate, audio = wavfile.read(io.BytesIO(stream.extractfile(member).read()))
            if rate != 16000 or audio.ndim != 1 or audio.dtype.kind != 'f':
                raise ValueError(f'Unexpected source audio format for {name}')
            pcm = np.rint(np.clip(audio, -1, 32767/32768) * 32768).astype(np.int16)
            feed = {'input_values': pcm[None, :].astype(np.float32) / 32768,
                    'input_lengths': np.array([pcm.size], dtype=np.int64)}
            scores = session.run(None, feed)[0][0]
            scores.astype('<f4').tofile(target / (remaining.pop(name) + '.bin'))
            if not remaining:
                break
    if remaining:
        raise ValueError(f'{len(remaining)} recordings missing in {archive}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('split', choices=['dev', 'test'])
    parser.add_argument('data', type=Path, help='FLEURS sn_zw directory')
    parser.add_argument('model_root', type=Path, help='Directory containing asr/sna.onnx and its weights')
    parser.add_argument('json_jar', type=Path, help='org.json:json:20250517 JAR from Maven Central')
    args = parser.parse_args()
    if ort.__version__ != '1.22.0':
        raise ValueError('Use onnxruntime==1.22.0')
    check_file(args.json_jar, '3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796')
    for name, digest in REPORT['recognizer']['source_files'].items():
        check_file(args.model_root / name, digest)
    hashes = REPORT['dataset']['sha256']
    tsv = args.data / (args.split + '.tsv')
    archive = args.data / 'audio' / (args.split + '.tar.gz')
    check_file(tsv, hashes[tsv.name])
    check_file(archive, hashes['audio/' + archive.name])
    with tsv.open(encoding='utf-8') as file:
        refs = {r[1]: normalize(r[3]) for r in csv.reader(file, delimiter='\t') if len(r) > 3}
    data = REPORT['splits'][args.split]['recordings']
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        write_logits(args.model_root, archive, data, work)
        baseline = work / 'baseline'
        for item in (SOURCE, PRIOR):
            destination = baseline / item
            destination.parent.mkdir(parents=True, exist_ok=True)
            revision = REPORT['decoder']['baseline_git_commit']
            destination.write_bytes(subprocess.check_output(['git', '-C', str(ROOT), 'show', f'{revision}:{item.as_posix()}']))
        for label, version in [('baseline', baseline), ('candidate', ROOT)]:
            jar = work / (label + '.jar')
            subprocess.run(['kotlinc', version / SOURCE, ROOT / 'tools/DecodeShonaCheck.kt', '-classpath', args.json_jar,
                            '-jvm-target', '17', '-include-runtime', '-d', jar], check=True)
            cp = os.pathsep.join(map(str, [jar, args.json_jar, version / RESOURCES]))
            command = ['java', '-Xmx256m', '-cp', cp, 'dev.nikita.voice.DecodeShonaCheckKt',
                       args.model_root / 'asr/sna.tokens.json', work, str(REPORT['decoder'][label + '_weight'])]
            decoded = json.loads(subprocess.check_output(command + [r['id'] for r in data]))
            for row in data:
                reference, result = refs[row['file']], normalize(decoded[row['id']])
                for unit, value in [('word', distance(reference.split(), result.split())),
                                    ('character', distance(reference, result))]:
                    expected = row[f'{label}_{unit}_errors']
                    if value != expected:
                        raise ValueError(f'{label} {row["file"]}: expected {expected} {unit} errors, got {value}')
            print(f'{label}: {len(data)} recordings match the published per-record counts')


if __name__ == '__main__':
    main()
