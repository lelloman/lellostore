# Android third-party notices

This document records dependencies that need special attention when distributing
the LelloStore Android application. It is an engineering inventory, not legal
advice or a substitute for the license material that must accompany a release.

## Self-ADB implementation

LelloStore uses `libadb-android` 3.1.1 for the ADB protocol, mDNS discovery, TLS
connection, and pairing integration. The project offers this library under
GPL-3.0-or-later or Apache-2.0; LelloStore selects Apache-2.0.

- Project and source: <https://github.com/MuntashirAkon/libadb-android>
- License declaration: <https://github.com/MuntashirAkon/libadb-android/blob/master/COPYING>

`libadb-android` depends on `spake2-android` 2.2.1 from
`MuntashirAkon/spake2-java` for Android Wireless Debugging pairing. Its published
metadata declares GNU LGPL-3.0.

- Project and corresponding source: <https://github.com/MuntashirAkon/spake2-java>
- LGPL-3.0 text and distribution requirements: <https://www.gnu.org/licenses/lgpl-3.0.html>
- GPL-3.0 text incorporated by LGPL-3.0: <https://www.gnu.org/licenses/gpl-3.0.html>

Before distributing an APK outside the private development environment, the
release package must provide the required notices and license texts and a durable
way to obtain the exact corresponding SPAKE2 source. The release terms must not
forbid reverse engineering for debugging modifications to that LGPL component.
If those requirements are unsuitable for a target distribution, replace the
pairing implementation before release; removing only the pairing UI is
insufficient while the LGPL binary remains packaged.

## Security decision

The upstream library states that it has not received a security audit. LelloStore
therefore limits its integration to fixed ADB services needed for identity
verification and streamed package installation. It does not expose arbitrary
shell commands through an activity, service, broadcast receiver, content
provider, or other IPC surface.

The ADB identity is generated locally, stored in the application's private
no-backup directory, and retained across ordinary upgrades. Clearing application
data or uninstalling LelloStore destroys that identity and requires pairing
again. Wireless Debugging uses Android's authenticated TLS endpoint; legacy port
5555 is attempted only over loopback, although enabling that daemon may expose it
on other network interfaces and is therefore documented as a trusted-network
option.
