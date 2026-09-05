package com.lelloman.store.recovery.protocol;

interface IRecoveryService {
    int protocolVersion();
    boolean recordUpdateAttempt(
        String attemptId,
        int currentVersion,
        int targetVersion,
        String packageName,
        String expectedSignerSha256,
        long startedAtMillis,
        long deadlineAtMillis
    );
    boolean acknowledgeHealth(String attemptId, int installedVersion);
    boolean cancelUnreplacedAttempt(String attemptId, String reason, int installedVersion);
    boolean backupStoreIdentity(in byte[] privateKey, in byte[] certificate);
    byte[] restoreStorePrivateKey();
    byte[] restoreStoreCertificate();
    String pendingAttemptId();
    int pendingTargetVersion();
}
