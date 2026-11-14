package it.aibox.telecamere;

import android.app.Activity;
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
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Griglia di telecamere RTSP per Android TV.
 * Configurazione: /sdcard/Android/data/it.aibox.telecamere/files/cameras.json (scritta via ADB dal Raspberry).
 * Telecomando: frecce = scegli riquadro, OK = schermo intero (flusso principale), Indietro = torna alla griglia.
 */
@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends Activity {

    static final class Cam {
        String name, sub, main;
    }

    /** Un riquadro: player + vista + stato di riconnessione. */
    final class Tile {
        final Cam cam;
        final String url;
        final boolean audio;
        final FrameLayout frame;
        final PlayerView view;
        final TextView status;
        ExoPlayer player;
        long lastPos = -1;
        int stuckChecks = 0;
        final Runnable reconnect = this::start;

        Tile(Cam cam, String url, boolean audio, FrameLayout frame, PlayerView view, TextView status) {
            this.cam = cam; this.url = url; this.audio = audio;
            this.frame = frame; this.view = view; this.status = status;
        }

        void start() {
            stop();
            status.setText("Connessione…");
            status.setVisibility(View.VISIBLE);
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
                @Override public void onPlayerError(PlaybackException e) {
                    scheduleReconnect("Errore: " + e.getErrorCodeName());
                }
            });
            view.setPlayer(player);
            RtspMediaSource src = new RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true) // TCP: più stabile sul Wi-Fi
                    .setTimeoutMs(8000)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(url)));
            player.setMediaSource(src);
            player.setPlayWhenReady(true);
            player.prepare();
            lastPos = -1; stuckChecks = 0;
        }

        void scheduleReconnect(String why) {
            status.setText(why + " – riprovo…");
            status.setVisibility(View.VISIBLE);
            handler.removeCallbacks(reconnect);
            handler.postDelayed(reconnect, 4000);
        }

        /** Chiamato ogni 5 s: se il video è fermo da ~15 s riconnette. */
        void watchdog() {
            if (player == null) return;
            if (player.getPlaybackState() == Player.STATE_IDLE) return; // già in riconnessione
            long pos = player.getCurrentPosition();
            if (pos == lastPos) {
                if (++stuckChecks >= 3) scheduleReconnect("Immagine ferma");
            } else stuckChecks = 0;
            lastPos = pos;
        }

        void stop() {
            handler.removeCallbacks(reconnect);
            if (player != null) {
                view.setPlayer(null);
                player.release();
                player = null;
            }
        }
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

    @Override
    protected void onStart() {
        super.onStart();
        String err = loadConfig();
        if (err != null) { showMessage(err); return; }
        if (fullscreenIndex >= 0) showFullscreen(fullscreenIndex); else showGrid();
        handler.postDelayed(watchdogTask, 5000);
    }

    @Override
    protected void onStop() {
        // in background libera subito decoder e connessioni
        handler.removeCallbacks(watchdogTask);
        releaseAll();
        super.onStop();
    }

    void releaseAll() {
        for (Tile t : tiles) t.stop();
        tiles.clear();
        root.removeAllViews();
    }

    String loadConfig() {
        cams.clear();
        File f = new File(getExternalFilesDir(null), "cameras.json");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0;
            while (n < buf.length) { int r = in.read(buf, n, buf.length - n); if (r < 0) break; n += r; }
            JSONArray arr = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8)).getJSONArray("cameras");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Cam c = new Cam();
                c.name = o.optString("name", "Telecamera " + (i + 1));
                c.sub = o.optString("sub", o.optString("main", ""));
                c.main = o.optString("main", c.sub);
                if (!c.sub.isEmpty()) cams.add(c);
            }
        } catch (Exception e) {
            return "Configurazione mancante o non valida:\n" + f.getAbsolutePath()
                    + "\n\n" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "\n\nCaricala dal Raspberry (ai-box/tools/tv-cameras).";
        }
        if (cams.isEmpty()) return "Nessuna telecamera in " + f.getAbsolutePath();
        return null;
    }

    void showMessage(String msg) {
        releaseAll();
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(48), dp(48), dp(48), dp(48));
        root.addView(tv, new FrameLayout.LayoutParams(-1, -1));
    }

    Tile makeTile(Cam cam, String url, boolean audio, boolean focusable) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundResource(R.drawable.tile_bg);
        int p = dp(4);
        frame.setPadding(p, p, p, p);
        frame.setFocusable(focusable);
        frame.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        PlayerView pv = new PlayerView(this);
        pv.setUseController(false);
        pv.setFocusable(false);
        pv.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        pv.setShutterBackgroundColor(Color.BLACK);
        pv.setKeepContentOnPlayerReset(true); // durante la riconnessione resta l'ultima immagine
        frame.addView(pv, new FrameLayout.LayoutParams(-1, -1));

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

        Tile t = new Tile(cam, url, audio, frame, pv, status);
        tiles.add(t);
        return t;
    }

    void showGrid() {
        releaseAll();
        fullscreenIndex = -1;
        int n = cams.size();
        int cols = n <= 1 ? 1 : 2;
        int rows = (n + cols - 1) / cols;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            col.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                View cell;
                if (i < n) {
                    final int idx = i;
                    Tile t = makeTile(cams.get(i), cams.get(i).sub, false, true);
                    t.frame.setOnClickListener(v -> showFullscreen(idx));
                    t.frame.setOnFocusChangeListener((v, has) -> { if (has) focusIndex = idx; });
                    cell = t.frame;
                } else {
                    cell = new View(this); // casella vuota (es. 3 telecamere su griglia 2x2)
                }
                row.addView(cell, new LinearLayout.LayoutParams(0, -1, 1f));
            }
        }
        // avvio scaglionato: meno picchi di carico su TV e telecamere
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            handler.postDelayed(t::start, 300L * i);
        }
        int f = Math.min(focusIndex, tiles.size() - 1);
        tiles.get(f).frame.requestFocus();
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
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (fullscreenIndex >= 0) {
            int n = cams.size();
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK:
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
