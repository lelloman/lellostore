# P2P (Pesce e pesce)

Use LelloStore on one Android device to manage another through a USB data cable.
The controller needs USB host support. Both devices must run Android 7 (API 24)
or newer. The receiver needs USB debugging enabled and must authorize the
controller; it does not need LelloStore, a store account, internet access, or root.

## Connect

1. Open **P2P** in the bottom navigation, or **P2P · USB tools**
   on the login screen.
2. Enable Developer options and USB debugging on the receiver. Unlock it so you
   can see its authorization prompt.
3. Connect a data-capable cable. The phone running LelloStore must take the USB
   host role. On devices that expose an OTG setting, enable it when needed.
4. Select the receiver and tap **Connect**. Grant USB access on the controller,
   then approve its debugging key on the receiver. Confirm that the displayed
   model and Android version belong to the intended receiver.

If nothing appears, check USB debugging, the cable's data support, the USB role,
and the controller's OTG setting. Charging alone does not establish an ADB link.
After unplugging, connect explicitly again. Operations run in a foreground service
with a **Stop** notification action and continue when you leave the screen.

## Actions

- **Install LelloStore** copies the APK currently running on the controller.
  **Open on receiver** launches it there. Accounts, settings, ADB keys,
  and automatic-update/recovery setup are not copied; complete setup there.
- **Install apps** opens the authenticated catalog. Select several apps and review
  their versions; installations run sequentially over USB. Selection respects the
  controller's stable/beta settings and account access. Only the controller needs
  connectivity to download APKs, which are size-checked and SHA-256 verified.
- **Enable ADB TCP/IP** asks the receiver to listen on port 5555. This exposes
  legacy network ADB, so use a trusted network and the confirmation dialog.
  **Test network ADB** authenticates to an address reported by the receiver.
  **Return to USB mode** disables that listener. These actions may restart ADB;
  if automatic reconnection fails, reconnect the cable and tap Connect. They do
  not configure Android's separate Wireless debugging feature. All installs use
  USB even after enabling network ADB.

USB tools work without signing in. Catalog access and downloads require the
controller's existing account. Signing in from the picker preserves the USB
session, and expiration of that account leaves offline tools available.

## Interrupted installations and limits

Results come from the receiver's package manager and target its current Android
user. Equal or newer installed versions are skipped. Package failures are shown
per app; signature conflicts do not trigger uninstall or data deletion. A lost
connection or account pauses the remaining batch for explicit resume. Resume
requires the same receiver, and changing its active Android user stops the batch.

After a lost commit response, the result is uncertain. Reconnect the same receiver
to reconcile its installed version and clean up any known unfinished session
before retrying. Stopping cannot undo an installation that already committed.
The current batch lives in memory; process termination does not replay it. A small
private journal preserves only the unfinished installation for reconciliation.

The initial implementation supports one receiver and single/universal APKs.
Copying a split installation is rejected. There is no arbitrary shell, forced
downgrade, uninstall, wireless installation, or account provisioning. Vendor package
restrictions and APK ABI compatibility can still cause installation failures.
Remote downloads have their own files and do not update the controller's installed
app records or invoke its local installation/recovery mechanisms.

## Verification and phone testing

The isolated `:remote-adb` module has protocol/authentication, framing, stream-close,
fragmented USB read, transfer-size, and zero-length-packet tests. App tests cover
verified downloads, receiver installation sessions, user/identity checks,
signature rejection, and uncertain-commit reconciliation. ViewModel and emulator
tests cover the tab, signed-out tools, catalog selection, confirmation, and entry
from the notification.

Verified on 2026-09-20: full Android lint, 278 unit tests in each debug/release
variant, 75 signed-release app unit tests, debug app/test APK builds, and 10
affected emulator tests (8 UI tests and 2 app-entry tests) passed. Emulator tests
ran on Android API 36.1. These checks do not exercise a physical USB connection.

Build and check from `android/`:

```sh
./gradlew lint test :app:assembleDebug
./gradlew :ui:assembleDebugAndroidTest :app:assembleDebugAndroidTest
```

Development verification used JDK 17; the installed JDK 21 encountered a native
compiler crash. Set `-Dorg.gradle.java.home=/path/to/jdk17` if needed.

**Physical USB compatibility is not yet verified.** The connected controller is
a OnePlus Nord 3 (CPH2493), reports Android 16, and advertises USB host support.
Those read-only checks do not establish cable operation. Test the available
USB-C to USB-C cable with a second Nord 3 first, then the OnePlus 6:

1. Record receiver OS version and actual USB roles; verify discovery, permission
   denial/retry, authorization, and repeated detach/reconnect.
2. With both phones offline, copy and open LelloStore. Confirm no controller
   account or keys were transferred. Repeat to verify the installed-version skip.
3. Enable, test, and disable port 5555 on a trusted network. Verify the receiver's
   setting after each ADB restart, including recovery from a cable disconnect.
4. Install a catalog batch, including a large APK, an already-installed version,
   and a rejected package. Confirm only the receiver changes.
5. Stop/unplug during transfer and commit, then reconnect and reconcile. Check
   rotation, navigation, screen lock, account expiry, and process termination.

Older API 24–27 USB behavior is covered with transport fakes but still requires
physical coverage. Publishing and a claim of hardware compatibility remain
separate from this implementation.
