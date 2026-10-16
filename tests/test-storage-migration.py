"""Run the actual v1->v2 SQL against a host SQLite fixture, without deleting data."""
import json
import re
import sqlite3
from pathlib import Path

source = (Path(__file__).resolve().parents[1] / "app/src/main/java/com/qingji/app/LedgerStore.java").read_text()


def statements(part):
    return [json.loads('"' + value + '"') for value in re.findall(r'db\.execSQL\("((?:[^"\\]|\\.)*)"\)', part)]


database = sqlite3.connect(":memory:")
create = source.split("@Override public void onCreate", 1)[1].split("@Override public void onUpgrade", 1)[0]
for sql in statements(create):
    # Version 1 used exactly these tables without the two image metadata columns.
    sql = sql.replace(",image_ref TEXT NOT NULL DEFAULT '',image_note TEXT NOT NULL DEFAULT ''", "")
    database.execute(sql)

defaults = dict(cents=599900, currency="CNY", kind="EXPENSE", merchant="测试商户", category="其他", rail="微信",
                occurred_at=1700000000000, exact_time=1, order_hash="order-hash", order_scope="merchant",
                account_tail="1234", target_tail="", confidence=.95, status="CONFIRMED", reason="旧原因", excerpt="旧摘要")
fixtures = [
    (1, "CONFIRMED", 0, ["ACCESSIBILITY_OCR"]),
    (2, "CONFIRMED", 1, ["ACCESSIBILITY_OCR"]),
    (3, "CONFIRMED", 0, ["ACCESSIBILITY_OCR", "NOTIFICATION"]),
    (4, "CONFIRMED", 0, ["SMS"]),
    (5, "IGNORED", 1, ["ACCESSIBILITY_OCR"]),
    (6, "REVIEW", 0, ["ACCESSIBILITY_OCR"]),
    (7, "CONFIRMED", 0, ["SCREENSHOT"]),
    (8, "CONFIRMED", 0, ["SCREEN_CAPTURE"]),
]
for row_id, status, edited, sources in fixtures:
    fields = dict(defaults, id=row_id, status=status, user_edited=edited)
    database.execute("INSERT INTO transactions (" + ",".join(fields) + ") VALUES (" + ",".join("?" for _ in fields) + ")", list(fields.values()))
    for index, capture_source in enumerate(sources):
        database.execute("INSERT INTO evidence VALUES (?,?,?,?,?,?,?)",
                         (f"fingerprint-{row_id}-{index}", row_id, capture_source, "com.tencent.mm", "event-hash", 1700000000000, "已有证据"))

before = database.execute("SELECT id,cents,currency,merchant,occurred_at,user_edited FROM transactions ORDER BY id").fetchall()
migration = source.split("@Override public void onUpgrade", 1)[1].split("private ContentValues values", 1)[0]
for sql in statements(migration):
    database.execute(sql)
after = database.execute("SELECT id,cents,currency,merchant,occurred_at,user_edited FROM transactions ORDER BY id").fetchall()
assert before == after, "Migration changed ledger fields or lost rows"
states = dict(database.execute("SELECT id,status FROM transactions"))
assert states == {1: "REVIEW", 2: "CONFIRMED", 3: "CONFIRMED", 4: "CONFIRMED", 5: "IGNORED", 6: "REVIEW", 7: "REVIEW", 8: "REVIEW"}, states
images = database.execute("SELECT image_ref,image_note FROM evidence").fetchall()
assert len(images) == 9 and all(ref == "" and "未保存截图" in note for ref, note in images)
database.execute("UPDATE evidence SET image_ref=? WHERE fingerprint=?", ("a" * 64, "fingerprint-1-0"))
assert database.execute("SELECT image_ref FROM evidence WHERE transaction_id=1").fetchone()[0] == "a" * 64
print("SQLite migration checks passed: ledger preservation, user decisions, mixed sources, OCR review, historical evidence and image attachment")
