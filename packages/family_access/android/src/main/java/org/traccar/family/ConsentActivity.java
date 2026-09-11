package org.traccar.family;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class ConsentActivity extends Activity {
    private FamilyRequest request;
    private Button allow;
    private boolean deciding;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        request = FamilyRequest.fromIntent(getIntent());
        deciding = false;
        getSystemService(NotificationManager.class).cancel(FamilyNotifications.REQUEST);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32, 64, 32, 32);
        TextView title = new TextView(this); title.setTextSize(24);
        title.setText("audio".equals(request.kind) ? "Разрешить звук вокруг?"
                : "screenshot".equals(request.kind) ? "Разрешить снимок экрана?" : "Включить громкий сигнал?");
        layout.addView(title);
        TextView description = new TextView(this);
        description.setText("Запрос от пользователя Traccar с доступом к этому устройству. Сервер: " + request.base
                + "\n\n" + ("audio".equals(request.kind) ? "Звук микрофона будет передаваться до 60 секунд."
                : "screenshot".equals(request.kind) ? "Будет передан один снимок. Убедитесь, что на экране нет лишних личных данных."
                : "Сигнал будет звучать 15 секунд на громкости будильника.")
                + "\n\nВы можете отклонить запрос или остановить сеанс через уведомление.");
        layout.addView(description);
        allow = new Button(this); allow.setText("Разрешить этот запрос"); allow.setEnabled(!deciding); layout.addView(allow);
        allow.setOnClickListener(view -> {
            if (deciding) return;
            deciding = true; allow.setEnabled(false);
            new Thread(() -> {
                try {
                    request.validate(this, true);
                    runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) permissions(); });
                } catch (Exception error) {
                    runOnUiThread(() -> { Toast.makeText(this, "Запрос истёк или недоступен", Toast.LENGTH_LONG).show(); finish(); });
                }
            }, "family-consent").start();
        });
        Button decline = new Button(this); decline.setText("Отклонить"); layout.addView(decline);
        decline.setOnClickListener(view -> { request.finish(this, "declined"); finish(); });
        setContentView(layout);
    }

    @Override
    public void onSaveInstanceState(Bundle state) { state.putBoolean("deciding", deciding); super.onSaveInstanceState(state); }

    private void permissions() {
        if (!FamilyNotifications.available(this)) {
            request.finish(this, "failed");
            Toast.makeText(this, "Сначала разрешите уведомления Traccar Client", Toast.LENGTH_LONG).show(); finish(); return;
        }
        if ("audio".equals(request.kind) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
        } else if ("screenshot".equals(request.kind)) {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            Intent capture = Build.VERSION.SDK_INT >= 34
                    ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    : manager.createScreenCaptureIntent();
            startActivityForResult(capture, 3);
        } else { start(null, 0); }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 2) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start(null, 0);
            else { request.finish(this, "declined"); finish(); }
        }
    }

    @Override
    public void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == 3) {
            if (result == RESULT_OK && data != null) start(data, result);
            else { request.finish(this, "declined"); finish(); }
        }
    }

    private void start(Intent projection, int result) {
        try {
            Intent intent = request.intent(this, CaptureService.class).putExtra("consented", true);
            if (projection != null) intent.putExtra("projection", projection).putExtra("projectionResult", result);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        } catch (RuntimeException error) {
            request.finish(this, "failed");
            Toast.makeText(this, "Не удалось запустить сеанс", Toast.LENGTH_LONG).show();
        }
        finish();
    }

    @Override
    public void onBackPressed() { request.finish(this, "declined"); super.onBackPressed(); }
}
