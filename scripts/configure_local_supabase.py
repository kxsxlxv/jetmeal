"""Write ignored Android configuration using only the local publishable key."""
import argparse
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--cli", default="supabase")
args = parser.parse_args()
status = json.loads(subprocess.check_output([args.cli, "status", "-o", "json"], stderr=subprocess.DEVNULL))
key = status.get("PUBLISHABLE_KEY", status.get("ANON_KEY"))
assert key and status["API_URL"].startswith(("http://127.0.0.1:", "http://localhost:")), "Local stack only"
Path("jetmeal.local.properties").write_text("supabase.url=http://127.0.0.1:54321\nsupabase.publishableKey=" + key + "\n")
print("Client-safe ignored Android configuration written; use adb reverse tcp:54321 tcp:54321")
