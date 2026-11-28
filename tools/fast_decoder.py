"""Build the recipe that turns the shipped NLLB decoder into the faster decoder the app uses.

The shipped INT8 decoder computes the output layer by dequantizing the whole 256k x 1024
vocabulary matrix to float on every decoding step (about 70 % of translation time on phones).
The derivative keeps the same INT8 weights and multiplies them directly with MatMulInteger, like
every other layer of the model, and transposes the matrix once for both decoder branches. It also has the beam-search outputs of the previous derivative.

The app applies the recipe on the phone (BeamDecoder.kt): it copies byte ranges of the installed
decoder.onnx and inserts the small changed parts, so no new weights are downloaded.

    pip install onnx==1.23.2
    python tools/fast_decoder.py model-assets/embedded_models/nllb app/src/main/resources/decoder-beam.json
"""
import base64
import hashlib
import json
import sys
from pathlib import Path

import onnx
from onnx import helper

BIG = 1 << 16


def apply(recipe, source):
    out = bytearray()
    for op in recipe["ops"]:
        if "data" in op:
            out += base64.b64decode(op["data"])
        else:
            start, length = op["copy"]
            out += source[start:start + length]
    return bytes(out)


def patch_output_layer(graph):
    """Replace Transpose -> DequantizeLinear -> MatMul of the LM head inside both If branches."""
    patched = 0
    for node in graph.node:
        for attribute in node.attribute:
            if attribute.type != onnx.AttributeProto.GRAPH:
                continue
            sub = attribute.g
            nodes = list(sub.node)
            producers = {out: n for n in nodes for out in n.output}
            for index, matmul in enumerate(nodes):
                if matmul.op_type != "MatMul" or "lm_head" not in matmul.name:
                    continue
                dq = producers.get(matmul.input[1])
                if dq is None or dq.op_type != "DequantizeLinear":
                    continue
                transposed, scale, zero = dq.input
                hidden = matmul.input[0]
                p = matmul.name + "_int8"
                replacement = [
                    helper.make_node("DynamicQuantizeLinear", [hidden], [p + "/x", p + "/x_scale", p + "/x_zero"],
                                     name=p + "/quantize"),
                    helper.make_node("MatMulInteger", [p + "/x", transposed, p + "/x_zero", zero], [p + "/acc"],
                                     name=p + "/matmul"),
                    helper.make_node("Cast", [p + "/acc"], [p + "/acc_float"], name=p + "/cast",
                                     to=onnx.TensorProto.FLOAT),
                    helper.make_node("Mul", [p + "/x_scale", scale], [p + "/scale"], name=p + "/scale"),
                    helper.make_node("Mul", [p + "/acc_float", p + "/scale"], list(matmul.output), name=p + "/rescale"),
                ]
                kept = [n for n in nodes if n is not dq and n is not matmul]
                position = kept.index(nodes[index - 1]) + 1 if index > 0 and nodes[index - 1] is not dq else len(kept)
                position = min(position, len(kept))
                kept[position:position] = replacement
                del sub.node[:]
                sub.node.extend(kept)
                patched += 1
                break
    if patched != 2:
        raise SystemExit(f"Expected the output layer in both decoder branches, patched {patched}.")
    # Both branches transposed the same INT8 matrix, and ONNX Runtime folded each into its own
    # 250 MB constant. One transpose in the main graph is shared by both branches.
    shared = None
    for node in graph.node:
        for attribute in node.attribute:
            if attribute.type != onnx.AttributeProto.GRAPH:
                continue
            sub = attribute.g
            transposes = [n for n in sub.node if n.op_type == "Transpose" and any(
                m.op_type == "MatMulInteger" and n.output[0] in m.input for m in sub.node)]
            if len(transposes) != 1 or transposes[0].attribute:
                raise SystemExit("Unexpected output-layer transpose.")
            shared = shared or helper.make_node("Transpose", list(transposes[0].input), list(transposes[0].output),
                                                name="nikita_shared_output_transpose")
            sub.node.remove(transposes[0])
    graph.node.insert(0, shared)


def tensors(model):
    """Every large raw tensor payload, in serialization order of the main graph."""
    return [t.raw_data for t in model.graph.initializer if len(t.raw_data) >= BIG]


def locate(blob, payloads):
    offsets, start = [], 0
    for raw in payloads:
        at = blob.find(raw[:512], start)
        while at >= 0 and blob[at:at + len(raw)] != raw:
            at = blob.find(raw[:512], at + 1)
        if at < 0:
            raise SystemExit("A weight tensor was not found in the serialized decoder.")
        offsets.append(at)
        start = at + len(raw)
    return offsets


def gap_ops(new, old, block=32):
    """Copy runs of [new] that exist in [old] (both small), insert the rest. Returns (ops, inserted)."""
    index = {}
    for i in range(0, max(0, len(old) - block + 1), 8):
        index.setdefault(old[i:i + block], i)
    ops, pending, q = [], bytearray(), 0
    while q < len(new):
        hit = index.get(new[q:q + block]) if q + block <= len(new) else None
        if hit is None:
            pending.append(new[q])
            q += 1
            continue
        length = block
        while q + length < len(new) and hit + length < len(old) and new[q + length] == old[hit + length]:
            length += 1
        if length < 128:
            pending += new[q:q + length]
            q += length
            continue
        if pending:
            ops.append(("data", bytes(pending)))
            pending = bytearray()
        ops.append(("copy", hit, length))
        q += length
    if pending:
        ops.append(("data", bytes(pending)))
    return ops


def recipe_between(source, target, source_model, target_model):
    payloads = tensors(target_model)
    if [len(p) for p in payloads] != [len(p) for p in tensors(source_model)]:
        raise SystemExit("Weight tensors differ between the decoders.")
    in_source, in_target = locate(source, payloads), locate(target, payloads)
    ops, s_prev, t_prev = [], 0, 0
    for raw, s_at, t_at in zip(payloads + [b""], in_source + [len(source)], in_target + [len(target)]):
        for op in gap_ops(target[t_prev:t_at], source[s_prev:s_at]):
            ops.append({"data": base64.b64encode(op[1]).decode()} if op[0] == "data"
                       else {"copy": [s_prev + op[1], op[2]]})
        if raw:
            ops.append({"copy": [s_at, len(raw)]})
        s_prev, t_prev = s_at + len(raw), t_at + len(raw)
    merged = []
    for op in ops:
        if merged and "copy" in op and "copy" in merged[-1] and sum(merged[-1]["copy"]) == op["copy"][0]:
            merged[-1]["copy"][1] += op["copy"][1]
        else:
            merged.append(op)
    if apply({"ops": merged}, source) != target:
        raise SystemExit("The recipe does not reproduce the decoder.")
    return merged


def main():
    nllb, recipe_path = Path(sys.argv[1]), Path(sys.argv[2])
    write_model = len(sys.argv) > 3 and sys.argv[3] == "--write-model"
    source = (nllb / "decoder.onnx").read_bytes()
    recipe = json.loads(recipe_path.read_text())
    source_sha = hashlib.sha256(source).hexdigest()
    if source_sha != recipe["source_sha256"]:
        raise SystemExit("decoder.onnx is not the shipped decoder.")
    if recipe.get("kind") == "int8-output":
        print("Recipe already builds the INT8 output layer.")
        return
    beam = apply(recipe, source)
    if hashlib.sha256(beam).hexdigest() != recipe["sha256"]:
        raise SystemExit("The current recipe does not reproduce its decoder.")
    model = onnx.load_from_string(beam)
    patch_output_layer(model.graph)
    onnx.checker.check_model(model, full_check=False)
    target = model.SerializeToString()
    ops = recipe_between(source, target, onnx.load_from_string(source), model)
    inserted = sum(len(base64.b64decode(op["data"])) for op in ops if "data" in op)
    result = {
        "kind": "int8-output",
        "source_sha256": source_sha,
        "source_bytes": len(source),
        "sha256": hashlib.sha256(target).hexdigest(),
        "bytes": len(target),
        "ops": ops,
    }
    recipe_path.write_text(json.dumps(result, indent=2) + "\n")
    print(f"decoder {len(target)} bytes, {len(ops)} operations, {inserted} inserted bytes, sha256 {result['sha256']}")
    if write_model:
        (nllb / f"decoder-{result['sha256'][:16]}.onnx").write_bytes(target)


if __name__ == "__main__":
    main()
