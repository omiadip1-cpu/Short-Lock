# Shorts Lock recovery and uninstall

You can never be permanently locked out. In order of convenience:

1. Tap the bubble and enter your PIN.
2. Wait for the timer you chose at START (1 to 4 h, set in the app). The lock ends by itself.
3. Forgot the PIN: open Shorts Lock > "Forgot PIN? Use recovery code". (Needs the lock off first, so use 2, 4 or 5.)
4. Reboot the phone. A reboot always starts unlocked.
5. Safe Mode: hold Power, then long-press "Power off" > OK. Third-party apps (including Shorts Lock) do not run in Safe Mode. Reboot normally to leave Safe Mode; the phone then starts unlocked and you can open Shorts Lock (e.g. to use the recovery code).
6. From a computer: `adb uninstall com.shortslock.app`. Only works if USB debugging was turned on and this computer was authorised **before** the phone was locked; it cannot be enabled while locked.

## Uninstall normally
Unlock first, then Settings > Apps > Shorts Lock > Uninstall. (While locked, Settings is blocked by design.)

## Data
Everything stays on the phone. Uninstalling deletes the PIN, recovery code and the Keystore key.
