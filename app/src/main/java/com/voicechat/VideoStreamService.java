package com.voicechat;

import android.app.Service;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service de streaming vidéo UDP via Camera1 API.
 * Capture des frames JPEG compressées et les envoie en UDP.
 * Reçoit des frames JPEG et les affiche via un SurfaceView.
 */
public class VideoStreamService extends Service {

    private static final String TAG = "VideoStreamService";

    // Résolution volontairement basse pour passer sur Wi-Fi local
    private static final int CAM_WIDTH   = 320;
    private static final int CAM_HEIGHT  = 240;
    private static final int JPEG_QUALITY = 40; // 0-100
    private static final int FPS_TARGET   = 10;
    private static final int MAX_PACKET   = 60000; // taille max paquet UDP (< 65507)

    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private boolean videoMuted = false;

    private Camera         camera;
    private DatagramSocket sendSocket, recvSocket;
    private InetAddress    remoteAddr;
    private int            remotePort, localPort;

    private SurfaceView svLocal, svRemote;
    private RemoteFrameRenderer remoteRenderer;

    private ExecutorService executor;
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public VideoStreamService getService() { return VideoStreamService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newFixedThreadPool(2);
    }

    @Override public IBinder onBind(Intent i) { return binder; }
    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }

    // -------------------------------------------------------------------------
    // API publique
    // -------------------------------------------------------------------------

    public void startStreaming(InetAddress addr, int peerPort, int myPort,
                               SurfaceView local, SurfaceView remote) {
        if (streaming.get()) forceStop();

        remoteAddr  = addr;
        remotePort  = peerPort;
        localPort   = myPort;
        svLocal     = local;
        svRemote    = remote;
        videoMuted  = false;

        Log.d(TAG, "startStreaming → " + addr.getHostAddress() + ":" + peerPort
                + " écoute:" + myPort);

        try {
            sendSocket = new DatagramSocket();
            recvSocket = new DatagramSocket(localPort);
            recvSocket.setSoTimeout(200);

            streaming.set(true);

            // Démarrer la réception dans un thread
            executor.submit(this::receiveLoop);

            // Initialiser la caméra sur le thread principal (requis par Camera1)
            if (local != null) {
                local.getHolder().addCallback(new SurfaceHolder.Callback() {
                    @Override public void surfaceCreated(SurfaceHolder h)  { startCamera(h); }
                    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hi) {}
                    @Override public void surfaceDestroyed(SurfaceHolder h) { stopCamera(); }
                });
            }

            // Initialiser le renderer pour la vidéo distante
            if (remote != null) {
                remoteRenderer = new RemoteFrameRenderer(remote);
            }

        } catch (Exception e) {
            Log.e(TAG, "Erreur démarrage vidéo", e);
            forceStop();
        }
    }

    public void stopStreaming() { forceStop(); }
    public void setVideoMuted(boolean m) {
        this.videoMuted = m;
        Log.d(TAG, "videoMuted=" + m);
    }
    public boolean isStreaming() { return streaming.get(); }

    // -------------------------------------------------------------------------
    // Caméra
    // -------------------------------------------------------------------------

    private void startCamera(SurfaceHolder holder) {
        try {
            camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
            Camera.Parameters params = camera.getParameters();
            params.setPreviewSize(CAM_WIDTH, CAM_HEIGHT);
            params.setPreviewFormat(ImageFormat.NV21);
            params.setPreviewFpsRange(FPS_TARGET * 1000, FPS_TARGET * 1000);
            camera.setParameters(params);
            camera.setPreviewDisplay(holder);

            // Callback pour capturer chaque frame
            final int bufSize = CAM_WIDTH * CAM_HEIGHT * 3 / 2; // NV21
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.setPreviewCallbackWithBuffer((data, cam) -> {
                if (streaming.get() && !videoMuted) {
                    sendFrame(data, cam);
                }
                // Remettre le buffer en circulation
                if (cam != null) cam.addCallbackBuffer(data);
            });

            camera.startPreview();
            Log.d(TAG, "Caméra démarrée");
        } catch (Exception e) {
            Log.e(TAG, "Erreur caméra", e);
        }
    }

    private void sendFrame(byte[] nv21, Camera cam) {
        executor.submit(() -> {
            try {
                // Convertir NV21 → JPEG
                Camera.Parameters params = cam.getParameters();
                Camera.Size size = params.getPreviewSize();
                android.graphics.YuvImage yuv = new android.graphics.YuvImage(
                        nv21, ImageFormat.NV21, size.width, size.height, null);
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                yuv.compressToJpeg(
                        new android.graphics.Rect(0, 0, size.width, size.height),
                        JPEG_QUALITY, baos);
                byte[] jpeg = baos.toByteArray();

                // Fragmenter si nécessaire (UDP < 65507)
                if (jpeg.length <= MAX_PACKET) {
                    sendSocket.send(new DatagramPacket(jpeg, jpeg.length, remoteAddr, remotePort));
                } else {
                    Log.w(TAG, "Frame trop grande : " + jpeg.length + " bytes, ignorée");
                }
            } catch (Exception e) {
                if (streaming.get()) Log.w(TAG, "sendFrame: " + e.getMessage());
            }
        });
    }

    private void stopCamera() {
        if (camera != null) {
            try { camera.setPreviewCallbackWithBuffer(null); } catch (Exception ignored) {}
            try { camera.stopPreview(); } catch (Exception ignored) {}
            try { camera.release(); } catch (Exception ignored) {}
            camera = null;
            Log.d(TAG, "Caméra arrêtée");
        }
    }

    // -------------------------------------------------------------------------
    // Réception et affichage
    // -------------------------------------------------------------------------

    private void receiveLoop() {
        byte[] buf = new byte[MAX_PACKET + 1024];
        Log.d(TAG, "receiveLoop démarrée");
        while (streaming.get()) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                recvSocket.receive(pkt);
                if (pkt.getLength() > 0 && remoteRenderer != null) {
                    byte[] jpeg = new byte[pkt.getLength()];
                    System.arraycopy(pkt.getData(), pkt.getOffset(), jpeg, 0, pkt.getLength());
                    remoteRenderer.renderFrame(jpeg);
                }
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (Exception e) {
                if (streaming.get()) Log.w(TAG, "receiveLoop: " + e.getMessage());
            }
        }
        Log.d(TAG, "receiveLoop terminée");
    }

    // -------------------------------------------------------------------------
    // Reset
    // -------------------------------------------------------------------------

    private void forceStop() {
        streaming.set(false);
        stopCamera();
        if (sendSocket != null && !sendSocket.isClosed()) sendSocket.close();
        if (recvSocket != null && !recvSocket.isClosed()) recvSocket.close();
        sendSocket = null; recvSocket = null;
        Log.d(TAG, "forceStop() terminé");
    }

    @Override
    public void onDestroy() {
        forceStop();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // Renderer de frames distantes
    // -------------------------------------------------------------------------

    private static class RemoteFrameRenderer {
        private final SurfaceView sv;
        RemoteFrameRenderer(SurfaceView sv) { this.sv = sv; }

        void renderFrame(byte[] jpeg) {
            try {
                SurfaceHolder holder = sv.getHolder();
                android.graphics.Canvas canvas = holder.lockCanvas();
                if (canvas == null) return;
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null,
                            new android.graphics.Rect(0, 0, sv.getWidth(), sv.getHeight()), null);
                    bmp.recycle();
                }
                holder.unlockCanvasAndPost(canvas);
            } catch (Exception ignored) {}
        }
    }
}
