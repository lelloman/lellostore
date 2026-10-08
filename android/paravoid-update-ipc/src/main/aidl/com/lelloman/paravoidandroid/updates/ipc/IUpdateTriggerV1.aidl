package com.lelloman.paravoidandroid.updates.ipc;
import com.lelloman.paravoidandroid.updates.ipc.IUpdateTriggerCallbackV1;
oneway interface IUpdateTriggerV1 {
    void notifyUpdatesChanged(IUpdateTriggerCallbackV1 callback);
}
