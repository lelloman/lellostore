# Upstream protocol conformance fixtures

Copied byte-for-byte from `paravoid-android` commit
`d58457fb22986a728922f7adbf3e588c56fa1594`, directory
`paravoid-contract/src/test/resources/metadata-vectors`.

`UPSTREAM.md` records scope and assumptions. These are metadata vectors, not VPK
archives, usable credentials, private keys or a production wire-protocol freeze.
Do not regenerate them to accommodate verifier failures. Changes require explicit
agreement with the upstream vectors and updating both implementations.

Rust tests independently verify signatures and match every upstream expected
outcome. Replay admission and lifecycle state are intentionally outside this test.
