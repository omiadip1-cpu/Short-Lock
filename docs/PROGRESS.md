# Shorts Lock progress
- Phase 1: project foundation (this commit). Builds in GitHub Actions; APK is the `shortslock-debug-apk` artifact.
- Phase 1 verified: CI build + lint green (run 45932766416, commit 8ebbf6a). APK has no INTERNET permission.
- Phase 2 verified: 8 unit tests pass in CI, build + lint green (run 45933257769, commit b2495d8). On-phone UI not yet tested.
- Phase 3 verified in CI only (run 45933569059, commit dce1f65): unit tests + build + lint green. Bubble/service not yet tested on a phone.
- Phase 4 verified in CI only (run 45933899499, commit ac689bd): detector unit tests, build, lint green. Detection against real YouTube NOT yet tested; threshold 0.8 and 3-miss debounce are guesses to tune from phone results.
