# Paravoid APK personalization

`apk_personalize.py` is copied unchanged from Paravoid delivery commit `0881e6c`
(`delivery/tools/apk_personalize.py`). It preserves developer APK signatures and
refuses unsupported signing layouts. Store integration supplies its independently
verified v1 grant checker through the callback; no verification bypass is used.

Do not silently update this vendored implementation. Re-run the real signed APK
and metadata conformance tests when upgrading it. Candidate signing-block ID
0x50564132 still requires upstream collision review before protocol freeze.
