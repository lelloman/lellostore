package com.lelloman.store.notificationfixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import org.unifiedpush.android.connector.UnifiedPush;
import java.util.List;

public class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(24,24,24,24);
        EditText vapid = new EditText(this); vapid.setHint("Approved server VAPID public key");
        List<String> distributors = UnifiedPush.getDistributors(this);
        Spinner picker = new Spinner(this); picker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, distributors));
        TextView output = new TextView(this); output.setTextIsSelectable(true);
        Runnable refresh = () -> {
            android.content.SharedPreferences prefs = getSharedPreferences("fixture", 0);
            output.setText(prefs.getString("result", "Ready") + "\n" + prefs.getString("subscription", ""));
        };
        layout.addView(vapid); layout.addView(picker);
        addAction(layout, "Register", () -> {
            if (distributors.isEmpty()) { output.setText("Enable LelloStore shared push and sign in first, then reopen this screen"); return; }
            UnifiedPush.saveDistributor(this, distributors.get(picker.getSelectedItemPosition()));
            try { UnifiedPush.register(this, "fixture", "Interoperability fixture", vapid.getText().toString().trim()); }
            catch (RuntimeException e) { output.setText(e.getMessage()); }
        });
        addAction(layout, "Unregister", () -> UnifiedPush.unregister(this, "fixture"));
        addAction(layout, "Refresh result", refresh);
        layout.addView(output); ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll); refresh.run();
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
    }
    private void addAction(LinearLayout layout, String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setOnClickListener(v -> action.run()); layout.addView(button);
    }
}
