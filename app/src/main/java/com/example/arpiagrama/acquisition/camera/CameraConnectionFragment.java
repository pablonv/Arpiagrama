package com.example.arpiagrama.acquisition.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ScaleGestureDetector;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import com.example.arpiagrama.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


/**
 * Camera Connection Fragment that captures images from camera.
 *
 * <p>Instantiated by newInstance.</p>
 */
@SuppressWarnings("FragmentNotInstantiable")
public class CameraConnectionFragment extends Fragment {


    /**
     * The camera preview size will be chosen to be the smallest frame by pixel size capable of
     * containing a DESIRED_SIZE x DESIRED_SIZE square.
     */
    private static final int MINIMUM_PREVIEW_SIZE = 320;

    /** Conversion from screen rotation to JPEG orientation. */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    private static final String FRAGMENT_DIALOG = "dialog";

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /** A {@link Semaphore} to prevent the app from exiting before closing the camera. */
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);
    /** A {@link OnImageAvailableListener} to receive frames as they are available. */
    private final OnImageAvailableListener imageListener;
    /** The input size in pixels desired by TensorFlow (width and height of a square bitmap). */
    private final Size inputSize;
    /** The layout identifier to inflate for this Fragment. */
    private final int layout;

    private final ConnectionCallback cameraConnectionCallback;
    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureProgressed(
                        final CameraCaptureSession session,
                        final CaptureRequest request,
                        final CaptureResult partialResult) {
                }

                @Override
                public void onCaptureCompleted(
                        final CameraCaptureSession session,
                        final CaptureRequest request,
                        final TotalCaptureResult result) {
                }
            };

    private String cameraId;
    private AutoFitTextureView textureView;
    private CameraCaptureSession captureSession;
    private CameraDevice cameraDevice;
    private Integer sensorOrientation;
    private Size previewSize;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                        final SurfaceTexture texture, final int width, final int height) {
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                        final SurfaceTexture texture, final int width, final int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(final SurfaceTexture texture) {
                }
            };
    private ImageReader previewReader;
    private CaptureRequest.Builder previewRequestBuilder;
    private CaptureRequest previewRequest;
    private float maxDigitalZoom = 1f;
    private float currentZoom = 1f;
    private ScaleGestureDetector scaleGestureDetector;
    private ZoomListener zoomListener;
    private boolean zoomActive = false;
    private ImageView staticZoomView;
    private View trackingOverlayView;
    private Bitmap frozenZoomBitmap;
    private float zoomTranslationX = 0f;
    private float zoomTranslationY = 0f;
    private float lastTouchX = 0f;
    private float lastTouchY = 0f;
    private boolean draggingZoomImage = false;
    private final CameraDevice.StateCallback stateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(final CameraDevice cd) {
                    // This method is called when the camera is opened.  We start camera preview here.
                    cameraOpenCloseLock.release();
                    cameraDevice = cd;
                    createCameraPreviewSession();
                }

                @Override
                public void onDisconnected(final CameraDevice cd) {
                    cameraOpenCloseLock.release();
                    cd.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(final CameraDevice cd, final int error) {
                    cameraOpenCloseLock.release();
                    cd.close();
                    cameraDevice = null;
                    final Activity activity = getActivity();
                    if (null != activity) {
                        activity.finish();
                    }
                }
            };

    @SuppressLint("ValidFragment")
    private CameraConnectionFragment(
            final ConnectionCallback connectionCallback,
            final OnImageAvailableListener imageListener,
            final int layout,
            final Size inputSize) {
        this.cameraConnectionCallback = connectionCallback;
        this.imageListener = imageListener;
        this.layout = layout;
        this.inputSize = inputSize;
    }

    // dentro do CameraConnectionFragment
    private static Size chooseBestSize(StreamConfigurationMap map, int rotation, Context ctx) {
        // 1) razão da tela
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        float screenW = dm.widthPixels;
        float screenH = dm.heightPixels;
        float screenAspect = screenW / screenH;         // ~2.22 em 20:9

        // 2) pegue as saídas suportadas para SurfaceTexture
        Size[] choices = map.getOutputSizes(android.graphics.SurfaceTexture.class);
        Size best = choices[0];
        float bestScore = Float.MAX_VALUE;

        for (Size s : choices) {
            int w = s.getWidth();
            int h = s.getHeight();

            // considere rotação do sensor vs. tela
            boolean swapped = (rotation == 90 || rotation == 270);
            float aspect = swapped ? (float) h / w : (float) w / h;

            // “quão perto” da razão da tela?
            float aspectErr = Math.abs(aspect - screenAspect);

            // preferir resoluções mais altas *desde que* a razão seja próxima
            // pontuação: erro de razão + pequeno peso pela área inversa
            float sizePenalty = 1f / (w * h); // quanto maior, menor a penalidade
            float score = aspectErr + 0.0000005f * sizePenalty;

            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best; // ex.: algo perto de 1920×864 (≈ 20:9) se a câmera suportar
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the minimum of both, or an exact match if possible.
     *
     * @param choices The list of sizes that the camera supports for the intended output class
     * @param width The minimum desired width
     * @param height The minimum desired height
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    protected static Size chooseOptimalSize(final Size[] choices, final int width, final int height) {
        final int minSize = Math.max(Math.min(width, height), MINIMUM_PREVIEW_SIZE);
        final Size desiredSize = new Size(width, height);

        // Collect the supported resolutions that are at least as big as the preview Surface
        boolean exactSizeFound = false;
        final List<Size> bigEnough = new ArrayList<Size>();
        final List<Size> tooSmall = new ArrayList<Size>();
        for (final Size option : choices) {
            if (option.equals(desiredSize)) {
                // Set the size but don't return yet so that remaining sizes will still be logged.
                exactSizeFound = true;
            }

            if (option.getHeight() >= minSize && option.getWidth() >= minSize) {
                bigEnough.add(option);
            } else {
                tooSmall.add(option);
            }
        }

        if (exactSizeFound) {
            return desiredSize;
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            final Size chosenSize = Collections.min(bigEnough, new CompareSizesByArea());
            // LOGGER.i("Chosen size: " + chosenSize.getWidth() + "x" + chosenSize.getHeight());
            return chosenSize;
        } else {
            // LOGGER.e("Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    public static CameraConnectionFragment newInstance(
            final ConnectionCallback callback,
            final OnImageAvailableListener imageListener,
            final int layout,
            final Size inputSize) {
        return new CameraConnectionFragment(callback, imageListener, layout, inputSize);
    }

    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private void showToast(final String text) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(
                    new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                        }
                    });
        }
    }

    @Override
    public View onCreateView(
            final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        return inflater.inflate(layout, container, false);
    }

    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState) {
        textureView = view.findViewById(R.id.texture);
        trackingOverlayView = view.findViewById(R.id.tracking_overlay);
        staticZoomView = view.findViewById(R.id.static_zoom_view);
        scaleGestureDetector = new ScaleGestureDetector(requireContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                return ativarZoomEstaticoSeNecessario();
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (!ativarZoomEstaticoSeNecessario()) {
                    return false;
                }
                float novoZoom = currentZoom * detector.getScaleFactor();
                currentZoom = Math.max(1f, Math.min(novoZoom, maxDigitalZoom));
                aplicarZoom();
                return true;
            }
        });
        view.setOnTouchListener((v, event) -> {
            if (scaleGestureDetector != null) {
                scaleGestureDetector.onTouchEvent(event);
            }
            if (event != null) {
                tratarArrastoDaImagemComZoom(event);
            }
            if (event != null && (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                    && zoomActive && currentZoom <= 1.01f) {
                desativarZoomEstatico();
            }
            return true;
        });
    }

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        desativarZoomEstatico();
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    public void setCamera(String cameraId) {
        this.cameraId = cameraId;
    }

    public void setZoomListener(ZoomListener zoomListener) {
        this.zoomListener = zoomListener;
    }

    /** Sets up member variables related to camera. */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void setUpCameraOutputs() {
        final Activity activity = getActivity();
        final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Float availableZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxDigitalZoom = Math.max(4f, availableZoom == null ? 1f : availableZoom);
            currentZoom = Math.max(1f, Math.min(currentZoom, maxDigitalZoom));

            final StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
            // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
            // garbage capture data.
            int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            previewSize = chooseBestSize(map, displayRotation, activity);

            // We fit the aspect ratio of TextureView to the size of preview we picked.
            final int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
            } else {
                textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
            }
        } catch (final CameraAccessException e) {
            //  LOGGER.e(e, "Exception!");
        } catch (final NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
//            ErrorDialog.newInstance("getString(R.string.tfe_ic_camera_error)")
//                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            throw new IllegalStateException("getString(R.string.tfe_ic_camera_error)");
        }

        cameraConnectionCallback.onPreviewSizeChosen(previewSize, sensorOrientation);
    }


    @SuppressLint("MissingPermission")
    private void openCamera(final int width, final int height) {
        setUpCameraOutputs();
        configureTransform(width, height);
        final Activity activity = getActivity();
        final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (final CameraAccessException e) {
            // LOGGER.e(e, "Exception!");
        } catch (final InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /** Closes the current {@link CameraDevice}. */
    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (null != captureSession) {
                captureSession.close();
                captureSession = null;
            }
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (null != previewReader) {
                previewReader.close();
                previewReader = null;
            }
        } catch (final InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    /** Starts a background thread and its {@link Handler}. */
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("ImageListener");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    /** Stops the background thread and its {@link Handler}. */
    private void stopBackgroundThread() {
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (final InterruptedException e) {
            //    LOGGER.e(e, "Exception!");
        }
    }

    /** Creates a new {@link CameraCaptureSession} for camera preview. */
    private void createCameraPreviewSession() {
        try {
            final SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            // This is the output Surface we need to start preview.
            final Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            // LOGGER.i("Opening camera preview: " + previewSize.getWidth() + "x" + previewSize.getHeight());

            // Create the reader for the preview frames.
            previewReader =
                    ImageReader.newInstance(
                            previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

            previewReader.setOnImageAvailableListener(imageListener, backgroundHandler);
            previewRequestBuilder.addTarget(previewReader.getSurface());

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice.createCaptureSession(
                    Arrays.asList(surface, previewReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(final CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == cameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            captureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                // Flash is automatically enabled when necessary.
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

                                // Finally, we start displaying the camera preview.
                                previewRequest = previewRequestBuilder.build();
                                captureSession.setRepeatingRequest(
                                        previewRequest, captureCallback, backgroundHandler);
                            } catch (final CameraAccessException e) {
                                //       LOGGER.e(e, "Exception!");
                            }
                        }

                        @Override
                        public void onConfigureFailed(final CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed");
                        }
                    },
                    null);
        } catch (final CameraAccessException e) {
            //        LOGGER.e(e, "Exception!");
        }
    }

    private boolean ativarZoomEstaticoSeNecessario() {
        if (zoomActive) {
            return true;
        }
        if (staticZoomView == null || zoomListener == null) {
            return false;
        }
        Bitmap snapshot = zoomListener.onRequestZoomSnapshot();
        if (snapshot == null) {
            return false;
        }
        limparBitmapZoomCongelado();
        frozenZoomBitmap = snapshot;
        staticZoomView.setImageBitmap(snapshot);
        staticZoomView.setScaleX(1f);
        staticZoomView.setScaleY(1f);
        staticZoomView.setPivotX(staticZoomView.getWidth() / 2f);
        staticZoomView.setPivotY(staticZoomView.getHeight() / 2f);
        zoomTranslationX = 0f;
        zoomTranslationY = 0f;
        aplicarTranslacaoComLimites();
        staticZoomView.setVisibility(View.VISIBLE);
        textureView.setVisibility(View.INVISIBLE);
        if (trackingOverlayView != null) {
            trackingOverlayView.setVisibility(View.INVISIBLE);
        }
        zoomActive = true;
        currentZoom = 1f;
        zoomListener.onZoomStateChanged(true, currentZoom);
        return true;
    }

    private void aplicarZoom() {
        if (!zoomActive || staticZoomView == null) {
            return;
        }
        staticZoomView.setPivotX(staticZoomView.getWidth() / 2f);
        staticZoomView.setPivotY(staticZoomView.getHeight() / 2f);
        staticZoomView.setScaleX(currentZoom);
        staticZoomView.setScaleY(currentZoom);
        aplicarTranslacaoComLimites();
    }

    private void desativarZoomEstatico() {
        if (!zoomActive) {
            return;
        }
        zoomActive = false;
        currentZoom = 1f;
        if (staticZoomView != null) {
            staticZoomView.setScaleX(1f);
            staticZoomView.setScaleY(1f);
            staticZoomView.setTranslationX(0f);
            staticZoomView.setTranslationY(0f);
            staticZoomView.setImageDrawable(null);
            staticZoomView.setVisibility(View.GONE);
        }
        zoomTranslationX = 0f;
        zoomTranslationY = 0f;
        draggingZoomImage = false;
        textureView.setVisibility(View.VISIBLE);
        if (trackingOverlayView != null) {
            trackingOverlayView.setVisibility(View.VISIBLE);
        }
        limparBitmapZoomCongelado();
        if (zoomListener != null) {
            zoomListener.onZoomStateChanged(false, currentZoom);
        }
    }

    private void limparBitmapZoomCongelado() {
        if (frozenZoomBitmap != null && !frozenZoomBitmap.isRecycled()) {
            frozenZoomBitmap.recycle();
        }
        frozenZoomBitmap = null;
    }

    private void tratarArrastoDaImagemComZoom(MotionEvent event) {
        if (!zoomActive || staticZoomView == null) {
            draggingZoomImage = false;
            return;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                draggingZoomImage = currentZoom > 1.01f && event.getPointerCount() == 1;
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                draggingZoomImage = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!draggingZoomImage || event.getPointerCount() != 1) {
                    break;
                }
                float deltaX = event.getX() - lastTouchX;
                float deltaY = event.getY() - lastTouchY;
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                zoomTranslationX += deltaX;
                zoomTranslationY += deltaY;
                aplicarTranslacaoComLimites();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                draggingZoomImage = false;
                break;
            default:
                break;
        }
    }

    private void aplicarTranslacaoComLimites() {
        if (staticZoomView == null) {
            return;
        }
        float maxOffsetX = Math.max(0f, (staticZoomView.getWidth() * (currentZoom - 1f)) / 2f);
        float maxOffsetY = Math.max(0f, (staticZoomView.getHeight() * (currentZoom - 1f)) / 2f);
        zoomTranslationX = Math.max(-maxOffsetX, Math.min(zoomTranslationX, maxOffsetX));
        zoomTranslationY = Math.max(-maxOffsetY, Math.min(zoomTranslationY, maxOffsetY));
        staticZoomView.setTranslationX(zoomTranslationX);
        staticZoomView.setTranslationY(zoomTranslationY);
    }

    /**
     * Configures the necessary {@link Matrix} transformation to `mTextureView`. This method should be
     * called after the camera preview size is determined in setUpCameraOutputs and also the size of
     * `mTextureView` is fixed.
     *
     * @param viewWidth The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(final int viewWidth, final int viewHeight) {
        final Activity activity = getActivity();
        if (null == textureView || null == previewSize || null == activity) {
            return;
        }
        final int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        final Matrix matrix = new Matrix();
        final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        final RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        final float centerX = viewRect.centerX();
        final float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            final float scale =
                    Math.max(
                            (float) viewHeight / previewSize.getHeight(),
                            (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    /**
     * Callback for Activities to use to initialize their data once the selected preview size is
     * known.
     */
    public interface ConnectionCallback {
        void onPreviewSizeChosen(Size size, int cameraRotation);
    }

    public interface ZoomListener {
        Bitmap onRequestZoomSnapshot();
        void onZoomStateChanged(boolean zoomActive, float zoomRatio);
    }

    /** Compares two {@code Size}s based on their areas. */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(final Size lhs, final Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    /** Shows an error message dialog. */
    public static class ErrorDialog extends DialogFragment {
        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(final String message) {
            final ErrorDialog dialog = new ErrorDialog();
            final Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(final Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(
                            android.R.string.ok,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(final DialogInterface dialogInterface, final int i) {
                                    activity.finish();
                                }
                            })
                    .create();
        }
    }
}
