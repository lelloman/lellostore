# Remote ADB notices

`AndroidPubkey.java` is adapted from Muntashir Al-Islam's libadb-android 3.1.1:
https://github.com/MuntashirAkon/libadb-android/tree/3.1.1

It is used under the upstream Apache-2.0 option. Modifications relocate the
package and use Android Base64 and standard UTF-8 encoding without additional
dependencies. The original SPDX notice is preserved.

The ADB packet layout/constants in `AdbTransport.kt` are adapted from that
release's `AdbProtocol.java`, copyright 2013 Cameron Gutman, under BSD-3-Clause
and the Apache-2.0 option. The packet-aware transport and single-command
connection state machine are local implementations; upstream TCP/TLS connection
management is not copied or modified.

The applicable license texts accompany this module in `LICENSE-APACHE-2.0` and
`LICENSE-BSD-3-Clause`. Copyright for the BSD portions: Copyright (c) 2013 Cameron
Gutman. All rights reserved.
