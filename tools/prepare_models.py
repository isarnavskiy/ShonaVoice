"""Prepare verified offline model assets for the Shona Voice Android build."""
import argparse
import hashlib
import json
import os
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RELEASE_SIZE = 1_715_381_351
RELEASE_SHA256 = "d61d415821620e9fba204cb668bd44e8d30390911a58040c3962b9bb17e64bdb"
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
    if size != RELEASE_SIZE:
        raise ValueError("Unexpected APK size. Use the signed ShonaVoice-Offline.apk from v1.0.2.")
    print("Checking release APK SHA-256...", flush=True)
    with apk_path.open("rb") as apk_file:
        if stream_hash(apk_file)[1] != RELEASE_SHA256:
            raise ValueError("APK SHA-256 mismatch. Use the original signed v1.0.2 release.")
        apk_file.seek(0)
        with zipfile.ZipFile(apk_file) as archive:
            index = (ROOT / "tools/release-pack.json").read_bytes()
            if archive.read(PREFIX + "pack-v1.json") != index:
                raise ValueError("The model index differs from the checked-in release index.")
            output.mkdir(parents=True, exist_ok=True)
            records = json.loads(index)["files"]
            for item in records:
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
            return len(records)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True, help="Path to a signed v1.0.2 release APK")
    args = parser.parse_args()
    try:
        count = unpack(args.apk, ROOT / "model-assets/embedded_models")
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Error: {error}\n")
    print(f"All {count} model files are ready.")


if __name__ == "__main__":
    main()
