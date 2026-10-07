package com.cashreadernovan;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.Image;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends ComponentActivity {

    private PreviewView previewView;
    private TextView resultText;
    private Button torchButton;

    private Camera camera;
    private boolean torchOn = false;

    private ExecutorService analysisExecutor;
    private TextRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    // Penstabil hasil: nominal harus terbaca sama di 2 frame berturut-turut
    private int lastCandidate = -1;
    private int stableCount = 0;
    private int lastSpoken = -1;
    private long lastSpokenAt = 0;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    resultText.setText("Izin kamera diperlukan untuk membaca uang.");
                    speak("Izin kamera diperlukan untuk membaca uang.");
                }
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();

        analysisExecutor = Executors.newSingleThreadExecutor();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("id", "ID"));
                ttsReady = true;
                speak("Arahkan kamera ke uang");
            }
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        root.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        resultText = new TextView(this);
        resultText.setText("Arahkan kamera ke uang");
        resultText.setTextSize(28);
        resultText.setTextColor(Color.WHITE);
        resultText.setBackgroundColor(0x99000000);
        resultText.setGravity(Gravity.CENTER);
        resultText.setPadding(32, 32, 32, 32);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(resultText, rlp);

        torchButton = new Button(this);
        torchButton.setText("Senter: Mati");
        torchButton.setContentDescription("Tombol senter");
        torchButton.setOnClickListener(v -> toggleTorch());
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        tlp.setMargins(24, 48, 24, 24);
        root.addView(torchButton, tlp);

        // Tap untuk fokus
        previewView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP && camera != null) {
                MeteringPoint point = previewView.getMeteringPointFactory()
                        .createPoint(event.getX(), event.getY());
                camera.getCameraControl().startFocusAndMetering(
                        new FocusMeteringAction.Builder(point).build());
                v.performClick();
            }
            return true;
        });

        setContentView(root);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                bindUseCases(future.get());
            } catch (Exception e) {
                resultText.setText("Kamera gagal dibuka.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases(ProcessCameraProvider provider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor, this::analyze);

        provider.unbindAll();
        camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);

        if (!camera.getCameraInfo().hasFlashUnit()) {
            torchButton.setEnabled(false);
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyze(ImageProxy proxy) {
        Image media = proxy.getImage();
        if (media == null) {
            proxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(
                media, proxy.getImageInfo().getRotationDegrees());
        recognizer.process(image)
                .addOnSuccessListener(text -> onTextRecognized(text.getText()))
                .addOnCompleteListener(task -> proxy.close());
    }

    private void onTextRecognized(String raw) {
        int value = detectDenomination(raw);
        if (value < 0) {
            lastCandidate = -1;
            stableCount = 0;
            return;
        }
        if (value == lastCandidate) {
            stableCount++;
        } else {
            lastCandidate = value;
            stableCount = 1;
        }
        if (stableCount < 2) return;

        long now = System.currentTimeMillis();
        if (value != lastSpoken || now - lastSpokenAt > 3000) {
            lastSpoken = value;
            lastSpokenAt = now;
            String label = spoken(value);
            resultText.setText("Rp " + String.format(Locale.US, "%,d", value).replace(',', '.'));
            speak(label);
        }
    }

    // ===== Deteksi nominal rupiah =====

    private static final int[] VALID = {1000, 2000, 5000, 10000, 20000, 50000, 100000};
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:[.,\\s]\\d{3})+|\\d{4,6}");

    private static int detectDenomination(String raw) {
        if (raw == null || raw.isEmpty()) return -1;
        String t = raw.toLowerCase(Locale.ROOT);

        // 1) Kata terbilang (urutan: yang lebih spesifik dulu)
        if (t.contains("seratus ribu")) return 100000;
        if (t.contains("lima puluh ribu")) return 50000;
        if (t.contains("dua puluh ribu")) return 20000;
        if (t.contains("sepuluh ribu")) return 10000;
        if (t.contains("lima ribu")) return 5000;
        if (t.contains("dua ribu")) return 2000;
        if (t.contains("seribu")) return 1000;

        // 2) Angka (mis. 100.000 / 100000 / 50 000)
        Matcher m = NUMBER.matcher(t);
        while (m.find()) {
            String digits = m.group().replaceAll("[^0-9]", "");
            try {
                int n = Integer.parseInt(digits);
                for (int v : VALID) {
                    if (n == v) return v;
                }
            } catch (NumberFormatException ignored) { }
        }
        return -1;
    }

    private static String spoken(int v) {
        switch (v) {
            case 1000: return "Seribu rupiah";
            case 2000: return "Dua ribu rupiah";
            case 5000: return "Lima ribu rupiah";
            case 10000: return "Sepuluh ribu rupiah";
            case 20000: return "Dua puluh ribu rupiah";
            case 50000: return "Lima puluh ribu rupiah";
            case 100000: return "Seratus ribu rupiah";
            default: return v + " rupiah";
        }
    }

    // ===== Util =====

    private void toggleTorch() {
        if (camera == null) return;
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        torchButton.setText(torchOn ? "Senter: Nyala" : "Senter: Mati");
    }

    private void speak(String s) {
        if (ttsReady && tts != null) {
            tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "cash");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (recognizer != null) recognizer.close();
        if (analysisExecutor != null) analysisExecutor.shutdown();
    }
}
