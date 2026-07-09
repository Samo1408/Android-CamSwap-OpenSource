package io.github.zensu357.camswap;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.zensu357.camswap.utils.LogUtil;

/**
 * Face detection + overlay engine.
 * 
 * Periodically captures frames from GLVideoRenderer, runs ML Kit face
 * detection, and produces an overlay bitmap composited at the detected
 * face position with subtle movement simulation.
 *
 * Designed to be lightweight: detects every ~6 frames, interpolates
 * between detections for smooth movement.
 */
public class FaceFilterEngine {

    private static final String TAG = "CS-FaceEngine";
    private static final int DETECT_INTERVAL_MS = 200; // detect every 200ms (~5 fps)
    private static final int MAX_FACE_WIDTH = 640;     // downscale input for speed
    private static final float JITTER_AMPLITUDE = 4f;  // px — subtle "alive" movement

    private FaceDetector mDetector;
    private HandlerThread mThread;
    private Handler mHandler;
    private volatile boolean mRunning;
    private volatile boolean mInitialized;

    // Face tracking state
    private volatile float mFaceCenterX = 0.5f;   // normalized 0..1
    private volatile float mFaceCenterY = 0.5f;
    private volatile float mFaceWidth = 0.3f;     // normalized
    private volatile long mLastFaceTime;
    private volatile boolean mHasFace;

    // Overlay
    private volatile Bitmap mOverlayBitmap;
    private volatile Bitmap mProcessedOverlay; // composited with face position
    private final Object mOverlayLock = new Object();
    private String mOverlayPath;

    // Input frame provider
    private FrameProvider mFrameProvider;

    public interface FrameProvider {
        Bitmap captureFrame(int width, int height);
    }

    public FaceFilterEngine() {
        FaceDetectorOptions opts = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.15f)
                .build();
        mDetector = FaceDetection.getClient(opts);
    }

    public void setFrameProvider(FrameProvider provider) {
        mFrameProvider = provider;
    }

    public void setOverlayPath(String path) {
        mOverlayPath = path;
        reloadOverlay();
    }

    public void reloadOverlay() {
        synchronized (mOverlayLock) {
            if (mOverlayPath != null) {
                try {
                    File f = new File(mOverlayPath);
                    if (f.exists()) {
                        mOverlayBitmap = BitmapFactory.decodeStream(new FileInputStream(f));
                        LogUtil.log(TAG + " Overlay loaded: " + f.length() + " bytes");
                        return;
                    }
                } catch (Exception e) {
                    LogUtil.log(TAG + " Overlay load error: " + e.getMessage());
                }
            }
            // Fallback: try public folder
            try {
                File f = new File("/sdcard/CamSwap/overlay_image.png");
                if (f.exists()) {
                    mOverlayBitmap = BitmapFactory.decodeStream(new FileInputStream(f));
                    LogUtil.log(TAG + " Overlay loaded from fallback");
                    return;
                }
            } catch (Exception ignored) {}
            mOverlayBitmap = null;
        }
    }

    public Bitmap getOverlayBitmap() {
        synchronized (mOverlayLock) {
            return mProcessedOverlay;
        }
    }

    public float getFaceCenterX() { return mFaceCenterX; }
    public float getFaceCenterY() { return mFaceCenterY; }
    public float getFaceWidth()   { return mFaceWidth; }
    public boolean hasFace()      { return mHasFace; }

    public void start() {
        if (mRunning) return;
        mRunning = true;
        mThread = new HandlerThread("CS-FaceFilter");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mHandler.post(mDetectRunnable);
        LogUtil.log(TAG + " started");
    }

    public void stop() {
        mRunning = false;
        if (mHandler != null) {
            mHandler.removeCallbacks(mDetectRunnable);
        }
        if (mThread != null) {
            mThread.quitSafely();
        }
        mInitialized = false;
        LogUtil.log(TAG + " stopped");
    }

    private final Runnable mDetectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            long start = SystemClock.elapsedRealtime();

            if (mFrameProvider != null) {
                detectFace();
                updateProcessedOverlay();
            }

            long elapsed = SystemClock.elapsedRealtime() - start;
            long delay = Math.max(33, DETECT_INTERVAL_MS - elapsed);
            if (mHandler != null) {
                mHandler.postDelayed(this, delay);
            }
        }
    };

    private void detectFace() {
        if (mFrameProvider == null) return;

        Bitmap frame = mFrameProvider.captureFrame(MAX_FACE_WIDTH, 
                (int)(MAX_FACE_WIDTH * 9f / 16f));
        if (frame == null) return;

        try {
            InputImage image = InputImage.fromBitmap(frame, 0);
            Task<List<Face>> task = mDetector.process(image);
            List<Face> faces = Tasks.await(task, 150, TimeUnit.MILLISECONDS);

            if (faces != null && !faces.isEmpty()) {
                Face face = faces.get(0);
                Rect bounds = face.getBoundingBox();
                mFaceCenterX = (bounds.centerX()) / (float) frame.getWidth();
                mFaceCenterY = (bounds.centerY()) / (float) frame.getHeight();
                mFaceWidth = bounds.width() / (float) frame.getWidth();
                mLastFaceTime = SystemClock.elapsedRealtime();
                mHasFace = true;

                if (!mInitialized) {
                    mInitialized = true;
                    LogUtil.log(TAG + " Face detected at (" 
                            + String.format("%.2f", mFaceCenterX) + ","
                            + String.format("%.2f", mFaceCenterY) + ")");
                }
            } else {
                // Keep last known position for ~1.5s, then fade to center
                if (SystemClock.elapsedRealtime() - mLastFaceTime > 1500) {
                    // Smooth drift toward center
                    mFaceCenterX += (0.5f - mFaceCenterX) * 0.2f;
                    mFaceCenterY += (0.5f - mFaceCenterY) * 0.2f;
                    mHasFace = false;
                }
            }
        } catch (Exception e) {
            // ML Kit task may timeout — use last known position
        } finally {
            frame.recycle();
        }
    }

    /**
     * Composite overlay bitmap at detected face position with subtle jitter.
     */
    private void updateProcessedOverlay() {
        Bitmap overlay;
        synchronized (mOverlayLock) {
            overlay = mOverlayBitmap;
        }
        if (overlay == null) return;

        // Apply subtle jitter for "alive" feel
        long t = SystemClock.elapsedRealtime();
        float jitterX = (float) (Math.sin(t * 0.003) * JITTER_AMPLITUDE);
        float jitterY = (float) (Math.cos(t * 0.004) * JITTER_AMPLITUDE);
        float jitterScale = 1f + (float)(Math.sin(t * 0.002) * 0.03);

        // Build the composited bitmap
        int w = overlay.getWidth();
        int h = overlay.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setAlpha(220); // slight transparency

        Matrix matrix = new Matrix();
        // Position overlay relative to face
        float faceX = mFaceCenterX * w - w * 0.35f;
        float faceY = mFaceCenterY * h - h * 0.5f;
        float scale = mFaceWidth * 1.4f * jitterScale;

        matrix.postTranslate(-w / 2f, -h / 2f);
        matrix.postScale(scale, scale);
        matrix.postTranslate(
                w / 2f + faceX + jitterX, 
                h / 2f + faceY + jitterY);

        canvas.drawBitmap(overlay, matrix, paint);

        synchronized (mOverlayLock) {
            if (mProcessedOverlay != null) {
                mProcessedOverlay.recycle();
            }
            mProcessedOverlay = out;
        }
    }
}
