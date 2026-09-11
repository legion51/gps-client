package org.traccar.family;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class FamilySettingsActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        var prefs = FamilyRequest.prefs(this);
        String deviceId = getIntent().getStringExtra("deviceId");
        if (deviceId == null || deviceId.isEmpty()) { finish(); return; }
        if (!deviceId.equals(prefs.getString("deviceId", ""))) {
            prefs.edit().putBoolean("enabled", false).putBoolean("ring", false).putString("deviceId", deviceId).apply();
            stopService(new Intent(this, CaptureService.class));
        }
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32, 48, 32, 24);
        TextView title = new TextView(this); title.setText("Семейный доступ"); title.setTextSize(25); layout.addView(title);
        TextView details = new TextView(this);
        details.setText("Звук и снимок экрана передаются только после вашего подтверждения каждого запроса. "
                + "Во время передачи видно уведомление с кнопкой остановки. Звук — до 60 секунд.\n\n"
                + "Укажите HTTPS-адрес веб-сервера Traccar (тот же, что в Manager), без /api.\nУстройство: " + deviceId);
        layout.addView(details);
        EditText url = new EditText(this); url.setSingleLine(); url.setHint("https://tracker.example.org");
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(prefs.getString("url", "")); layout.addView(url);
        CheckBox enabled = new CheckBox(this); enabled.setText("Разрешаю получать семейные запросы");
        enabled.setChecked(prefs.getBoolean("enabled", false)); layout.addView(enabled);
        CheckBox ring = new CheckBox(this);
        ring.setText("Разрешаю удалённый громкий сигнал без дополнительного подтверждения (15 секунд)");
        ring.setChecked(prefs.getBoolean("ring", false)); layout.addView(ring);
        TextView note = new TextView(this);
        note.setText("Сигнал использует громкость будильника, в том числе в беззвучном режиме. "
                + "«Не беспокоить», подключённая гарнитура и ограничения производителя могут повлиять на звук.");
        layout.addView(note);
        Button save = new Button(this); save.setText("Сохранить"); layout.addView(save);
        save.setOnClickListener(view -> {
            try {
                String base = enabled.isChecked() ? FamilyRequest.normalizeUrl(url.getText().toString()) : prefs.getString("url", "");
                boolean changed = !base.equals(prefs.getString("url", ""));
                prefs.edit().putString("url", base).putString("deviceId", deviceId)
                        .putBoolean("enabled", enabled.isChecked()).putBoolean("ring", ring.isChecked()).apply();
                if (!enabled.isChecked() || changed) {
                    stopService(new Intent(this, CaptureService.class));
                    getSystemService(NotificationManager.class).cancel(FamilyNotifications.REQUEST);
                }
                if (enabled.isChecked() && !FamilyNotifications.available(this)) {
                    Toast.makeText(this, "Разрешите уведомления: без них семейные сеансы недоступны", Toast.LENGTH_LONG).show();
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
                    } else if (Build.VERSION.SDK_INT >= 26) {
                        startActivity(new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())
                                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, "family_requests"));
                    } else startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName())));
                } else { finish(); }
            } catch (IllegalArgumentException error) { url.setError("Введите корректный HTTPS-адрес без /api, пароля и параметров"); }
        });
        Button stop = new Button(this); stop.setText("Остановить текущий сеанс"); layout.addView(stop);
        stop.setOnClickListener(view -> stopService(new Intent(this, CaptureService.class)));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.addView(layout); setContentView(scroll);
    }
}
