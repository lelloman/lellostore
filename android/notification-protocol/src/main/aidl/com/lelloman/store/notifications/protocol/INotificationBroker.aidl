package com.lelloman.store.notifications.protocol;
import com.lelloman.store.notifications.protocol.INotificationCallback;
oneway interface INotificationBroker { void request(String json, INotificationCallback callback); }
