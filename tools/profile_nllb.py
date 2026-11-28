"""Find where NLLB decoding time goes: graph structure and an ONNX Runtime per-node profile.

    pip install onnxruntime==1.22.0 onnx sentencepiece numpy
    python tools/profile_nllb.py model-assets/embedded_models
"""
import collections
import glob
import json
import os
import sys
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import sentencepiece as spm

sys.path.insert(0, str(Path(__file__).parent))
from bench_nllb import translate  # noqa: E402


def describe(path):
    model = onnx.load(str(path), load_external_data=False)
    sizes = {}
    for init in model.graph.initializer:
        n = 1
        for d in init.dims:
            n *= d
        sizes[init.name] = (n, onnx.TensorProto.DataType.Name(init.data_type), list(init.dims))

    def walk(graph, depth, label):
        ops = collections.Counter(node.op_type for node in graph.node)
        print(f"{'  ' * depth}{label}: {len(graph.node)} nodes {dict(ops.most_common(14))}")
        for node in graph.node:
            big = [(i, sizes[i]) for i in node.input if i in sizes and sizes[i][0] >= 50_000_000]
            if big:
                print(f"{'  ' * depth}  BIG {node.op_type} {node.name}: {big} -> {list(node.output)}")
            for attr in node.attribute:
                if attr.type == onnx.AttributeProto.GRAPH:
                    walk(attr.g, depth + 1, f"{node.op_type}.{attr.name}")
    print(path.name, "opset", [(o.domain, o.version) for o in model.opset_import])
    walk(model.graph, 0, "main")


def profile(root, config, sp, level):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    options.graph_optimization_level = level
    encoder = ort.InferenceSession(str(root / "encoder.onnx"), options, providers=["CPUExecutionProvider"])
    options.enable_profiling = True
    options.profile_file_prefix = f"decoder-{int(level)}"
    decoder = ort.InferenceSession(str(root / "decoder.onnx"), options, providers=["CPUExecutionProvider"])
    text = "так теперь тест непрерывного перевода вот я что-то говорю за сколько секунд это переведёшь на другой язык"
    started = time.perf_counter()
    out, steps = translate(encoder, decoder, sp, config, "rus", "sna", text)
    elapsed = time.perf_counter() - started
    print(f"level={level} steps={steps} total={elapsed:.2f}s per_token={1000 * elapsed / max(1, steps):.0f}ms => {out}")
    trace = json.loads(Path(decoder.end_profiling()).read_text())
    by_op, by_node = collections.Counter(), collections.Counter()
    for event in trace:
        if event.get("cat") != "Node" or not event.get("name", "").endswith("_kernel_time"):
            continue
        op = event.get("args", {}).get("op_name", "?")
        by_op[op] += event["dur"]
        by_node[(op, event["name"][:-12])] += event["dur"]
    total = sum(by_op.values()) or 1
    print(f"  kernel time {total / 1e6:.2f}s, by op:")
    for op, dur in by_op.most_common(15):
        print(f"    {op:28s} {dur / 1e6:7.2f}s {100 * dur / total:5.1f}%")
    print("  top nodes:")
    for (op, name), dur in by_node.most_common(15):
        print(f"    {op:24s} {name[:70]:70s} {dur / 1e6:7.2f}s")


def main():
    root = Path(sys.argv[1]) / "nllb"
    config = json.loads((root / "config.json").read_text())
    sp = spm.SentencePieceProcessor(model_file=str(root / "sentencepiece.bpe.model"))
    for name in ("decoder.onnx", "encoder.onnx"):
        describe(root / name)
    print("files:", {p.name: p.stat().st_size for p in root.iterdir()})
    profile(root, config, sp, ort.GraphOptimizationLevel.ORT_ENABLE_BASIC)


if __name__ == "__main__":
    main()
