package org.traccar.family;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.RingtoneManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class CaptureService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile boolean finished;
    private FamilyRequest request;
    private Thread worker;
    private volatile MediaProjection projection;
    private volatile MediaPlayer player;
    private AudioManager audioManager;
    private int oldVolume = -1;
    private int setVolume = -1;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        FamilyRequest next = FamilyRequest.fromIntent(intent);
        if (running) {
            if (!next.id.equals(request.id)) next.finish(this, "failed");
            return START_NOT_STICKY;
        }
        request = next;
        boolean consented = intent.getBooleanExtra("consented", false);
        if (!FamilyRequest.prefs(this).getBoolean("enabled", false)
                || !FamilyNotifications.available(this)
                || (!consented && !("ring".equals(request.kind) && FamilyRequest.prefs(this).getBoolean("ring", false)))) {
            request.finish(this, "failed"); stopSelf(); return START_NOT_STICKY;
        }
        try {
            int type = "audio".equals(request.kind) ? (Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0)
                    : "screenshot".equals(request.kind) ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (Build.VERSION.SDK_INT >= 29) startForeground(FamilyNotifications.ACTIVE, FamilyNotifications.active(this, request.kind), type);
            else startForeground(FamilyNotifications.ACTIVE, FamilyNotifications.active(this, request.kind));
        } catch (RuntimeException error) {
            request.finish(this, "failed"); stopSelf(); return START_NOT_STICKY;
        }
        running = true; finished = false;
        main.postDelayed(this::stopSelf, 70000);
        worker = new Thread(() -> {
            String result = "completed";
            try {
                request.validate(this, true);
                if (!running) return;
                request.state(this, "active");
                if (!running) return;
                switch (request.kind) {
                    case "audio": audio(); break;
                    case "screenshot": screenshot(intent); break;
                    case "ring": ring(); break;
                    default: throw new IOException("Invalid kind");
                }
                if (!running) result = "stopped";
            } catch (Exception error) { result = running ? "failed" : "stopped"; }
            finally {
                if (!running) result = "stopped";
                finished = true;
                request.finish(this, result);
                main.post(this::stopSelf);
            }
        }, "family-capture");
        worker.start();
        return START_NOT_STICKY;
    }

    private void audio() throws Exception {
        int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException("Microphone format unavailable");
        ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(8);
        AtomicReference<Exception> failure = new AtomicReference<>();
        long until = SystemClock.elapsedRealtime() + 60000;
        Thread producer = new Thread(() -> {
            AudioRecord recorder = null;
            try {
                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum * 2, 64000));
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
                recorder.startRecording();
                while (running && !Thread.currentThread().isInterrupted() && SystemClock.elapsedRealtime() < until) {
                    byte[] block = new byte[32000]; int offset = 0;
                    while (running && offset < block.length && SystemClock.elapsedRealtime() < until) {
                        int count = recorder.read(block, offset, block.length - offset, AudioRecord.READ_NON_BLOCKING);
                        if (count < 0) throw new IOException("Microphone read failed");
                        if (count == 0) Thread.sleep(10); else offset += count;
                    }
                    if (offset > 0 && running) {
                        byte[] data = java.util.Arrays.copyOf(block, offset - offset % 2);
                        if (!queue.offer(data)) { queue.poll(); queue.offer(data); }
                    }
                }
            } catch (Exception error) { failure.set(error); }
            finally { if (recorder != null) { try { recorder.stop(); } catch (RuntimeException ignored) { } recorder.release(); } }
        }, "family-microphone");
        producer.start();
        try {
            while (running && (producer.isAlive() || !queue.isEmpty())) {
                byte[] bytes = queue.poll(1, TimeUnit.SECONDS);
                if (bytes != null && running) request.send(this, "data", bytes);
                if (failure.get() != null) throw failure.get();
            }
        } finally {
            producer.interrupt();
            producer.join(1500);
        }
    }

    private void ring() throws Exception {
        audioManager = getSystemService(AudioManager.class);
        oldVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
        setVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, setVolume, 0);
            MediaPlayer media = new MediaPlayer(); player = media;
            media.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            android.net.Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (sound == null) throw new IOException("No alarm sound configured");
            media.setDataSource(this, sound); media.setLooping(true); media.prepare();
            if (!running) return;
            media.start();
            main.postDelayed(() -> {
                try { media.pause(); } catch (RuntimeException ignored) { }
                restoreVolume();
            }, 15000);
            long until = SystemClock.elapsedRealtime() + 15000;
            while (running && SystemClock.elapsedRealtime() < until) {
                request.validate(this, false);
                Thread.sleep(500);
            }
        } finally {
            MediaPlayer media = player; player = null;
            if (media != null) { try { media.stop(); } catch (RuntimeException ignored) { } media.release(); }
            restoreVolume();
        }
    }

    private void restoreVolume() {
        try {
            if (audioManager != null && oldVolume >= 0 && audioManager.getStreamVolume(AudioManager.STREAM_ALARM) == setVolume) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0);
            }
        } catch (RuntimeException ignored) { }
        oldVolume = -1;
    }

    @SuppressWarnings("deprecation")
    private void screenshot(Intent intent) throws Exception {
        Intent permission = intent.getParcelableExtra("projection");
        if (permission == null) throw new IOException("Screen consent missing");
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<byte[]> jpeg = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        DisplayMetrics metrics = new DisplayMetrics();
        getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
        float scale = Math.min(1f, 1280f / Math.max(metrics.widthPixels, metrics.heightPixels));
        int width = Math.max(1, Math.round(metrics.widthPixels * scale));
        int height = Math.max(1, Math.round(metrics.heightPixels * scale));
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        VirtualDisplay display = null;
        try {
            projection = getSystemService(MediaProjectionManager.class)
                    .getMediaProjection(intent.getIntExtra("projectionResult", 0), permission);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { ready.countDown(); }
            }, main);
            long after = SystemClock.elapsedRealtime() + 700;
            reader.setOnImageAvailableListener(source -> {
                try (Image image = source.acquireLatestImage()) {
                    if (image == null || !running || ready.getCount() == 0 || SystemClock.elapsedRealtime() < after) return;
                    Image.Plane plane = image.getPlanes()[0];
                    int paddedWidth = plane.getRowStride() / plane.getPixelStride();
                    Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
                    Bitmap cropped = null;
                    try {
                        java.nio.ByteBuffer pixels = plane.getBuffer();
                        int required = padded.getByteCount();
                        if (pixels.remaining() < required) {
                            java.nio.ByteBuffer extended = java.nio.ByteBuffer.allocate(required);
                            extended.put(pixels); extended.rewind(); pixels = extended;
                        }
                        padded.copyPixelsFromBuffer(pixels);
                        cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        cropped.compress(Bitmap.CompressFormat.JPEG, 80, output);
                        jpeg.set(output.toByteArray());
                    } finally {
                        if (cropped != null && cropped != padded) cropped.recycle();
                        padded.recycle();
                    }
                    ready.countDown();
                } catch (Exception error) { failure.set(error); ready.countDown(); }
            }, main);
            // Let the consent activity close before creating the display; a static screen may emit just one frame.
            Thread.sleep(800);
            if (!running) return;
            display = projection.createVirtualDisplay("Family screenshot", width, height, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, main);
            if (!ready.await(10, TimeUnit.SECONDS) || failure.get() != null || jpeg.get() == null) {
                throw new IOException("Screenshot unavailable", failure.get());
            }
            if (running) request.send(this, "data", jpeg.get());
        } finally {
            if (display != null) display.release();
            reader.setOnImageAvailableListener(null, null); reader.close();
            MediaProjection current = projection; projection = null;
            if (current != null) current.stop();
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (worker != null) worker.interrupt();
        MediaProjection current = projection;
        if (current != null) { try { current.stop(); } catch (RuntimeException ignored) { } }
        MediaPlayer media = player;
        if (media != null) { try { media.pause(); } catch (RuntimeException ignored) { } }
        restoreVolume();
        if (request != null && !finished) request.finish(this, "stopped");
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
