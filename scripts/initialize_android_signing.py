"""Create a permanent key ONCE outside Git, back it up, optionally configure GitHub."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
from urllib.parse import urlparse


def run(command, **kwargs):
    result = subprocess.run(command, capture_output=True, **kwargs)
    if result.returncode:
        # Do not include subprocess output or input: it may contain credentials.
        raise RuntimeError(f"Command failed: {Path(command[0]).name} {command[1]}; credentials were retained locally.")
    return result.stdout


def private_directory(directory: Path, repository: Path):
    resolved = directory.resolve()
    if resolved == repository or repository in resolved.parents:
        raise ValueError("Signing key and backups must be OUTSIDE the repository.")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--backup-directory", type=Path, required=True)
    parser.add_argument("--keytool", default=shutil.which("keytool"))
    parser.add_argument("--github-repo", help="Optional OWNER/REPO; requires authenticated gh with admin access")
    parser.add_argument("--client-config", type=Path, help="Existing ignored jetmeal.local.properties for client-safe Actions variables")
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[1]
    if not args.keytool:
        raise ValueError("Install a JDK or supply --keytool.")
    if args.directory.resolve() == args.backup_directory.resolve():
        raise ValueError("Backup must be a separate directory.")
    for directory in (args.directory, args.backup_directory):
        private_directory(directory, repository)
    key = args.directory / "jetmeal-release.jks"
    credentials = args.directory / "signing-credentials.json"
    if key.exists() != credentials.exists():
        raise ValueError("Incomplete existing signing directory; recover it, never overwrite its key.")
    if not key.exists():
        values = {"ANDROID_KEYSTORE_PASSWORD": secrets.token_urlsafe(36),
                  "ANDROID_KEY_ALIAS": "jetmeal", "ANDROID_KEY_PASSWORD": secrets.token_urlsafe(36)}
        # Save recovery credentials BEFORE generation; never lose the only password copy.
        descriptor = os.open(credentials, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as output:
            json.dump(values, output)
        run([args.keytool, "-genkeypair", "-keystore", str(key), "-storetype", "JKS",
             "-alias", values["ANDROID_KEY_ALIAS"], "-keyalg", "RSA", "-keysize", "3072",
             "-validity", "10000", "-dname", "CN=JetMeal Release, O=JetMeal",
             "-storepass:env", "ANDROID_KEYSTORE_PASSWORD", "-keypass:env", "ANDROID_KEY_PASSWORD"],
            env={**os.environ, **values})
        key.chmod(0o600)
    else:
        values = json.loads(credentials.read_text())
    certificate = run([args.keytool, "-exportcert", "-keystore", str(key),
                       "-alias", values["ANDROID_KEY_ALIAS"], "-storepass:env", "ANDROID_KEYSTORE_PASSWORD"],
                      env={**os.environ, **values})
    fingerprint = hashlib.sha256(certificate).hexdigest()
    (args.directory / "certificate-sha256.txt").write_text(fingerprint + "\n")
    for name in (key.name, credentials.name, "certificate-sha256.txt"):
        source, target = args.directory / name, args.backup_directory / name
        if target.exists() and target.read_bytes() != source.read_bytes():
            raise ValueError("Backup contains a different key/credentials; refuse to overwrite.")
        shutil.copy2(source, target)
        target.chmod(0o600)
    if args.github_repo:
        def github(kind, name, value):
            run(["gh", kind, "set", name, "--repo", args.github_repo], input=value.encode())
        github("secret", "ANDROID_KEYSTORE_BASE64", base64.b64encode(key.read_bytes()).decode())
        for name, value in values.items():
            github("secret", name, value)
        github("variable", "ANDROID_SIGNING_CERT_SHA256", fingerprint)
        if args.client_config:
            config = dict(line.strip().split("=", 1) for line in args.client_config.read_text().splitlines()
                          if line.strip() and not line.lstrip().startswith(("#", "!")))
            url, client_key = config["supabase.url"], config["supabase.publishableKey"]
            parsed = urlparse(url)
            if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
                raise ValueError("Hosted HTTPS project URL required.")
            if not client_key.startswith("sb_publishable_"):
                payload = client_key.split(".")[1]
                role = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))["role"]
                if role != "anon":
                    raise ValueError("Only client-safe publishable/anon project keys are allowed.")
            github("variable", "SUPABASE_URL", url)
            github("variable", "SUPABASE_PUBLISHABLE_KEY", client_key)
        print("GitHub signing secrets and certificate variable configured.")
    print(f"Permanent key: {key}\nBackup: {args.backup_directory}\nCertificate SHA-256: {fingerprint}")
    print("Keep the full signing directory in an encrypted off-device backup/password manager.")


if __name__ == "__main__":
    main()
