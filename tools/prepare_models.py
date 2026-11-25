"""Prepare verified offline model assets for the Shona Voice Android build."""
import argparse
import hashlib
import json
import os
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RELEASE_SIZES = {
    "fedb71301685fbb2e39831a224ff8a24e6b08df83fc512e33b894d0a56843efb": 1_596_727_716,
    "dfffa2939cfee833dc60abdb56302bbd1546e7360ddccab42b9c93d9b13a4b51": 1_596_731_823,
}
PREFIX = "assets/embedded_models/"
BUF_SIZE = 1024 * 1024


def stream_hash(stream, target=None):
    digest = hashlib.sha256()
    size = 0
    while chunk := stream.read(BUF_SIZE):
        digest.update(chunk)
        size += len(chunk)
        if target is not None:
            target.write(chunk)
    return size, digest.hexdigest()


def unpack(apk_path, output):
    size = apk_path.stat().st_size
    if size not in RELEASE_SIZES.values():
        raise ValueError("Unexpected APK size. Use the signed ShonaVoice-Offline.apk from v1.0.0 or v1.0.1.")
    print("Checking release APK SHA-256...", flush=True)
    with apk_path.open("rb") as apk_file:
        if RELEASE_SIZES.get(stream_hash(apk_file)[1]) != size:
            raise ValueError("APK SHA-256 mismatch. Use the original signed v1.0.0 or v1.0.1 release.")
        apk_file.seek(0)
        with zipfile.ZipFile(apk_file) as archive:
            index = (ROOT / "tools/release-pack.json").read_bytes()
            if archive.read(PREFIX + "pack-v1.json") != index:
                raise ValueError("The model index differs from the checked-in release index.")
            output.mkdir(parents=True, exist_ok=True)
            for item in json.loads(index)["files"]:
                name = item["path"]
                info = archive.getinfo(PREFIX + name)
                expected = (item["bytes"], item["sha256"])
                if info.file_size != expected[0]:
                    raise ValueError(f"Incorrect size in APK for {name}")
                destination = output / name
                if destination.is_file() and destination.stat().st_size == expected[0]:
                    with destination.open("rb") as existing:
                        if stream_hash(existing) == expected:
                            print(f"Verified {name}", flush=True)
                            continue
                destination.parent.mkdir(parents=True, exist_ok=True)
                with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as temporary:
                    tmp = Path(temporary.name)
                    try:
                        with archive.open(info) as model:
                            if stream_hash(model, temporary) != expected:
                                raise ValueError(f"Model SHA-256 mismatch for {name}")
                        temporary.close()
                        os.replace(tmp, destination)
                    finally:
                        temporary.close()
                        tmp.unlink(missing_ok=True)
                print(f"Prepared {name}", flush=True)
            # Publish the index only after the entire pack validates. Gradle checks it again.
            with tempfile.NamedTemporaryFile(dir=output, delete=False) as temporary:
                tmp = Path(temporary.name)
                try:
                    temporary.write(index)
                    temporary.close()
                    os.replace(tmp, output / "pack-v1.json")
                finally:
                    temporary.close()
                    tmp.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True, help="Path to a signed v1.0.0 or v1.0.1 release APK")
    args = parser.parse_args()
    try:
        unpack(args.apk, ROOT / "model-assets/embedded_models")
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Error: {error}\n")
    print("All 13 model files are ready.")


if __name__ == "__main__":
    main()
