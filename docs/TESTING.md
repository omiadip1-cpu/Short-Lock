# Shorts Lock test checklist

Run on each phone (Xiaomi and Vivo). Mark pass/fail.

## Mandatory acceptance tests
| # | Test | Expected |
|---|---|---|
| 1 | Set PIN, note recovery code | Code shown once |
| 2 | Activate, enable Accessibility | Grey bubble appears over YouTube |
| 3 | YouTube > Shorts > bubble > Time > START | Toast "Locked for ...", bubble green |
| 4 | Swipe up/down in the middle | Next/previous Short plays |
| 5 | Tap middle of video | Pauses/resumes |
| 6 | Tap Like, Dislike, Comments, Share, Remix, channel, Subscribe, Search, bottom tabs | Nothing happens |
| 7 | Volume buttons | Volume changes |
| 8 | Phone Home | Returns to Shorts within ~1-2 s |
| 9 | Back (gesture or button) | Returns to Shorts within ~1-3 s |
| 10 | Recent apps | Returns to Shorts |
| 11 | Pull down notification shade | Blocked by the top strip |
| 11b | Hold Power while locked | Power menu stays open (reboot / Safe Mode still possible) |
| 12 | Open Settings (any route) | Returns to Shorts |
| 13 | Tap bubble | PIN keypad only, no menu; bubble cannot be dragged |
| 14 | Wrong PIN x5 | Never unlocks; then "Wait 60 s" |
| 15 | Correct PIN | "Unlocked", bubble grey, phone normal |
| 16 | Timer: lock with 15 min, wait | Unlocks by itself at the end |
| 17 | Incoming call / alarm while locked | Can answer/dismiss; then back to Shorts |
| 18 | Screen off and on while locked | Lock still on; timer keeps counting |
| 19 | Force-stop Shorts Lock while locked (crash) | Service restarts and lock resumes |
| 20 | Reboot while locked | Phone starts unlocked |
| 21 | Recovery code | Resets the PIN |

## Platform limitations (expected, not bugs)
- Home, Back and Recents cannot be disabled without Device Owner; the child sees a brief flash before returning.
- Return may land on a different Short than before.
- Buttons not on screen at START (e.g. Subscribe/Shop on some Shorts) are not covered.
- A swipe that starts on a covered button is swallowed; swipe from the middle.
- Portrait only. Rotating moves the shields out of place.
- YouTube redesigns may move buttons; take a new START after updating YouTube.
- If Xiaomi/Vivo battery settings are not applied, the system may kill the service.
- The dialer is allowed during a lock (so calls work); a child could reach it after a call.
