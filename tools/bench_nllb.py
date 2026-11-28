"""Compare NLLB translation speed and output for ONNX Runtime optimization levels and thread counts.

Mirrors the app's greedy decoding (Nllb.kt). Run after tools/prepare_models.py:
    pip install onnxruntime==1.22.0 sentencepiece numpy
    python tools/bench_nllb.py model-assets/embedded_models
"""
import json
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import sentencepiece as spm

EOS = 2
SENTENCES = [
    ("rus", "sna", "Как вас зовут?"),
    ("rus", "sna", "так тест перевода с руского языка"),
    ("rus", "sna", "так тест перевода с русского языка"),
    ("rus", "sna", "Встречу перенесли на пятницу. Адрес не изменился."),
    ("sna", "rus", "Zita rako ndiani? Ndinokutendai zvikuru."),
    ("sna", "rus", "Ndiri kuenda kumusika mangwana mangwanani."),
]


def session(path, level, threads):
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    options.enable_mem_pattern = False
    options.enable_cpu_mem_arena = False
    options.graph_optimization_level = level
    started = time.perf_counter()
    result = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
    return result, time.perf_counter() - started


def translate(encoder, decoder, sp, config, src, dst, text):
    pieces = sp.encode(text)
    ids = [config[src]] + [3 if p == 0 else p + 1 for p in pieces] + [EOS]
    ids = np.array([ids], dtype=np.int64)
    mask = np.ones_like(ids)
    hidden = encoder.run(["last_hidden_state"], {"input_ids": ids, "attention_mask": mask})[0]
    past_names = [i.name for i in decoder.get_inputs() if i.name.startswith("past_key_values.")]
    outputs = [o.name for o in decoder.get_outputs()]
    empty = np.zeros((1, 16, 0, 64), dtype=np.float32)
    feeds = {n: empty for n in past_names}
    tokens = []
    cross = {}
    step_ids = np.array([[EOS, config[dst]]], dtype=np.int64)
    for step in range(min(512, max(64, 3 * ids.shape[1] + 32))):
        feeds.update({"input_ids": step_ids, "encoder_hidden_states": hidden, "encoder_attention_mask": mask,
                      "use_cache_branch": np.array([step > 0])})
        result = dict(zip(outputs, decoder.run(outputs, feeds)))
        token = int(result["next_token"].reshape(-1)[0])
        if token == EOS:
            break
        tokens.append(token)
        for name in past_names:
            present = "present." + name[len("past_key_values."):]
            if ".encoder." in name:
                if step == 0:
                    cross[name] = result[present]
                feeds[name] = cross[name]
            else:
                feeds[name] = result[present]
        step_ids = np.array([[token]], dtype=np.int64)
    pieces = [t - 1 for t in tokens if 4 <= t <= config["piece_count"]]
    return sp.decode(pieces), len(tokens)


def main():
    root = Path(sys.argv[1]) / "nllb"
    config = json.loads((root / "config.json").read_text())
    sp = spm.SentencePieceProcessor(model_file=str(root / "sentencepiece.bpe.model"))
    levels = [("BASIC", ort.GraphOptimizationLevel.ORT_ENABLE_BASIC),
              ("EXTENDED", ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED)]
    reference = None
    report = {}
    for threads in (2, 4):
        for name, level in levels:
            encoder, enc_load = session(root / "encoder.onnx", level, threads)
            decoder, dec_load = session(root / "decoder.onnx", level, threads)
            translate(encoder, decoder, sp, config, "rus", "sna", "Привет")  # warm-up
            outputs, total, steps = [], 0.0, 0
            for src, dst, text in SENTENCES:
                started = time.perf_counter()
                out, count = translate(encoder, decoder, sp, config, src, dst, text)
                total += time.perf_counter() - started
                steps += count
                outputs.append(out)
            key = f"{name} threads={threads}"
            report[key] = {"load_s": round(enc_load + dec_load, 2), "translate_s": round(total, 2),
                           "ms_per_token": round(1000 * total / max(1, steps), 1)}
            print(key, json.dumps(report[key]), flush=True)
            if reference is None:
                reference = outputs
                for (src, dst, text), out in zip(SENTENCES, outputs):
                    print(f"  {src}->{dst}: {text} => {out}", flush=True)
            else:
                same = sum(a == b for a, b in zip(reference, outputs))
                print(f"  identical outputs: {same}/{len(outputs)}", flush=True)
                for (src, dst, text), a, b in zip(SENTENCES, reference, outputs):
                    if a != b:
                        print(f"  DIFF {text}: {a!r} vs {b!r}", flush=True)
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
