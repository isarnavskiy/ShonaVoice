"""Rebuild the bundled ARM64 SentencePiece JNI library with Android SDK tools."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
NDK_VERSION = "27.2.12479018"
CMAKE_VERSION = "3.22.1"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, help="Android SDK path (or set ANDROID_HOME)")
    parser.add_argument("--source", type=Path, help="Local SentencePiece v0.2.1 source checkout, for offline builds")
    args = parser.parse_args()
    sdk_path = args.sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_path:
        parser.error("Set ANDROID_HOME or pass --sdk")
    sdk = Path(sdk_path).expanduser().resolve()
    ndk = sdk / "ndk" / NDK_VERSION
    binaries = sdk / "cmake" / CMAKE_VERSION / "bin"
    suffix = ".exe" if platform.system() == "Windows" else ""
    cmake, ninja = (binaries / (name + suffix) for name in ("cmake", "ninja"))
    toolchain = ndk / "build/cmake/android.toolchain.cmake"
    if not all(tool.is_file() for tool in (cmake, ninja, toolchain)):
        parser.error(f'Install Android SDK packages "ndk;{NDK_VERSION}" and "cmake;{CMAKE_VERSION}" in {sdk}')
    strip = next((ndk / "toolchains/llvm/prebuilt").glob(f"*/bin/llvm-strip{suffix}"), None)
    if strip is None:
        parser.error("llvm-strip missing from NDK")
    native = ROOT / "native/sentencepiece"
    with tempfile.TemporaryDirectory(prefix="nikita-sp-") as temp:
        options = [
            str(cmake), "-S", str(native), "-B", temp, "-G", "Ninja",
            f"-DCMAKE_MAKE_PROGRAM={ninja.as_posix()}", f"-DCMAKE_TOOLCHAIN_FILE={toolchain.as_posix()}",
            "-DCMAKE_BUILD_TYPE=Release", "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-26",
            "-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
        ]
        if args.source:
            options.append(f"-DFETCHCONTENT_SOURCE_DIR_SENTENCEPIECE={args.source.resolve().as_posix()}")
        subprocess.run(options, check=True)
        subprocess.run([str(cmake), "--build", temp, "--target", "nikita_sentencepiece", "--parallel", "4"], check=True)
        built = Path(temp) / "libnikita_sentencepiece.so"
        subprocess.run([str(strip), "--strip-unneeded", str(built)], check=True)
        destination = ROOT / "app/src/main/jniLibs/arm64-v8a" / built.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(built, destination)
        print(f"{destination}  sha256={hashlib.sha256(destination.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
