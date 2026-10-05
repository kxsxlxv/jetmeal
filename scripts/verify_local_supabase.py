"""Real local Email OTP, refresh, RLS and atomic Nutrition Tools smoke test.

Run after `supabase start` and `supabase db reset --local`.
No hosted access, admin credentials, fixed OTP or production fixtures are used.
"""
import argparse
import json
import re
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cli", default="supabase")
    args = parser.parse_args()
    status = json.loads(subprocess.check_output([args.cli, "status", "-o", "json"], stderr=subprocess.DEVNULL))
    base = status["API_URL"].rstrip("/")
    mail = status.get("MAILPIT_URL", status.get("INBUCKET_URL", "http://127.0.0.1:54324"))
    key = status.get("PUBLISHABLE_KEY", status.get("ANON_KEY"))
    assert base.startswith(("http://127.0.0.1:", "http://localhost:")), "Local stack only"

    def request(path, data=None, token=None, expected=200, method=None):
        req = urllib.request.Request(base + path, data=None if data is None else json.dumps(data).encode(),
            headers={"apikey": key, "Authorization": "Bearer " + (token or key), "Content-Type": "application/json"},
            method=method)
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                code, body = response.status, response.read()
        except urllib.error.HTTPError as error:
            code, body = error.code, error.read()
        assert code == expected, f"{path}: expected {expected}, got {code}: {body.decode()[:300]}"
        return json.loads(body) if body else None

    def session(label):
        email = f"jetmeal-{label}-{uuid.uuid4().hex[:12]}@example.test"
        request("/auth/v1/otp", {"email": email, "create_user": True})
        token = None
        for _ in range(30):
            with urllib.request.urlopen(mail + "/api/v1/messages", timeout=10) as response:
                messages = json.load(response)["messages"]
            for message in messages:
                if any(recipient["Address"] == email for recipient in message["To"]):
                    with urllib.request.urlopen(mail + "/api/v1/message/" + message["ID"], timeout=10) as response:
                        captured = json.load(response)
                    match = re.search(r"\b([0-9]{6})\b", captured.get("Text", "") + captured.get("HTML", ""))
                    if match:
                        token = match.group(1)
                        break
            if token:
                break
            time.sleep(0.2)
        assert token, "OTP email did not contain one-time code"
        verified = request("/auth/v1/verify", {"email": email, "token": token, "type": "email"})
        assert verified["access_token"] and verified["refresh_token"]
        return verified

    def rpc(name, values, session_token, expected=200):
        return request("/rest/v1/rpc/jetmeal_" + name, {"p_input": values}, session_token, expected)

    a, b = session("a"), session("b")
    a_token, b_token = a["access_token"], b["access_token"]
    profile = request("/rest/v1/profiles?select=id,timezone", token=a_token)
    assert len(profile) == 1 and profile[0]["id"] == a["user"]["id"]
    request("/rest/v1/profiles?id=eq." + a["user"]["id"], {"timezone": "Europe/Istanbul"}, a_token, 204, "PATCH")
    food = rpc("create_food", {"name": "Local OTP test curd", "kind": "packaged", "serving_amount": 300,
        "serving_unit": "g", "calories_kcal": 435, "protein_g": 50.4, "fat_g": 15, "carbs_g": 9}, a_token)
    variant = food["data"]["variant"]["id"]
    assert request("/rest/v1/food_variants?id=eq." + variant, token=b_token) == []
    rpc("log_food", {"food_variant_id": variant, "quantity": 125, "consumed_at": "2026-10-05T05:00:00Z"}, b_token, 400)
    entry = rpc("log_food", {"food_variant_id": variant, "quantity": 125, "consumed_at": "2026-10-05T05:00:00Z"}, a_token)["data"]
    assert float(entry["calories_kcal_snapshot"]) == 181.25 and entry["meal_type"] == "morning"
    assert len(request("/rest/v1/diary_entries?deleted_at=is.null&consumed_at=gte.2026-10-04T21:00:00Z&consumed_at=lt.2026-10-05T21:00:00Z", token=a_token)) == 1
    assert request("/rest/v1/diary_entries", token=b_token) == []
    rpc("update_log", {"entry_id": entry["id"], "quantity": 250}, b_token, 400)
    edited = rpc("update_log", {"entry_id": entry["id"], "quantity": 250}, a_token)["data"]
    assert float(edited["calories_kcal_snapshot"]) == 362.5
    rpc("delete_log", {"entry_id": entry["id"]}, a_token)
    assert request("/rest/v1/diary_entries?deleted_at=is.null", token=a_token) == []
    rpc("undo_last_action", {}, a_token)
    assert len(request("/rest/v1/diary_entries?deleted_at=is.null", token=a_token)) == 1
    proposed = {"daily_calories_kcal": 2000, "daily_protein_g": 170, "daily_fat_g": 70,
        "daily_carbs_g": 180, "adjustment_limit_ratio": 0.1}
    rpc("update_targets", proposed, a_token, 403)
    confirmation = rpc("prepare_targets", proposed, a_token)["data"]["confirmation"]
    rpc("update_targets", {**proposed, "confirmation": confirmation}, a_token)
    rpc("update_targets", {**proposed, "confirmation": confirmation}, a_token, 403)
    assert request("/rest/v1/nutrition_targets", token=b_token) == []
    assert request("/rest/v1/audit_events", token=b_token) == []
    request("/rest/v1/audit_events", {"owner_id": a["user"]["id"]}, a_token, 403)
    request("/rest/v1/diary_entries", token=None, expected=401)
    refreshed = request("/auth/v1/token?grant_type=refresh_token", {"refresh_token": a["refresh_token"]})
    assert refreshed["access_token"]
    request("/auth/v1/logout", {}, refreshed["access_token"], 204)
    request("/auth/v1/token?grant_type=refresh_token", {"refresh_token": refreshed["refresh_token"]}, expected=400)
    print("PASS: real OTP for 2 users, authenticated reads, RLS isolation, catalogue -> log -> day read, quantity scaling, soft delete/undo, confirmed targets, audit protection, anonymous rejection, refresh, sign-out refresh revocation")


if __name__ == "__main__":
    main()
