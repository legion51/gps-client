package org.traccar.family;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodChannel;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FamilyPlugin implements FlutterPlugin {
    private MethodChannel channel;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    @Override
    public void onAttachedToEngine(FlutterPluginBinding binding) {
        Context context = binding.getApplicationContext();
        channel = new MethodChannel(binding.getBinaryMessenger(), "org.traccar/family");
        channel.setMethodCallHandler((call, result) -> {
            if (call.method.equals("configure")) {
                String deviceId = call.argument("deviceId");
                context.startActivity(new Intent(context, FamilySettingsActivity.class)
                        .putExtra("deviceId", deviceId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                result.success(null);
            } else if (call.method.equals("receive")) {
                Map<String, Object> data = call.arguments();
                WORKER.execute(() -> {
                    try {
                        FamilyRequest request = FamilyRequest.fromPush(context, data);
                        if (request != null) {
                            request.validate(context, true);
                            if (!request.id.equals(FamilyRequest.prefs(context).getString("lastRequest", ""))) {
                                FamilyRequest.prefs(context).edit().putString("lastRequest", request.id).apply();
                                if (request.kind.equals("ring") && FamilyRequest.prefs(context).getBoolean("ring", false)) {
                                    Intent intent = request.intent(context, CaptureService.class);
                                    try {
                                        if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
                                        else context.startService(intent);
                                    } catch (RuntimeException restricted) {
                                        // A downgraded FCM message may not be allowed to start a foreground service.
                                        FamilyNotifications.request(context, request);
                                    }
                                } else {
                                    FamilyNotifications.request(context, request);
                                }
                            }
                        }
                        new Handler(Looper.getMainLooper()).post(() -> result.success(null));
                    } catch (Exception error) {
                        new Handler(Looper.getMainLooper()).post(() ->
                                result.error("family_request", "Family request unavailable or permission missing", null));
                    }
                });
            } else {
                result.notImplemented();
            }
        });
    }

    @Override
    public void onDetachedFromEngine(FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        channel = null;
    }
}
