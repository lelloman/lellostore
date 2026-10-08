package com.lelloman.paravoidandroid.updates.ipc;

/** A hint only: no release data, credentials, or user preference overrides cross this IPC. */
public final class UpdateTriggerProtocol {
    private UpdateTriggerProtocol() {}
    public static final String ACTION = "com.lelloman.paravoidandroid.action.UPDATE_TRIGGER_V1";
    public static final int QUEUED = 1, COALESCED = 2, REJECTED = 3, UNAVAILABLE = 4;
}
