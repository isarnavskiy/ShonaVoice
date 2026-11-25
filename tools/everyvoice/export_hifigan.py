"""Convert the EveryVoice OpenBible Shona vocoder generator to Android-compatible ONNX."""
import argparse
import ast
import hashlib
import json
import math
from enum import Enum
from pathlib import Path
from types import SimpleNamespace
import numpy as np
import onnx
import onnxruntime as ort
import torch
from torch import nn
from torch.nn import Conv1d, ConvTranspose1d
from torch.nn import functional as F
from torch.nn.utils.parametrizations import weight_norm
from torch.nn.utils.parametrize import remove_parametrizations

CHECKPOINT_SHA256 = '48e7055d3b51abf5b4b85eb6bfe1dbbed121c48eb2e82388a7dabd037b677a80'
SOURCE_SHA256 = 'cd1b64b96cde6f989ea17c1b1e58a6e4d00916ada5bcd52392ade912e839e932'
CHECKPOINT_REVISION = '76dd224de0f4f05034b6f4e561f21dd83dd6c102'
SOURCE_REVISION = 'dc2e1a459204ff1440c760f1c3a6874a0c8e490d'

def file_hash(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()

class HiFiGANResblock(Enum):
    one='1'
    two='2'

def upstream_generator(source):
    if file_hash(source) != SOURCE_SHA256:
        raise ValueError("Unexpected source SHA-256 for upstream model.py")
    # Import just the upstream inference definitions to avoid training-only packages.
    module=ast.parse(source.read_text())
    wanted={'get_padding','init_weights','ResBlock1','ResBlock2','Generator'}
    module.body=[node for node in module.body if getattr(node,'name','') in wanted]
    assert {node.name for node in module.body}==wanted
    ctx={'torch':torch,'nn':nn,'F':F,'math':math,'Conv1d':Conv1d,'ConvTranspose1d':ConvTranspose1d,
         'VocoderConfig':object,'weight_norm':weight_norm,
         'remove_parametrizations':remove_parametrizations,'HiFiGANResblock':HiFiGANResblock}
    exec(compile(module,str(source),'exec'),ctx)
    return ctx["Generator"]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checkpoint', type=Path, required=True, help='Shona vocoder.ckpt')
    parser.add_argument('--model-source', type=Path, required=True, help='Pinned HiFiGAN hfgl/model.py')
    parser.add_argument('--output', type=Path, required=True, help='Target vocoder.onnx')
    parser.add_argument('--report', type=Path, required=True, help='Target validation JSON')
    args = parser.parse_args()
    checkpoint = args.checkpoint
    out = args.output
    if file_hash(checkpoint) != CHECKPOINT_SHA256:
        parser.error('Checkpoint SHA-256 differs from the pinned Shona release')
    generator = upstream_generator(args.model_source)
    torch.set_num_threads(2)
    torch.manual_seed(26)
    data=torch.load(checkpoint,weights_only=True,mmap=True,map_location='cpu')
    h=data['hyper_parameters']['config']
    model=dict(h['model'])
    assert model['activation_function']=='everyvoice.utils.original_hifigan_leaky_relu'
    model['activation_function']=lambda x:F.leaky_relu(x,0.1)
    model['resblock']=HiFiGANResblock(model['resblock'])
    audio=h['preprocessing']['audio']
    assert audio['n_mels']==80 and audio['output_sampling_rate']==22050 and not model['istft_layer']
    config=SimpleNamespace(model=SimpleNamespace(**model),preprocessing=SimpleNamespace(audio=SimpleNamespace(**audio)))
    gen=generator(config).eval()
    gen.load_state_dict({k.removeprefix('generator.'):v for k,v in data['state_dict'].items() if k.startswith('generator.')},strict=True)
    gen.remove_weight_norm()
    x=torch.randn(1,80,26) * 1.5 - 3
    with torch.inference_mode():
        torch.onnx.export(gen,x,out,opset_version=17,do_constant_folding=True,
            input_names=['mel'],output_names=['audio'],
            dynamic_axes={'mel':{2:'frames'},'audio':{2:'samples'}},dynamo=False)
        onnx.checker.check_model(str(out))
        opt=ort.SessionOptions();opt.intra_op_num_threads=2;opt.inter_op_num_threads=1
        runtime=ort.InferenceSession(str(out),opt,providers=['CPUExecutionProvider'])
        tests=[]
        for frames in [5,17,40,121,227]:
            mel=torch.randn(1,80,frames)*1.5-3
            expected=gen(mel).numpy()
            actual=runtime.run(['audio'],{'mel':mel.numpy()})[0]
            assert actual.shape==expected.shape==(1,1,frames*256)
            diff=float(abs(actual-expected).max())
            err=float(np.sqrt(np.mean((actual-expected)**2)))
            assert np.isfinite(actual).all() and diff<4e-4 and err<3e-5,(diff,err)
            tests.append({'frames':frames,'samples':frames*256,'max_diff':diff,'rms_error':err})
    report={'source_revision':'76dd224de0f4f05034b6f4e561f21dd83dd6c102','source_checkpoint_sha256':'48e7055d3b51abf5b4b85eb6bfe1dbbed121c48eb2e82388a7dabd037b677a80','step':data['global_step'],'size':out.stat().st_size,'sha256':file_hash(out),'tests':tests,'sample_rate':22050,'input':'mel [1,80,frames] float32','output':'audio [1,1,frames*256] float32','opset':17,'onnxruntime':ort.__version__}
    args.report.write_text(json.dumps(report,indent=2))
    print(json.dumps(report),flush=True)
if __name__=='__main__': main()
