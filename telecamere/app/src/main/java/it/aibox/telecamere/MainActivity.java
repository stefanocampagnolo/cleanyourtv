package it.aibox.telecamere;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Griglia di telecamere RTSP per Android TV.
 * Campo "engine" per telecamera: "exo" (predefinito, Media3) o "vlc" (libVLC, per SDP non standard).
 * Configurazione: schermata Impostazioni (OK tenuto premuto / tasto MENU), salvata in memoria privata (Config).
 * Telecomando: frecce = scegli riquadro, OK = schermo intero (flusso principale), Indietro = torna alla griglia.
 */
@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends Activity {

    static final class Cam {
        String name, sub, main, engine;
    }

    /** Un riquadro: player (ExoPlayer o libVLC) + vista + stato di riconnessione. */
    final class Tile {
        final Cam cam;
        final String url;
        final boolean audio;
        final FrameLayout frame;
        final FrameLayout videoBox;
        final TextView status;
        ExoPlayer player;          // motore "exo"
        AspectRatioFrameLayout exoView;
        TextureView exoTexture;
        org.videolan.libvlc.MediaPlayer vlc; // motore "vlc"
        VLCVideoLayout vlcView;
        long lastPos = -1;
        int stuckChecks = 0;
        final Runnable reconnect = this::start;

        Tile(Cam cam, String url, boolean audio, FrameLayout frame, FrameLayout videoBox, TextView status) {
            this.cam = cam; this.url = url; this.audio = audio;
            this.frame = frame; this.videoBox = videoBox; this.status = status;
        }

        void start() {
            stop();
            status.setText("Connessione…");
            status.setVisibility(View.VISIBLE);
            if ("vlc".equals(cam.engine)) startVlc(); else startExo();
            lastPos = -1; stuckChecks = 0;
        }

        void startExo() {
            if (exoView == null) {
                // TextureView (composizione GPU) invece di SurfaceView: la TV ha pochi livelli video
                // hardware e con 3 SurfaceView alcuni riquadri diventavano neri.
                exoView = new AspectRatioFrameLayout(MainActivity.this);
                exoView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                exoTexture = new TextureView(MainActivity.this);
                exoView.addView(exoTexture, new FrameLayout.LayoutParams(-1, -1));
                videoBox.addView(exoView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            }
            DefaultRenderersFactory rf = new DefaultRenderersFactory(MainActivity.this)
                    .setEnableDecoderFallback(true); // se i decoder hardware sono occupati usa quello software
            DefaultLoadControl lc = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(500, 2000, 250, 500) // buffer piccolo = poco ritardo
                    .build();
            player = new ExoPlayer.Builder(MainActivity.this, rf).setLoadControl(lc).build();
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audio).build());
            if (!audio) player.setVolume(0f);
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) status.setVisibility(View.GONE);
                    else if (state == Player.STATE_BUFFERING) { status.setText("Caricamento…"); status.setVisibility(View.VISIBLE); }
                    else if (state == Player.STATE_ENDED) scheduleReconnect("Flusso terminato");
                }
                @Override public void onVideoSizeChanged(VideoSize vs) {
                    if (vs.width > 0 && vs.height > 0)
                        exoView.setAspectRatio(vs.width * vs.pixelWidthHeightRatio / vs.height);
                }
                @Override public void onPlayerError(PlaybackException e) {
                    scheduleReconnect("Errore: " + e.getErrorCodeName());
                }
            });
            player.setVideoTextureView(exoTexture);
            RtspMediaSource src = new RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true) // TCP: più stabile sul Wi-Fi
                    .setTimeoutMs(8000)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(url)));
            player.setMediaSource(src);
            player.setPlayWhenReady(true);
            player.prepare();
        }

        void startVlc() {
            if (vlcView == null) {
                vlcView = new VLCVideoLayout(MainActivity.this);
                vlcView.setFocusable(false);
                videoBox.addView(vlcView, new FrameLayout.LayoutParams(-1, -1));
            }
            vlc = new org.videolan.libvlc.MediaPlayer(libVlc());
            vlc.attachViews(vlcView, null, false, true); // true = TextureView (vedi sopra)
            Media m = new Media(libVlc(), Uri.parse(url));
            m.setHWDecoderEnabled(true, false);
            m.addOption(":rtsp-tcp");
            m.addOption(":rtsp-frame-buffer-size=2000000");
            m.addOption(":network-caching=400");
            if (!audio) m.addOption(":no-audio");
            vlc.setMedia(m);
            m.release();
            final org.videolan.libvlc.MediaPlayer mine = vlc;
            vlc.setEventListener(ev -> {
                if (vlc != mine) return;
                switch (ev.type) {
                    case org.videolan.libvlc.MediaPlayer.Event.Vout:
                        if (ev.getVoutCount() > 0) status.setVisibility(View.GONE);
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.TimeChanged:
                        if (status.getVisibility() == View.VISIBLE) status.setVisibility(View.GONE);
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.EncounteredError:
                        scheduleReconnect("Errore VLC");
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.EndReached:
                        scheduleReconnect("Flusso terminato");
                        break;
                }
            });
            vlc.play();
        }

        void scheduleReconnect(String why) {
            status.setText(why + " – riprovo…");
            status.setVisibility(View.VISIBLE);
            handler.removeCallbacks(reconnect);
            handler.postDelayed(reconnect, 4000);
        }

        /** Chiamato ogni 5 s: se il video è fermo da ~15 s riconnette. */
        void watchdog() {
            long pos;
            if (player != null) {
                if (player.getPlaybackState() == Player.STATE_IDLE) return; // già in riconnessione
                pos = player.getCurrentPosition();
            } else if (vlc != null) {
                pos = vlc.getTime();
            } else return;
            if (pos == lastPos) {
                // prima immagine non ancora arrivata: alcuni flussi aspettano il fotogramma chiave, attendi 30 s
                int limit = pos <= 0 ? 6 : 3;
                if (++stuckChecks >= limit) scheduleReconnect(pos <= 0 ? "Nessuna immagine" : "Immagine ferma");
            } else stuckChecks = 0;
            lastPos = pos;
        }

        void stop() {
            handler.removeCallbacks(reconnect);
            if (player != null) {
                player.release();
                player = null;
            }
            if (vlc != null) {
                org.videolan.libvlc.MediaPlayer v = vlc;
                vlc = null;
                v.setEventListener(null);
                v.stop();
                v.detachViews();
                v.release();
            }
        }
    }

    LibVLC libVlcInstance;

    LibVLC libVlc() {
        if (libVlcInstance == null) {
            ArrayList<String> opts = new ArrayList<>();
            opts.add("--rtsp-tcp");
            // i fotogrammi chiave dei flussi principali (H.265 1080p/1440p) superano i 250 KB predefiniti:
            // venivano troncati e VLC restava fermo su "Waiting for VPS/SPS/PPS"
            opts.add("--rtsp-frame-buffer-size=2000000");
            opts.add("--no-stats");
            opts.add("--no-sub-autodetect-file");
            libVlcInstance = new LibVLC(this, opts);
        }
        return libVlcInstance;
    }

    @Override
    protected void onDestroy() {
        if (libVlcInstance != null) { libVlcInstance.release(); libVlcInstance = null; }
        super.onDestroy();
    }

    final Handler handler = new Handler(Looper.getMainLooper());
    final List<Cam> cams = new ArrayList<>();
    final List<Tile> tiles = new ArrayList<>();
    FrameLayout root;
    int fullscreenIndex = -1; // -1 = griglia
    int focusIndex = 0;

    final Runnable watchdogTask = new Runnable() {
        @Override public void run() {
            for (Tile t : tiles) t.watchdog();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
    }

    Config cfg;
    int bigIndex = 0;          // disposizione "una grande": quale telecamera è grande
    boolean hintShown = false;

    final Runnable cycleTask = new Runnable() {
        @Override public void run() {
            if (fullscreenIndex >= 0 && cams.size() > 1) showFullscreen((fullscreenIndex + 1) % cams.size());
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        String imported = Config.importFromFile(this);
        if (imported != null) Toast.makeText(this, imported, Toast.LENGTH_LONG).show();
        cfg = Config.load(this);
        cams.clear();
        for (Config.CamCfg c : cfg.cams) {
            Cam cam = new Cam();
            cam.name = c.name;
            cam.main = cfg.url(c, true);
            cam.sub = c.gridMain ? cam.main : cfg.url(c, false);
            cam.engine = c.engine;
            cams.add(cam);
        }
        if (!cfg.isUsable() || cams.isEmpty()) { openSettings(); return; }
        if (fullscreenIndex >= cams.size()) fullscreenIndex = -1;
        if (focusIndex >= cams.size()) focusIndex = 0;
        if (bigIndex >= cams.size()) bigIndex = 0;
        if (Config.LAYOUT_CYCLE.equals(cfg.layout)) showFullscreen(fullscreenIndex >= 0 ? fullscreenIndex : 0);
        else if (fullscreenIndex >= 0) showFullscreen(fullscreenIndex);
        else showGrid();
        handler.postDelayed(watchdogTask, 5000);
        if (!hintShown) {
            hintShown = true;
            Toast.makeText(this, "Tieni premuto OK per le impostazioni", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStop() {
        // in background libera subito decoder e connessioni
        handler.removeCallbacks(watchdogTask);
        releaseAll();
        super.onStop();
    }

    void releaseAll() {
        handler.removeCallbacks(cycleTask);
        for (Tile t : tiles) t.stop();
        tiles.clear();
        root.removeAllViews();
    }

    void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    Tile makeTile(Cam cam, String url, boolean audio, boolean focusable) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundResource(R.drawable.tile_bg);
        int p = dp(4);
        frame.setPadding(p, p, p, p);
        frame.setFocusable(focusable);
        frame.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        frame.setOnLongClickListener(v -> { openSettings(); return true; }); // OK tenuto premuto

        FrameLayout videoBox = new FrameLayout(this);
        videoBox.setFocusable(false);
        frame.addView(videoBox, new FrameLayout.LayoutParams(-1, -1));

        TextView label = new TextView(this);
        label.setText(cam.name);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setShadowLayer(4, 0, 0, Color.BLACK);
        label.setBackgroundColor(0x66000000);
        label.setPadding(dp(8), dp(2), dp(8), dp(2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(dp(8), dp(8), 0, 0);
        frame.addView(label, lp);

        TextView status = new TextView(this);
        status.setTextColor(0xFFFFC107);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setGravity(Gravity.CENTER);
        frame.addView(status, new FrameLayout.LayoutParams(-1, -1));

        Tile t = new Tile(cam, url, audio, frame, videoBox, status);
        tiles.add(t);
        return t;
    }

    /** Riquadro della griglia: OK = azione, focus memorizzato. */
    View gridTile(int idx, Runnable onOk) {
        Tile t = makeTile(cams.get(idx), cams.get(idx).sub, false, true);
        t.frame.setOnClickListener(v -> onOk.run());
        t.frame.setOnFocusChangeListener((v, has) -> { if (has) focusIndex = idx; });
        return t.frame;
    }

    /** Casella libera della griglia: scorciatoia per le impostazioni. */
    View settingsCell() {
        TextView tv = new TextView(this);
        tv.setText("⚙  Impostazioni\n\n(oppure tieni premuto OK)");
        tv.setTextColor(0xFF9AA5B1);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setGravity(Gravity.CENTER);
        tv.setFocusable(true);
        tv.setBackgroundResource(R.drawable.tile_bg);
        tv.setOnClickListener(v -> openSettings());
        return tv;
    }

    void showGrid() {
        releaseAll();
        fullscreenIndex = -1;
        int n = cams.size();
        if (Config.LAYOUT_BIG.equals(cfg.layout) && n >= 2) {
            // una grande a sinistra (2/3), le altre impilate a destra; OK su una piccola la rende grande
            LinearLayout h = new LinearLayout(this);
            h.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(h, new FrameLayout.LayoutParams(-1, -1));
            h.addView(gridTile(bigIndex, () -> showFullscreen(bigIndex)), new LinearLayout.LayoutParams(0, -1, 2f));
            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            h.addView(right, new LinearLayout.LayoutParams(0, -1, 1f));
            for (int i = 0; i < n; i++) {
                if (i == bigIndex) continue;
                final int idx = i;
                right.addView(gridTile(i, () -> { bigIndex = idx; focusIndex = idx; showGrid(); }),
                        new LinearLayout.LayoutParams(-1, 0, 1f));
            }
        } else {
            int cols = n <= 1 ? 1 : 2;
            int cells = n == 1 ? 1 : 4;
            int rows = (cells + cols - 1) / cols;
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            root.addView(col, new FrameLayout.LayoutParams(-1, -1));
            for (int r = 0; r < rows; r++) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                col.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
                for (int c = 0; c < cols; c++) {
                    int i = r * cols + c;
                    final int idx = i;
                    View cell = i < n ? gridTile(i, () -> showFullscreen(idx))
                            : (i == n ? settingsCell() : new View(this));
                    row.addView(cell, new LinearLayout.LayoutParams(0, -1, 1f));
                }
            }
        }
        // avvio scaglionato: meno picchi di carico su TV e telecamere
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            handler.postDelayed(t::start, 300L * i);
        }
        for (Tile t : tiles) if (t.cam == cams.get(Math.min(focusIndex, n - 1))) t.frame.requestFocus();
    }

    void showFullscreen(int idx) {
        releaseAll();
        fullscreenIndex = idx;
        Cam cam = cams.get(idx);
        Tile t = makeTile(cam, cam.main, true, true);
        root.addView(t.frame, new FrameLayout.LayoutParams(-1, -1));
        t.frame.setBackgroundColor(Color.BLACK);
        t.frame.requestFocus();
        t.start();
        if (Config.LAYOUT_CYCLE.equals(cfg.layout)) handler.postDelayed(cycleTask, cfg.cycleSec * 1000L);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            openSettings();
            return true;
        }
        if (fullscreenIndex >= 0) {
            int n = cams.size();
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK:
                    if (Config.LAYOUT_CYCLE.equals(cfg.layout)) break; // in rotazione Indietro esce dall'app
                    focusIndex = fullscreenIndex;
                    showGrid();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    showFullscreen((fullscreenIndex + 1) % n);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    showFullscreen((fullscreenIndex + n - 1) % n);
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
