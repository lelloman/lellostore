package com.lelloman.store.notificationfixture;

import android.app.*;
import android.content.Context;
import org.json.JSONObject;
import org.unifiedpush.android.connector.*;
import org.unifiedpush.android.connector.data.*;

public class FixtureReceiver extends MessagingReceiver {
    private void result(Context context, String value) {
        context.getSharedPreferences("fixture", 0).edit().putString("result", value).apply();
    }
    @Override public void onNewEndpoint(Context context, PushEndpoint endpoint, String instance) {
        try {
            PublicKeySet keys = endpoint.getPubKeySet();
            if (keys == null) { result(context, "Missing encryption keys"); return; }
            JSONObject subscription = new JSONObject().put("endpoint", endpoint.getUrl()).put("keys",
                new JSONObject().put("p256dh", keys.getPubKey()).put("auth", keys.getAuth()));
            context.getSharedPreferences("fixture", 0).edit().putString("subscription", subscription.toString(2)).apply();
            result(context, "Registered " + instance);
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }
    @Override public void onRegistrationFailed(Context context, FailedReason reason, String instance) {
        result(context, "Registration failed: " + reason);
    }
    @Override public void onUnregistered(Context context, String instance) {
        context.getSharedPreferences("fixture", 0).edit().remove("subscription").apply();
        result(context, "Unregistered " + instance);
    }
    @Override public void onMessage(Context context, PushMessage message, String instance) {
        if (!message.getDecrypted()) { result(context, "Decryption failed"); return; }
        String text = new String(message.getContent(), java.nio.charset.StandardCharsets.UTF_8);
        result(context, "Decrypted: " + text);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel("fixture", "Fixture", NotificationManager.IMPORTANCE_DEFAULT));
            builder = new Notification.Builder(context, "fixture");
        } else { builder = new Notification.Builder(context); }
        try {
            manager.notify(1, builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("UnifiedPush fixture").setContentText(text).build());
        } catch (SecurityException ignored) { /* Receiving does not require notification permission. */ }
    }
}
