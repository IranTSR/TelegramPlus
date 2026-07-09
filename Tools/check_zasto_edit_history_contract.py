#!/usr/bin/env python3
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
STORE = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ZaStoEditHistoryStore.java"
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"


def main() -> int:
    store = STORE.read_text(encoding="utf-8", errors="replace")
    chat = CHAT.read_text(encoding="utf-8", errors="replace")
    failures: list[str] = []

    if "new org.telegram.messenger.ZaStoEditHistoryStore.Version(" not in chat:
        failures.append("ChatActivity must append the current message as a ZaSto edit-history Version")

    if not re.search(r"public\s+Version\s*\(\s*int\s+date\s*,\s*String\s+text\s*\)", store):
        failures.append("ZaStoEditHistoryStore.Version constructor must be public for ChatActivity")

    if failures:
        print("ZaSto edit-history contract failed:", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1

    print("ZaSto edit-history contract passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
