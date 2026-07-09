#!/usr/bin/env python3
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
LOGIN_ACTIVITY = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/LoginActivity.java"


def fail(message: str) -> None:
    raise SystemExit(f"login proxy button check failed: {message}")


def slice_between(text: str, start: str, end: str) -> str:
    start_index = text.find(start)
    if start_index < 0:
        fail(f"missing block start {start!r}")
    end_index = text.find(end, start_index)
    if end_index < 0:
        fail(f"missing block end {end!r}")
    return text[start_index:end_index]


def main() -> int:
    java = LOGIN_ACTIVITY.read_text(encoding="utf-8")

    if "private boolean shouldAlwaysShowProxyButton()" not in java:
        fail("LoginActivity must declare an explicit pre-login proxy button visibility rule")

    helper_match = re.search(
        r"private boolean shouldAlwaysShowProxyButton\(\) \{\n(?P<body>.*?)\n    \}",
        java,
        re.S,
    )
    if not helper_match:
        fail("pre-login proxy button visibility helper must be parseable")
    helper_body = helper_match.group("body")
    if "activityMode == MODE_LOGIN" not in helper_body:
        fail("normal login flow must always expose the proxy button")
    if "activityMode == MODE_CANCEL_ACCOUNT_DELETION" not in helper_body:
        fail("cancel-account-deletion login flow must always expose the proxy button")
    if "MODE_CHANGE_PHONE_NUMBER" in helper_body or "MODE_CHANGE_LOGIN_EMAIL" in helper_body:
        fail("logged-in change-phone/email flows must not be forced to show the login proxy button")

    update_body = slice_between(
        java,
        "private void updateProxyButton(boolean animated, boolean force)",
        "private boolean proxyButtonVisible",
    )
    if "if (shouldAlwaysShowProxyButton())" not in update_body:
        fail("updateProxyButton must check the pre-login always-visible rule first")
    if "proxyDrawable.setConnected(proxyEnabled, connected, animated);" not in update_body:
        fail("pre-login button must keep an empty shield when no proxy is configured")
    if "showProxyButton(true, animated);" not in update_body:
        fail("pre-login button must be shown immediately, not delayed until connecting")
    prelogin_branch = slice_between(
        update_body,
        "if (shouldAlwaysShowProxyButton()) {",
        "} else if",
    )
    if "showProxyButtonDelayed" in prelogin_branch:
        fail("pre-login button must not use the delayed show path")

    if "NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);" not in java:
        fail("LoginActivity must observe proxy settings changes while the login screen is open")
    if "NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);" not in java:
        fail("LoginActivity must remove the global proxy settings observer")
    if "id == NotificationCenter.proxySettingsChanged" not in java:
        fail("LoginActivity must refresh the proxy button when proxy settings change")
    if "updateProxyButton(false, true);" not in slice_between(java, "public void onResume()", "@Override\n    public void onConfigurationChanged"):
        fail("LoginActivity must force-refresh proxy button state when returning from proxy settings")

    print("login proxy button check passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
