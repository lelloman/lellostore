package com.lelloman.store.notifications.protocol;
import com.lelloman.store.notifications.protocol.INotificationCallback;
oneway interface INotificationReceiver { void deliver(String json, INotificationCallback callback); }
