package org.lifeproduction.lifeswitch;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("deprecation")
public final class PairingScanActivity extends Activity implements SurfaceHolder.Callback {
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private SurfaceHolder surface;
    private Camera camera;
    private boolean active;
    private TextView instruction;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        instruction = new TextView(this);
        instruction.setText("Point the camera at the booth pairing QR.");
        instruction.setPadding(24, 20, 24, 20);
        layout.addView(instruction);
        SurfaceView preview = new SurfaceView(this);
        preview.getHolder().addCallback(this);
        layout.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(view -> finish());
        layout.addView(cancel);
        setContentView(layout);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, 1);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] result) {
        super.onRequestPermissionsResult(request, permissions, result);
        if (result.length > 0 && result[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else instruction.setText("Camera access was declined. Use manual pairing in Connection.");
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        startCamera();
    }

    @Override protected void onPause() {
        active = false;
        releaseCamera();
        super.onPause();
    }

    @Override protected void onDestroy() {
        decoder.shutdownNow();
        super.onDestroy();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surface = holder;
        startCamera();
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surface = null;
        releaseCamera();
    }

    private void startCamera() {
        if (!active || surface == null || camera != null ||
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int id = -1;
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break; }
            }
            if (id < 0) throw new IllegalStateException("No rear camera");
            camera = Camera.open(id);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180 :
                rotation == Surface.ROTATION_270 ? 270 : 0;
            camera.setDisplayOrientation((info.orientation - degrees + 360) % 360);
            Camera.Parameters params = camera.getParameters();
            if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            params.setPreviewFormat(android.graphics.ImageFormat.NV21);
            camera.setParameters(params);
            Camera.Size size = camera.getParameters().getPreviewSize();
            camera.setPreviewDisplay(surface);
            camera.startPreview();
            nextFrame(camera, size);
        } catch (Exception error) {
            releaseCamera();
            instruction.setText("Camera unavailable. Use manual pairing in Connection.");
        }
    }

    private void nextFrame(Camera source, Camera.Size size) {
        source.setOneShotPreviewCallback((bytes, current) -> {
            if (!active || camera != current) return;
            decoder.execute(() -> {
                String value = decode(bytes, size.width, size.height);
                runOnUiThread(() -> {
                    if (!active || camera != current || isFinishing()) return;
                    if (value != null && MainActivity.isPairingQr(value)) {
                        setResult(RESULT_OK, new Intent().putExtra("pairing", value));
                        finish();
                    } else {
                        if (value != null) instruction.setText("That code is not a LifeSWITCH booth QR. Try again.");
                        nextFrame(current, size);
                    }
                });
            });
        });
    }

    private static String decode(byte[] bytes, int width, int height) {
        if (bytes == null || bytes.length < width * height) return null;
        PlanarYUVLuminanceSource image = new PlanarYUVLuminanceSource(bytes, width, height,
            0, 0, width, height, false);
        try { return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(image))).getText(); }
        catch (ReaderException error) { return null; }
    }

    private void releaseCamera() {
        Camera previous = camera;
        camera = null;
        if (previous != null) previous.release();
    }
}
