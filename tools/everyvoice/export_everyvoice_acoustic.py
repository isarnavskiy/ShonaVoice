"""Export the inference path of the EveryVoice 1.2 Shona FastSpeech2 checkpoint for single, unpadded requests."""

import argparse
import ast
import gc
import hashlib
import importlib.util
import json
import os
import tempfile
import zipfile
from pathlib import Path

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

# The two source packages are read at fixed versions. Only inference layers are used.
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--checkpoint", type=Path, required=True)
parser.add_argument("--everyvoice-wheel", type=Path, required=True)
parser.add_argument("--conformer-source", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()


def check_sha(path, expected):
    with path.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != expected:
            raise ValueError(f"Unexpected SHA-256 for {path.name}")


check_sha(
    args.checkpoint, "ca96fa117ec33f951ee6c16b07e429e8454604c9a513381c1692fa86701239ad"
)
check_sha(
    args.everyvoice_wheel,
    "bcd91a05eaadbd66e251c564f58cd05822c46acefe72d80170f6779ddc4f262f",
)
check_sha(
    args.conformer_source,
    "e4871e53e8dc64aa1f9074d3a912a8cadb9b43be4ccd93eb3e57e42ec225c5e0",
)
spec = importlib.util.spec_from_file_location(
    "upconformer", args.conformer_source.resolve()
)
upconformer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upconformer)
Conformer = upconformer.Conformer
checkpoint = args.checkpoint.resolve()
output = args.output.resolve()
output.mkdir(exist_ok=True, parents=True)
source_dir = tempfile.TemporaryDirectory()
source_prefix = "everyvoice/model/feature_prediction/FastSpeech2_lightning/fs2/"
FS = Path(source_dir.name)
with zipfile.ZipFile(args.everyvoice_wheel) as archive:
    for name in ("blocks.py", "layers.py", "variance_adaptor.py"):
        (FS / name).write_bytes(archive.read(source_prefix + name))
os.chdir(output)


def source_classes(path, names):
    tree = ast.parse(path.read_text())
    subset = ast.Module(
        [
            node
            for node in tree.body
            if isinstance(node, ast.ClassDef) and node.name in names
        ],
        type_ignores=[],
    )
    exec(compile(subset, str(path), "exec"), globals())


source_classes(FS / "blocks.py", {"ConvNorm", "DepthwiseSeparableConv1d"})
source_classes(
    FS / "layers.py",
    {"Transpose", "VarianceConvolutionLayer", "PostNet", "PositionalEmbedding"},
)
source_classes(FS / "variance_adaptor.py", {"VariancePredictor"})


class ExportAttention(nn.Module):
    def __init__(self, native):
        super().__init__()
        self.out_proj = native.out_proj
        self.in_proj_bias = native.in_proj_bias
        self.in_proj_weight = native.in_proj_weight
        self.width = native.embed_dim // native.num_heads
        self.heads = native.num_heads

    def forward(self, query, key, value, key_padding_mask=None, need_weights=False):
        t = query.shape[0]
        q, k, v = (
            F.linear(query[:, 0, :], self.in_proj_weight, self.in_proj_bias)
            .reshape(t, 3, self.heads, self.width)
            .permute(1, 2, 0, 3)
            .unbind(0)
        )
        scores = (q * (self.width ** (-0.5))) @ k.transpose(1, 2)
        out = (torch.softmax(scores, -1) @ v).transpose(0, 1).reshape(t, 1, -1)
        return self.out_proj(out), None


class Acoustic(nn.Module):
    def __init__(self, shape):
        super().__init__()
        self.text_input_layer = nn.Embedding(*shape)
        self.position_embedding = PositionalEmbedding(256)
        args = dict(
            input_dim=256,
            num_heads=2,
            ffn_dim=1024,
            num_layers=4,
            depthwise_conv_kernel_size=9,
            dropout=0.2,
        )
        self.encoder = Conformer(**args)
        self.decoder = Conformer(**args)
        self.speaker_embedding = nn.Embedding(1, 256)
        self.variance_adaptor = nn.Module()
        va = self.variance_adaptor
        for name in ["energy", "pitch", "duration"]:
            setattr(
                va,
                name + "_predictor",
                VariancePredictor(
                    input_dim=256,
                    n_layers=5,
                    n_channels=256,
                    output_dim=1,
                    kernel_size=3,
                    dropout_rate=0.5,
                    depthwise=True,
                ),
            )
            if name != "duration":
                setattr(va, name + "_embedding", nn.Embedding(256, 256))
                va.register_buffer(name + "_bins", torch.zeros(255))
        self.mel_linear = nn.Linear(256, 80)
        self.postnet = PostNet()
        self.portable = False

    def enc(self, x, stack):
        x = x + self.position_embedding(
            torch.arange(x.shape[1], device=x.device, dtype=x.dtype)
        )
        # Match torchaudio Conformer.forward, omitting the all-false key-padding mask in a batch of one.
        if not self.portable:
            lens = torch.full((1,), x.shape[1], device=x.device, dtype=torch.long)
            return stack(x, lens)[0]
        x = x.transpose(0, 1)
        for layer in stack.conformer_layers:
            x = layer(x, None)
        return x.transpose(0, 1)

    def forward(self, tokens):
        x = self.enc(self.text_input_layer(tokens), self.encoder)
        x = x + self.speaker_embedding.weight[0].view(1, 1, -1)
        va = self.variance_adaptor
        for predictor, embedding, bins in [
            (va.energy_predictor, va.energy_embedding, va.energy_bins),
            (va.pitch_predictor, va.pitch_embedding, va.pitch_bins),
        ]:
            v = predictor(x)
            bucket = (
                (v.unsqueeze(-1) > bins).long().sum(-1)
                if self.portable
                else torch.bucketize(v, bins)
            )
            x = x + embedding(bucket)
        durations = (
            torch.round(torch.exp(va.duration_predictor(x)) - 1)
            .clamp(min=0)
            .to(torch.long)[0]
        )
        maxframes = 1000
        if self.portable:
            ends = torch.cumsum(durations, 0)
            frames = torch.arange(
                torch.clamp(ends[-1], min=1, max=maxframes), device=x.device
            )
            ids = (
                (frames.unsqueeze(1) >= ends.unsqueeze(0))
                .long()
                .sum(1)
                .clamp(max=x.shape[1] - 1)
            )
            x = x.index_select(1, ids)
        else:
            x = torch.repeat_interleave(x, durations, dim=1)[:, :maxframes, :]
        x = self.mel_linear(self.enc(x, self.decoder))
        return (x + self.postnet(x)).transpose(1, 2).contiguous()

    def portable_attention(self):
        for stack in (self.encoder, self.decoder):
            for layer in stack.conformer_layers:
                layer.self_attn = ExportAttention(layer.self_attn)
        self.portable = True


def symbols(h):
    cfg = h["config"]["text"]["symbols"]
    p = cfg["punctuation"]
    vals = set(
        sum(p.values(), [])
        + cfg["silence"]
        + cfg["open-bible-shona_characters"]
        + cfg["open-bible-shona_phones"]
    )
    vals.update(
        "<EXCL> <EPS> <QINT> <QUOTE> <PERIOD> <COMMA> <COLON> <SEMICOL> <HYPHEN> <PAREN>".split()
    )
    ss = ["\x80", " "] + sorted(vals - set(["\x80", " "]), key=lambda s: (-len(s), s))
    assert len(ss) == 90
    return {s: i for i, s in enumerate(ss)}


def encode(text, syms):
    return torch.tensor(
        [[syms[c] for c in " ".join(text.split()) if c in syms]], dtype=torch.long
    )


def main():
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    bundle = torch.load(checkpoint, mmap=True, weights_only=True, map_location="cpu")
    if bundle.get("model_info") != {"name": "FastSpeech2", "version": "1.2"}:
        raise ValueError("Unsupported fastspeech model version")
    h = bundle["hyper_parameters"]
    w = {
        k: v
        for k, v in bundle["state_dict"].items()
        if not k.startswith(("loss.", "variance_adaptor.attention."))
    }
    model = Acoustic(w["text_input_layer.weight"].shape)
    missing, extra = model.load_state_dict(w, strict=False)
    print("missing/extra", missing, extra, flush=True)
    assert not missing and not extra
    model.eval()
    syms = symbols(h)
    texts = [
        "Mhoro!",
        "Zita rako ndiani?",
        "Musangano wacho wakachinjirwa kusvika musi weChishanu. Kero yacho haina kuchinja.",
    ]
    ins = [encode(t, syms) for t in texts]
    with torch.inference_mode():
        references = [model(x).numpy() for x in ins]
        model.portable_attention()
        for i, x in enumerate(ins):
            val = model(x).numpy()
            print(
                "Portable",
                texts[i],
                val.shape,
                np.max(np.abs(val - references[i])),
                np.mean(np.abs(val - references[i])),
                flush=True,
            )
            assert (
                val.shape == references[i].shape
                and np.max(np.abs(val - references[i])) < 0.01
            )
        torch.onnx.export(
            model,
            ins[1],
            "acoustic.onnx",
            opset_version=17,
            input_names=["tokens"],
            output_names=["mel"],
            dynamic_axes={"tokens": {1: "token_count"}, "mel": {2: "mel_frames"}},
            do_constant_folding=True,
        )
    del model, w
    gc.collect()
    import onnxruntime as ort

    opt = ort.SessionOptions()
    opt.intra_op_num_threads = 1
    opt.inter_op_num_threads = 1
    sess = ort.InferenceSession(
        "acoustic.onnx", sess_options=opt, providers=["CPUExecutionProvider"]
    )
    for i, x in enumerate(ins):
        y = sess.run(["mel"], {"tokens": x.numpy()})[0]
        print(
            "ONNX",
            texts[i],
            y.shape,
            "max err",
            np.max(np.abs(y - references[i])),
            "rmse",
            np.mean((y - references[i]) ** 2) ** 0.5,
            flush=True,
        )
        assert (
            y.shape == references[i].shape and np.max(np.abs(y - references[i])) < 0.02
        )
        # Export validation runs on three input lengths, including punctuation.
    json.dump(
        {
            "sample_rate": 22050,
            "tokens": {s: i for s, i in syms.items() if len(s) == 1 and s != "\x80"},
            "base_model": "multilingual-tts/EveryVoice-OpenBible-Shona",
            "revision": "76dd224de0f4f05034b6f4e561f21dd83dd6c102",
        },
        open("voice.json", "w"),
        ensure_ascii=False,
        indent=2,
    )


if __name__ == "__main__":
    main()
