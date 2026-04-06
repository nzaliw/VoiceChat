package com.voicechat;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VideoStreamService — streaming vidéo UDP fiable.
 *
 * Architecture :
 *  - Capture : Camera1 → NV21 → JPEG → UDP
 *  - Lecture  : UDP → JPEG → Canvas (letterbox 9:16, sans déformation)
 *  - Reset complet entre chaque appel
 *
 * Format d'image : 9:16 portrait (368×640) côté capture
 * Affichage      : letterbox centré, fond noir, aucune déformation
 */
public class VideoStreamService extends Service {

    private static final String TAG = "VideoStreamService";

    // Résolution de capture — rapport 9:16 pour un téléphone portrait
    private static final int CAP_W    = 352;
    private static final int CAP_H    = 640;
    private static final int JPEG_Q   = 50;
    private static final int FPS      = 12;
    private static final int MAX_PKT  = 62000; // < limite UDP 65507

    // ── état ────────────────────────────────────────────────────────────────
    private final AtomicBoolean streaming  = new AtomicBoolean(false);
    private volatile boolean    videoMuted = false;

    // ── caméra ──────────────────────────────────────────────────────────────
    private Camera  camera;
    private int     camOrientation = 0; // degrés à appliquer à setDisplayOrientation
    private boolean frontFacing    = true;

    // ── réseau ──────────────────────────────────────────────────────────────
    private DatagramSocket sendSocket, recvSocket;
    private InetAddress    remoteAddr;
    private int            remotePort, localPort;

    // ── UI ──────────────────────────────────────────────────────────────────
    private SurfaceView svLocal, svRemote;
    private final Paint bgPaint = new Paint();

    // ── threads ─────────────────────────────────────────────────────────────
    private final Handler       mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService     executor;
    private final IBinder       binder      = new LocalBinder();

    public class LocalBinder extends Binder {
        public VideoStreamService getService() { return VideoStreamService.this; }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newFixedThreadPool(3);
        bgPaint.setColor(Color.BLACK);
        bgPaint.setStyle(Paint.Style.FILL);
    }

    @Override public IBinder onBind(Intent i)   { return binder; }
    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }

    // ════════════════════════════════════════════════════════════════════════
    // API publique
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Démarre le streaming vidéo bidirectionnel.
     * Peut être appelé plusieurs fois (reset automatique).
     */
    public void startStreaming(InetAddress addr, int peerPort, int myPort,
                               SurfaceView local, SurfaceView remote) {
        // Reset propre si un streaming était déjà actif
        if (streaming.get()) {
            Log.d(TAG, "startStreaming: reset du streaming précédent");
            forceStop();
            // Laisser le temps au thread de réception de se terminer
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }

        remoteAddr = addr;
        remotePort = peerPort;
        localPort  = myPort;
        svLocal    = local;
        svRemote   = remote;
        videoMuted = false;

        Log.d(TAG, "startStreaming → " + addr.getHostAddress()
                + ":" + peerPort + "  écoute:" + myPort);

        try {
            // Recréer l'executor si nécessaire
            if (executor == null || executor.isShutdown()) {
                executor = Executors.newFixedThreadPool(3);
            }

            // Ouvrir les sockets
            sendSocket = new DatagramSocket();
            recvSocket = new DatagramSocket(localPort);
            recvSocket.setSoTimeout(300);

            streaming.set(true);

            // Démarrer la réception en arrière-plan
            executor.submit(this::receiveLoop);

            // Initialiser la caméra sur le thread UI (obligatoire Camera1)
            mainHandler.post(this::setupCamera);

        } catch (Exception e) {
            Log.e(TAG, "Erreur startStreaming", e);
            forceStop();
        }
    }

    public void stopStreaming()          { forceStop(); }
    public boolean isStreaming()         { return streaming.get(); }
    public void setVideoMuted(boolean m) { this.videoMuted = m; }

    // ════════════════════════════════════════════════════════════════════════
    // Caméra — doit tourner sur le thread UI
    // ════════════════════════════════════════════════════════════════════════

    private void setupCamera() {
        if (!streaming.get() || svLocal == null) return;

        SurfaceHolder holder = svLocal.getHolder();

        // Si la surface est déjà prête → ouvrir la caméra directement
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            openAndStartCamera(holder);
        } else {
            // Sinon attendre que la surface soit créée
            holder.addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder h) {
                    openAndStartCamera(h);
                }
                @Override public void surfaceChanged(SurfaceHolder h, int fmt, int w, int ht) {
                    // Redémarrer preview si dimensions changent
                    if (camera != null) {
                        try { camera.stopPreview(); camera.startPreview(); }
                        catch (Exception ignored) {}
                    }
                }
                @Override public void surfaceDestroyed(SurfaceHolder h) {
                    releaseCamera();
                }
            });
        }
    }

    private void openAndStartCamera(SurfaceHolder holder) {
        try {
            // Trouver la caméra frontale (ou arrière par défaut)
            int numCams = Camera.getNumberOfCameras();
            int chosenId = 0;
            frontFacing  = false;
            Camera.CameraInfo info = new Camera.CameraInfo();

            for (int i = 0; i < numCams; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    chosenId    = i;
                    frontFacing = true;
                    break;
                }
            }

            camera = Camera.open(chosenId);
            Camera.getCameraInfo(chosenId, info);

            // ── Rotation display ────────────────────────────────────────
            int screenDeg = getScreenRotationDegrees();
            int displayRotation;
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                displayRotation = (info.orientation + screenDeg) % 360;
                displayRotation = (360 - displayRotation) % 360; // miroir
            } else {
                displayRotation = (info.orientation - screenDeg + 360) % 360;
            }
            camera.setDisplayOrientation(displayRotation);
            camOrientation = displayRotation;

            // ── Paramètres ──────────────────────────────────────────────
            Camera.Parameters params = camera.getParameters();

            // Trouver la résolution 9:16 la plus proche
            Camera.Size bestSize = chooseBestSize(params.getSupportedPreviewSizes());
            params.setPreviewSize(bestSize.width, bestSize.height);
            params.setPreviewFormat(ImageFormat.NV21);

            // FPS
            int[] fpsRange = chooseBestFps(params.getSupportedPreviewFpsRange());
            if (fpsRange != null) params.setPreviewFpsRange(fpsRange[0], fpsRange[1]);

            // Focus video
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            camera.setParameters(params);
            camera.setPreviewDisplay(holder);

            // Buffers de capture double
            int bufSize = bestSize.width * bestSize.height * 3 / 2;
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.addCallbackBuffer(new byte[bufSize]);

            final Camera.Size finalSize = bestSize;
            camera.setPreviewCallbackWithBuffer((data, cam) -> {
                if (streaming.get() && !videoMuted && cam != null) {
                    // Envoyer la frame dans le pool de threads
                    final byte[] copy = data; // pas de copie, on utilise le buffer directement
                    executor.submit(() -> sendFrame(copy, finalSize.width, finalSize.height, cam));
                } else {
                    if (cam != null) try { cam.addCallbackBuffer(data); } catch (Exception ignored) {}
                }
            });

            camera.startPreview();
            Log.d(TAG, "✅ Caméra démarrée: " + bestSize.width + "×" + bestSize.height
                    + " rot=" + displayRotation + " fps≈" + FPS);

        } catch (Exception e) {
            Log.e(TAG, "Erreur ouverture caméra", e);
        }
    }

    /**
     * Choisit la résolution de prévisualisation la plus proche du ratio 9:16
     * avec une préférence pour les petites résolutions (économie bande passante).
     */
    private Camera.Size chooseBestSize(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0);
        double targetRatio = 9.0 / 16.0; // portrait
        double bestScore   = Double.MAX_VALUE;

        for (Camera.Size s : sizes) {
            double ratio    = (double) s.width / s.height;
            double ratioDiff = Math.abs(ratio - targetRatio);
            // Pénaliser les trop grandes résolutions
            double resPenalty = (s.width * s.height) / (1280.0 * 720.0);
            double score = ratioDiff * 10 + resPenalty;
            if (score < bestScore) { bestScore = score; best = s; }
        }
        Log.d(TAG, "Résolution choisie: " + best.width + "×" + best.height);
        return best;
    }

    private int[] chooseBestFps(List<int[]> ranges) {
        if (ranges == null || ranges.isEmpty()) return null;
        int target = FPS * 1000;
        int[] best = ranges.get(0);
        int   bestDiff = Integer.MAX_VALUE;
        for (int[] r : ranges) {
            int diff = Math.abs(r[1] - target);
            if (diff < bestDiff) { bestDiff = diff; best = r; }
        }
        return best;
    }

    private int getScreenRotationDegrees() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            switch (wm.getDefaultDisplay().getRotation()) {
                case Surface.ROTATION_90:  return 90;
                case Surface.ROTATION_180: return 180;
                case Surface.ROTATION_270: return 270;
                default:                   return 0;
            }
        } catch (Exception e) { return 0; }
    }

    private void releaseCamera() {
        if (camera == null) return;
        try { camera.setPreviewCallbackWithBuffer(null); } catch (Exception ignored) {}
        try { camera.stopPreview();  } catch (Exception ignored) {}
        try { camera.release();      } catch (Exception ignored) {}
        camera = null;
        Log.d(TAG, "Caméra libérée");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Envoi de frames
    // ════════════════════════════════════════════════════════════════════════

    private void sendFrame(byte[] nv21, int w, int h, Camera cam) {
        try {
            // Convertir NV21 → JPEG
            YuvImage yuv   = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(w * h / 4);
            yuv.compressToJpeg(new Rect(0, 0, w, h), JPEG_Q, baos);
            byte[] jpeg = baos.toByteArray();

            if (jpeg.length <= MAX_PKT && sendSocket != null && !sendSocket.isClosed()) {
                sendSocket.send(new DatagramPacket(jpeg, jpeg.length, remoteAddr, remotePort));
            }
        } catch (Exception e) {
            if (streaming.get()) Log.v(TAG, "sendFrame: " + e.getMessage());
        } finally {
            // Remettre le buffer en circulation
            if (cam != null) {
                try { cam.addCallbackBuffer(nv21); } catch (Exception ignored) {}
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Réception et affichage
    // ════════════════════════════════════════════════════════════════════════

    private void receiveLoop() {
        byte[] buf = new byte[MAX_PKT + 512];
        Log.d(TAG, "receiveLoop démarrée");

        while (streaming.get()) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                recvSocket.receive(pkt);
                if (pkt.getLength() > 100) { // ignorer les paquets trop petits
                    final byte[] jpeg = new byte[pkt.getLength()];
                    System.arraycopy(pkt.getData(), 0, jpeg, 0, jpeg.length);
                    renderFrame(jpeg);
                }
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (Exception e) {
                if (streaming.get()) Log.v(TAG, "receiveLoop: " + e.getMessage());
            }
        }
        Log.d(TAG, "receiveLoop terminée");
    }

    /**
     * Affiche un JPEG sur svRemote.
     *
     * Règles d'affichage :
     * - Fond noir
     * - Image centrée et mise à l'échelle en mode "fit" (letterbox/pillarbox)
     * - Aucune déformation : le ratio original est toujours respecté
     */
    private void renderFrame(byte[] jpeg) {
        if (svRemote == null) return;
        SurfaceHolder holder = svRemote.getHolder();
        if (holder == null) return;
        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) return;

        try {
            // Décoder sans scaling forcé
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (src == null) return;

            // Appliquer la rotation si nécessaire (même rotation que l'affichage local)
            Bitmap rotated = src;
            if (camOrientation != 0) {
                Matrix m = new Matrix();
                m.postRotate(camOrientation);
                rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, false);
                src.recycle();
            }

            Canvas canvas = holder.lockCanvas();
            if (canvas == null) {
                rotated.recycle();
                return;
            }

            try {
                int vw = canvas.getWidth();
                int vh = canvas.getHeight();
                int bw = rotated.getWidth();
                int bh = rotated.getHeight();

                // Fond noir
                canvas.drawRect(0, 0, vw, vh, bgPaint);

                if (bw > 0 && bh > 0) {
                    // Calcul letterbox : ajuster l'image dans le canvas sans déformer
                    float scale = Math.min((float) vw / bw, (float) vh / bh);
                    float dw = bw * scale;
                    float dh = bh * scale;
                    float dx = (vw - dw) / 2f;
                    float dy = (vh - dh) / 2f;

                    canvas.drawBitmap(rotated, null,
                            new RectF(dx, dy, dx + dw, dy + dh), null);
                }
            } finally {
                holder.unlockCanvasAndPost(canvas);
            }
            rotated.recycle();

        } catch (Exception e) {
            Log.v(TAG, "renderFrame: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Reset complet
    // ════════════════════════════════════════════════════════════════════════

    private void forceStop() {
        streaming.set(false);

        // Libérer la caméra sur le thread UI
        mainHandler.post(this::releaseCamera);

        // Fermer les sockets (interrompt receiveLoop)
        closeSocket(sendSocket); sendSocket = null;
        closeSocket(recvSocket); recvSocket = null;

        Log.d(TAG, "forceStop() terminé");
    }

    private void closeSocket(DatagramSocket s) {
        if (s != null && !s.isClosed()) try { s.close(); } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        forceStop();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
