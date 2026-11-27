# Shorts Lock progress

| Phase | Status |
|---|---|
| 1 Foundation | CI green |
| 2 PIN + recovery code | CI green, 8 unit tests |
| 3 Accessibility service + bubble | CI green |
| 4 Shorts snapshot + detection | CI green |
| 5 Touch shields | CI green |
| 6 Navigation guard + return to Shorts | CI green |
| 7 PIN-protected exit, timer, shade strip | CI green |
| 8 Crash restore, reboot clears, phone setup checklist | CI green |
| 9 Test checklist + recovery docs | docs/TESTING.md, docs/RECOVERY.md |
| 10 Stable signing + release APK | Code ready; needs signing key in repo secrets |

Nothing has been tested on a real phone yet. Use docs/TESTING.md.

## Architecture change from spec
- Foreground notification added back in 1.2.1: Xiaomi killed the service after ~5 min without it.
