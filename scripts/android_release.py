"""Deterministic CI version allocation and validation of built/uploaded APK metadata."""
import argparse
import hashlib
import json
from pathlib import Path
import re

MAX_CODE = 2_100_000_000
BASE_CODE = 1_000_000
APPLICATION_ID = "com.kxsxlxv.jetmeal"


def allocate(run_number: int, attempt: int, tags: list[str]) -> int:
    if run_number < 1 or not 1 <= attempt < 100:
        raise ValueError("Invalid workflow run number/attempt (attempt must be 1..99).")
    previous = [int(match.group(1)) for tag in tags
                if (match := re.fullmatch(r"(?:refs/tags/)?v1\.1\.(\d+)", tag.strip()))]
    code = max(BASE_CODE + run_number * 100 + attempt, max(previous, default=3) + 1)
    if not 3 < code <= MAX_CODE:
        raise ValueError("Android versionCode range exhausted; review version allocation.")
    return code


def validate_metadata(path: Path, code: int, name: str) -> Path:
    metadata = json.loads(path.read_text())
    assert metadata["applicationId"] == APPLICATION_ID, "Unexpected applicationId"
    assert metadata["variantName"] == "release", "Expected release variant"
    assert len(metadata["elements"]) == 1, "Expected one universal release APK"
    item = metadata["elements"][0]
    assert item["versionCode"] == code and item["versionName"] == name, "Incorrect APK version"
    apk = path.parent / item["outputFile"]
    assert apk.resolve().parent == path.parent.resolve(), "Unexpected APK output path"
    assert apk.is_file() and apk.stat().st_size > 0, "Release APK missing/empty"
    return apk


def validate_badging(text: str, code: int, name: str):
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text, re.MULTILINE)
    assert match and match.groups() == (APPLICATION_ID, str(code), name), "Packaged APK identity/version mismatch"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    version = commands.add_parser("version")
    version.add_argument("--run-number", type=int, required=True)
    version.add_argument("--attempt", type=int, required=True)
    version.add_argument("--tags", type=Path, required=True)
    version.add_argument("--output", type=Path, required=True)
    check = commands.add_parser("metadata")
    check.add_argument("--metadata", type=Path, required=True)
    check.add_argument("--code", type=int, required=True)
    check.add_argument("--name", required=True)
    badging = commands.add_parser("badging")
    badging.add_argument("--file", type=Path, required=True)
    badging.add_argument("--code", type=int, required=True)
    badging.add_argument("--name", required=True)
    upload = commands.add_parser("upload")
    upload.add_argument("--release", type=Path, required=True)
    upload.add_argument("--apk", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "version":
        code = allocate(args.run_number, args.attempt, args.tags.read_text().splitlines())
        with args.output.open("a") as output:
            output.write(f"code={code}\nname=1.1.{code}\ntag=v1.1.{code}\n")
    elif args.command == "metadata":
        print(validate_metadata(args.metadata, args.code, args.name))
    elif args.command == "badging":
        validate_badging(args.file.read_text(), args.code, args.name)
        print("Packaged APK identity and version verified.")
    else:
        release = json.loads(args.release.read_text())
        assert release["draft"] and not release["prerelease"], "Expected staged normal release"
        assets = [asset for asset in release["assets"] if asset["name"] == args.apk.name]
        assert len(assets) == 1 and assets[0]["state"] == "uploaded", "APK upload incomplete"
        assert assets[0]["size"] == args.apk.stat().st_size, "APK upload size mismatch"
        digest = assets[0].get("digest")
        if digest:
            assert digest == "sha256:" + hashlib.sha256(args.apk.read_bytes()).hexdigest(), "APK digest mismatch"
        print("Uploaded APK verified; release may be published.")


if __name__ == "__main__":
    main()
