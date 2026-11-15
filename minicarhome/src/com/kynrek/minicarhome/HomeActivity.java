package com.kynrek.minicarhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.res.ColorStateList;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.net.Uri;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Home screen for the JoyeBox: Google Maps runs as a freeform window over the main area,
 * and a slim bar sits on the left, right, top or bottom. The bar can be compact and 1-column
 * default for a more Android Auto-like look.
 */
public class HomeActivity extends Activity {
    private static final String TAG = "BoxHome";
    private static final String MAPS = "com.google.android.apps.maps";
    private static final String WAZE = "com.waze";
    private static final int PICK_NONE = 0, PICK_SHORTCUT = 1, PICK_NAV = 2, PICK_ASSISTANT = 3, PICK_OFFLINE = 4;

    // Main window is a multi-window task with launch bounds.
    private static final int WINDOWING_MODE_FREEFORM = 5, WINDOWING_MODE_MULTI_WINDOW = 6;
    private static final int FREEFORM_CAPTION_DP = 24;
    private final java.util.Map<String, Rect> openedAt = new java.util.HashMap<>();
    private String reopening;
    private volatile String paneTarget;
    private long paneLaunchedAt;
    private static final long PANE_RESCUE_MS = 10000;
    private static final long MAPS_CHECK_MS = 2000;
    private static final long MAPS_RELAUNCH_MS = 5000;        // first MAPS_FAST_TRIES relaunches
    private static final long MAPS_RELAUNCH_SLOW_MS = 10000;     // afterwards, forever
    private static final int MAPS_FAST_TRIES = 18;
    private static final long BOOT_ONLINE_WAIT_MS = 90000;
    // Retry loop state: which package it is currently driving (offline app first, then main app).
    private String mapsTarget;
    private boolean onlineSeen, offlineUp;
    private int mapsAttempts;
    private long mapsLaunchedAt;
    private boolean mapsPending, mapsInterrupted, topResumed, editing;
    private int mapsGen;
    private String paneApp;

    private static final java.util.Map<String, String> TRAMPOLINES = java.util.Collections.singletonMap(
            "com.bambuna.podcastaddict/com.bambuna.podcastaddict.activity.LauncherActivity",
            "com.bambuna.podcastaddict/com.bambuna.podcastaddict.activity.PodcastListActivity");

    // Default to 1 column, minimum 1, maximum 4.
    private static final int MIN_COLUMNS = 1, MAX_COLUMNS = 4, DEFAULT_COLUMNS = 1;
    private static final int COLUMN_DP = 84;
    private static final int PAD_EDGE = 14, PAD_INNER = 6;
    private int columns = DEFAULT_COLUMNS;

    // Position values: "left", "right", "top", "bottom"
    private String panelPos = "bottom";
    private boolean settingsOpen;

    private boolean isCompactPanel() {
        return "top".equals(panelPos) || "bottom".equals(panelPos);
    }

    private TextView mapSlot;
    private static final int MAX_RECENT = 10;
    private static final String[] SHORTCUTS = {
            MAPS,
            "app.symfonik.music.player",
            "com.bambuna.podcastaddict",
            "com.audible.application",
            "com.audiobookshelf.app",
            "com.android.vending",
            "com.applepie.setting",
            "com.android.settings",
            "local.boxtools/local.boxtools.WirelessDebugActivity",
            "org.codeaurora.dialer",
            "com.android.mms",
    };

    private static final int BG = 0xFF000000;
    private static final int CARD = 0xFF1B2428;
    private static final int TEXT = 0xFFE8EEF0;
    private static final int DIM = 0xFF9AA8AD;
    private static final int ACCENT = 0xFF6FD3C0;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PackageManager pm;
    private WindowManager wm;
    private SharedPreferences prefs;
    private FrameLayout panel;
    private BrowserView browser;
    private boolean panelShown, browserShown, started;
    private TextView mediaTitle, mediaArtist, playButton, wifiLabel, cellLabel;
    private SignalBars wifiBars, cellBars;
    private TextView weatherTemp, weatherRange;
    private static final long WEATHER_REFRESH_MS = 15 * 60_000;
    private static final long WEATHER_RETRY_MS = 60_000;
    private static final long SIGNAL_REFRESH_MS = 5000;
    private ImageView mediaArt;
    private LinearLayout progressRow;
    private WavyProgress mediaProgress;
    private TextView mediaRemaining;
    private MediaSessionManager sessions;
    private MediaController controller;

    private MediaMetadata metadata;
    private PlaybackState playback;
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
    @Override
public void onMetadataChanged(MediaMetadata m) {
    metadata = m;
    showMedia();

    if (compactMediaControls != null && compactTrackInfo != null) {
        main.removeCallbacks(restoreCompactMediaControls);
        compactMediaControls.setVisibility(View.GONE);
        compactTrackInfo.setVisibility(View.VISIBLE);
        if (repositionCompactMedia != null) {
            main.post(repositionCompactMedia);
        }
        main.postDelayed(restoreCompactMediaControls, 3000);
    }
}

    @Override
    public void onPlaybackStateChanged(PlaybackState s) {
        playback = s;
        showMedia();
    }

    @Override
    public void onSessionDestroyed() {
        pickController();
    }
};

    private final List<MediaController> watched = new ArrayList<>();
    private final MediaController.Callback watcher = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) {
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) pickController();
        }
    };
    
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = list -> pickController();
    private View compactMediaControls;
    private View compactTrackInfo;
private final Runnable restoreCompactMediaControls = () -> {
    if (compactMediaControls != null) compactMediaControls.setVisibility(View.VISIBLE);
    if (compactTrackInfo != null) compactTrackInfo.setVisibility(View.GONE);
    if (HomeActivity.this.repositionCompactMedia != null) HomeActivity.this.repositionCompactMedia.run();
};

    
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        pm = getPackageManager();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("home", MODE_PRIVATE);

        columns = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, prefs.getInt("columns", DEFAULT_COLUMNS)));

        String savedPos = prefs.getString("panel_pos", "bottom");
        panelPos = "left".equals(savedPos) || "right".equals(savedPos) || "top".equals(savedPos) || "bottom".equals(savedPos)
                ? savedPos : "bottom";

        setContentView(buildBackdrop());

        android.content.IntentFilter apps = new android.content.IntentFilter();
        apps.addAction(Intent.ACTION_PACKAGE_ADDED);
        apps.addAction(Intent.ACTION_PACKAGE_REPLACED);
        apps.addAction(Intent.ACTION_PACKAGE_REMOVED);
        apps.addAction(Intent.ACTION_PACKAGE_CHANGED);
        apps.addDataScheme("package");
        registerReceiver(appsChanged, apps);

        panel = new FrameLayout(this);
        panel.addView(buildPanel());
        main.postDelayed(this::ensureMaps, 800);
        applySavedDensity();
        syncStatusIcons();
    }

    private final android.content.BroadcastReceiver appsChanged = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            iconCache.clear();
            labelCache.clear();
            main.removeCallbacks(appsRebuild);
            main.postDelayed(appsRebuild, 500);
        }
    };
    private final Runnable appsRebuild = () -> rebuildPanel();

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(appsChanged);
        windowOps.shutdown();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String target = intent.getStringExtra("launch");
        ComponentName cn = target == null ? null : ComponentName.unflattenFromString(target);
        if (cn != null) {
            main.postDelayed(() -> launch(cn), 300);
            return;
        }
        hideBrowser();
        main.postDelayed(this::ensureMaps, 250);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        showPanel();
        if (mapsInterrupted) {
            mapsInterrupted = false;
            ensureMaps();
        }
        main.removeCallbacks(signalTick);
        main.post(signalTick);
        showWeather();
        main.removeCallbacks(weatherTick);
        main.post(weatherTick);
        sessions = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listenerName(), main);
            pickController();
        } catch (SecurityException e) {
            mediaTitle.setText("Now playing needs notification access");
            mediaArtist.setText("");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        mapsInterrupted = mapsPending;
        mapsPending = false;
        main.removeCallbacks(mapsRetry);
        main.removeCallbacks(signalTick);
        main.removeCallbacks(weatherTick);
        main.removeCallbacks(progressTick);
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        setListening(false);
        hideBrowser();
        hidePanel();
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsListener);
        watch(Collections.emptyList());
        setController(null);
        rescuePaneApp();
    }

    private void rescuePaneApp() {
        if (paneTarget == null || SystemClock.uptimeMillis() - paneLaunchedAt > PANE_RESCUE_MS) return;
        paneLaunchedAt = 0;
        String pkg = paneTarget;
        java.util.Set<String> apps = new java.util.HashSet<>(handoffApps());
        if (!apps.add(pkg)) return;
        prefs.edit().putStringSet("handoff_apps", apps).apply();
        Log.i(TAG, pkg + " hands off to a new task");
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null || !windowsReady()) return;
        windowOps.execute(() -> {
            TaskRef task = findTask(pkg);
            if (task == null || !removeTask(task)) return;
            main.postDelayed(() -> startHandoffApp(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), pkg), 600);
        });
    }

    private void startHandoffApp(Intent i, String pkg) {
        if (!windowsReady()) {
            paneLaunchedAt = 0;
            try { startActivity(i); } catch (RuntimeException e) { Log.w(TAG, "launch failed", e); }
            return;
        }
        startPane(i, WINDOWING_MODE_FREEFORM);
        Rect bounds = captionedBounds();
        for (long delay : new long[]{1500, 3000, 5000}) {
            main.postDelayed(() -> { if (pkg.equals(paneTarget)) fitTask(pkg, bounds); }, delay);
        }
    }

    private Rect captionedBounds() {
        Rect r = mapsBounds();
        r.bottom -= sysDp(FREEFORM_CAPTION_DP);
        return r;
    }

    private static final Uri BOX_SHELL = Uri.parse("content://local.boxtools.shell");
    private final java.util.concurrent.ExecutorService windowOps =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "window-ops"));
    private static final java.util.regex.Pattern TASK_LINE = java.util.regex.Pattern.compile(
            "taskId=(\\d+): (\\S+) bounds=\\S+ userId=\\d+ visible=(true|false)(?: topActivity=ComponentInfo\\{([^}]+)\\})?");

    private static final class TaskRef {
        final int id;
        final ComponentName top;
        final boolean visible;

        TaskRef(int id, ComponentName top, boolean visible) {
            this.id = id;
            this.top = top;
            this.visible = visible;
        }
    }

    private boolean windowsReady() {
        return pm.resolveContentProvider("local.boxtools.shell", 0) != null;
    }

    private Bundle boxCall(String method, String arg, Bundle extras) {
        try {
            Bundle b = getContentResolver().call(BOX_SHELL, method, arg, extras);
            if (b != null && b.containsKey("error")) {
                Log.w(TAG, "Box Tools " + method + ": " + b.getString("error"));
                return null;
            }
            return b;
        } catch (RuntimeException e) {
            Log.w(TAG, "Box Tools " + method + " failed", e);
            return null;
        }
    }

    private String shell(String command) {
        Bundle b = boxCall("exec", command, null);
        String out = b == null ? null : b.getString("out");
        return out == null ? "" : out;
    }

    private TaskRef findTask(String pkg) {
        return findTask(shell("am stack list"), pkg);
    }

    private Boolean taskVisible(String pkg) {
        String list = shell("am stack list");
        if (list.isEmpty()) return null;
        TaskRef t = findTask(list, pkg);
        return t != null && t.visible;
    }

    private static TaskRef findTask(String list, String pkg) {
        java.util.regex.Matcher m = TASK_LINE.matcher(list);
        while (m.find()) {
            ComponentName top = m.group(4) != null ? ComponentName.unflattenFromString(m.group(4)) : null;
            if (top == null) top = ComponentName.unflattenFromString(m.group(2));
            if (top != null && pkg.equals(top.getPackageName())) {
                return new TaskRef(Integer.parseInt(m.group(1)), top, "true".equals(m.group(3)));
            }
        }
        return null;
    }

    private boolean portraitOnly(TaskRef task) {
        String dump = shell("dumpsys activity activities");
        int at = dump.indexOf(" #" + task.id + " ");
        if (at >= 0) {
            int next = dump.indexOf("* Task{", at);
            String block = dump.substring(at, next < 0 ? dump.length() : next);
            if (block.contains("resizeMode=RESIZE_MODE_UNRESIZEABLE")) return true;
        }
        try {
            int o = pm.getActivityInfo(task.top, 0).screenOrientation;
            return o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                    || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    private void fitTask(String pkg, Rect captioned) {
        Rect pane = mapsBounds();
        windowOps.execute(() -> {
            if (!pkg.equals(paneTarget)) return;
            TaskRef task = findTask(pkg);
            if (task == null) return;
            if (portraitOnly(task)) {
                setTaskMode(task, WINDOWING_MODE_FREEFORM, captioned);
                return;
            }
            setTaskMode(task, WINDOWING_MODE_MULTI_WINDOW, pane);
            new Thread(() -> {
                boolean before = false;
                for (long wait : new long[]{1200, 1500, 1500, 2000, 2000}) {
                    SystemClock.sleep(wait);
                    if (!pkg.equals(paneTarget)) return;
                    boolean now = letterboxed(pkg);
                    boolean twice = now && before;
                    before = now;
                    if (!twice) continue;
                    Log.i(TAG, pkg + " is letterboxed in the pane; using a freeform window");
                    TaskRef t = findTask(pkg);
                    if (t != null && pkg.equals(paneTarget)) setTaskMode(t, WINDOWING_MODE_FREEFORM, captioned);
                    return;
                }
            }, "letterbox-check").start();
        });
    }

    private boolean setTaskMode(TaskRef task, int mode, Rect bounds) {
        Bundle extras = new Bundle();
        extras.putInt("taskId", task.id);
        extras.putInt("mode", mode);
        extras.putParcelable("bounds", bounds);
        return boxOk("windowMode", extras);
    }

    private boolean removeTask(TaskRef task) {
        Bundle extras = new Bundle();
        extras.putInt("taskId", task.id);
        return boxOk("removeTask", extras);
    }

    private boolean boxOk(String method, Bundle extras) {
        Bundle b = boxCall(method, null, extras);
        String out = b == null ? null : b.getString("out");
        if ("ok".equals(out)) return true;
        if (out != null) Log.w(TAG, method + " " + extras + ": " + out);
        return false;
    }

    private boolean letterboxed(String pkg) {
        String out = shell("dumpsys activity activities");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\* Hist +#\\d+: ActivityRecord\\{\\S+ u0 " + java.util.regex.Pattern.quote(pkg) + "/").matcher(out);
        int at = m.find() ? m.start() + 1 : -1;
        if (at < 0) return false;
        int next = out.indexOf("* Hist", at);
        String block = out.substring(at, next < 0 ? out.length() : next);
        return block.contains("areBoundsLetterboxed=true");
    }

    private java.util.Set<String> handoffApps() {
        return prefs.getStringSet("handoff_apps", java.util.Collections.emptySet());
    }

    private Rect mapsBounds() {
        Rect screen = wm.getMaximumWindowMetrics().getBounds();

        if ("top".equals(panelPos) || "bottom".equals(panelPos)) {
            int barH = panelHeight();
            int top = "top".equals(panelPos) ? barH : 0;
            int bottom = "bottom".equals(panelPos) ? screen.height() - barH : screen.height();
            return new Rect(0, top, screen.width(), bottom);
        }

        int mapsWidth = screen.width() - panelWidth();
        int left = "left".equals(panelPos) ? screen.width() - mapsWidth : 0;
        return new Rect(left, 0, left + mapsWidth, screen.height());
    }

    private void resizeMaps(Rect old) { ensureMaps(); }
    private void resizeMaps(Rect old, String oldApp) { ensureMaps(); }

    private void ensureMaps() {
        mapsAttempts = 0;
        mapsPending = true;
        mapsGen++;
        onlineSeen = false;
        offlineUp = false;
        mapsTarget = pickMapsTarget();
        paneTarget = mapsTarget;
        main.removeCallbacks(mapsRetry);
        main.post(mapsRetry);
    }

    /** "none", "wait" or "app" (default: wait). A saved app that is no longer installed counts as wait. */
    private String offlineMode() {
        String v = prefs.getString("offline_app", "wait");
        if ("none".equals(v)) return "none";
        if ("wait".equals(v)) return "wait";
        return pm.getLaunchIntentForPackage(v) != null ? "app" : "wait";
    }

    private String offlineApp() {
        return "app".equals(offlineMode()) ? prefs.getString("offline_app", null) : null;
    }

    /**
     * Offline app while there is no internet; the main app as soon as internet has been seen
     * (and from then on for this round, so the offline app is forgotten).
     */
    private String pickMapsTarget() {
        String mainApp = navApp(), off = offlineApp();
        if (off == null || off.equals(mainApp) || onlineSeen) return mainApp;
        if (online()) { onlineSeen = true; return mainApp; }
        return off;
    }

    private final Runnable mapsRetry = new Runnable() {
        @Override public void run() {
            if (!mapsPending) return;
            if (paneTarget != null && !paneTarget.equals(mapsTarget)) {
                mapsPending = false;
                return;
            }
            String target = pickMapsTarget();
            if (!target.equals(mapsTarget)) {
                // offline app -> main app (or main app changed): start the attempts over
                Log.i(TAG, "switching window target " + mapsTarget + " -> " + target);
                mapsTarget = target;
                mapsAttempts = 0;
                offlineUp = false;
                mapsGen++;
                paneTarget = target;
            }
            if (offlineUp) {
                // Offline app is showing: no more window checks, just wait for internet.
                main.postDelayed(this, 1000);
                return;
            }
            boolean booting = SystemClock.elapsedRealtime() < BOOT_ONLINE_WAIT_MS;
            boolean waitForNet = mapsAttempts == 0 && booting && "wait".equals(offlineMode())
                    && target.equals(navApp()) && !online();
            if (!getSystemService(android.os.UserManager.class).isUserUnlocked() || waitForNet) {
                main.postDelayed(this, 500);
                return;
            }
            if (!windowsReady()) {
                mapsStep(mapsAttempts > 0 && !topResumed);
                return;
            }

            int gen = mapsGen;
            windowOps.execute(() -> {
                Boolean up = taskVisible(target);
                main.post(() -> {
                    if (gen == mapsGen && mapsPending) mapsStep(up != null ? up : mapsAttempts > 0 && !topResumed);
                });
            });
        }
    };

    private void mapsStep(boolean up) {
        if (paneTarget != null && !paneTarget.equals(mapsTarget)) {
            mapsPending = false;
            return;
        }
        if (up) {
            Log.i(TAG, mapsTarget + " window is up after " + mapsAttempts + " tries");
            if (!mapsTarget.equals(navApp())) {
                // Offline app is up: don't recheck it or relaunch it. Only watch for internet,
                // then the loop switches to the main app.
                offlineUp = true;
                main.postDelayed(mapsRetry, 1000);
                return;
            }
            mapsPending = false;
            return;
        }
        // Never give up: relaunch every 5s for the first 18 tries, then every 10s forever.
        long wait = mapsAttempts <= 0 ? 0
                : mapsAttempts <= MAPS_FAST_TRIES ? MAPS_RELAUNCH_MS : MAPS_RELAUNCH_SLOW_MS;
        if (mapsAttempts > 0 && SystemClock.uptimeMillis() - mapsLaunchedAt < wait) {
            main.postDelayed(mapsRetry, MAPS_CHECK_MS);
            return;
        }
        if (mapsAttempts < Integer.MAX_VALUE) mapsAttempts++;
        Log.i(TAG, "opening " + mapsTarget + " window, try " + mapsAttempts + " (" + SystemClock.elapsedRealtime() / 1000
                + " s after boot)");
        launchPackage(mapsTarget);
        mapsLaunchedAt = SystemClock.uptimeMillis();
        main.postDelayed(mapsRetry, MAPS_CHECK_MS);
    }

    @Override
    public void onTopResumedActivityChanged(boolean top) {
        super.onTopResumedActivityChanged(top);
        topResumed = top;
    }

    private boolean online() {
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.Network net = cm.getActiveNetwork();
            if (net == null) return false;
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(net);
            return nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void goHome() {
        hideBrowser();
        settingsOpen = false;
        paneApp = null;
        rebuildPanel();
        launchMaps();
    }

    private void launchMaps() { launchPackage(navApp()); }

    private void launchPackage(String pkg) {
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            Log.i(TAG, pkg + " not available yet");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startInPane(i, pkg);
    }

    private void startInPane(Intent i, String pkg) {
        if (pkg.equals(reopening)) return;
        paneTarget = pkg;
        paneLaunchedAt = SystemClock.uptimeMillis();
        if (handoffApps().contains(pkg)) { startHandoffApp(i, pkg); return; }
        Rect want = mapsBounds(), had = openedAt.get(pkg);
        startPane(i, WINDOWING_MODE_FREEFORM);
        if (had == null || had.equals(want)) {
            dropCaption(i, pkg, want);
            return;
        }
        Log.i(TAG, "reopening " + pkg + " at " + want + " (was " + had + ")");
        reopening = pkg;
        hidePanel();
        main.postDelayed(() -> sendBroadcast(new Intent("local.boxtools.action.CLOSE_WINDOW")
                .setPackage("local.boxtools").putExtra("package", pkg)), 600);
        main.postDelayed(() -> {
            showPanel();
            reopening = null;
            if (!pkg.equals(paneTarget)) return;
            startPane(i, WINDOWING_MODE_FREEFORM);
            dropCaption(i, pkg, want);
        }, 3000);
    }

    private void dropCaption(Intent i, String pkg, Rect bounds) {
        openedAt.put(pkg, bounds);
        main.postDelayed(() -> {
            if (pkg.equals(paneTarget) && !pkg.equals(reopening)) startPane(i, WINDOWING_MODE_MULTI_WINDOW);
        }, 1000);
        main.postDelayed(() -> {
            if (pkg.equals(paneTarget) && !pkg.equals(reopening) && windowsReady()) fitTask(pkg, captionedBounds());
        }, 2500);
    }

    private void startPane(Intent i, int mode) {
        try {
            startActivity(i, paneOptions(mode));
        } catch (RuntimeException e) {
            Log.w(TAG, "launch failed", e);
            Toast.makeText(this, "Can't open: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private Bundle paneOptions(int mode) {
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchBounds(mapsBounds());
        try {
            Method m = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
            m.invoke(o, mode);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "windowed launch unavailable", e);
        }
        return o.toBundle();
    }

    private boolean opensInPane(ComponentName cn) {
        return prefs.getBoolean("apps_in_pane", true) && !"local.boxtools".equals(cn.getPackageName());
    }

    private void launchInPane(Intent i, String pkg) {
        paneApp = pkg;
        main.postDelayed(() -> startInPane(i, pkg), 400);
    }

    private WindowManager.LayoutParams overlayParams(int width, int height, int gravity, boolean focusable) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!focusable) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags, PixelFormat.OPAQUE);
        lp.gravity = gravity;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        return lp;
    }

    private void showPanel() {
        if (panelShown) return;
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "MiniCarHome needs the 'display over other apps' permission", Toast.LENGTH_LONG).show();
            return;
        }
        wm.addView(panel, panelParams());
        panelShown = true;
    }

    private WindowManager.LayoutParams panelParams() {
        if (settingsOpen) {
            return overlayParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START, false);
        }
        if ("left".equals(panelPos) || "right".equals(panelPos)) {
            int g = "left".equals(panelPos) ? Gravity.TOP | Gravity.START : Gravity.TOP | Gravity.END;
            return overlayParams(panelWidth(), WindowManager.LayoutParams.MATCH_PARENT, g, false);
        }
        int g = "top".equals(panelPos) ? Gravity.TOP | Gravity.START : Gravity.BOTTOM | Gravity.START;
        return overlayParams(WindowManager.LayoutParams.MATCH_PARENT, panelHeight(), g, false);
    }

    private int panelWidth() {
        int max = wm.getMaximumWindowMetrics().getBounds().width() * 3 / 5;
        return Math.min(max, dp(PAD_EDGE) + dp(PAD_INNER) + columns * dp(COLUMN_DP));
    }

    private int panelHeight() {
        int rowCount = Math.max(1, Math.min(MAX_COLUMNS, columns));
        return dp(36 * rowCount + 8);
    }

    private void setColumns(int n) {
        n = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, n));
        if (n == columns) return;
        Rect old = mapsBounds();
        columns = n;
        prefs.edit().putInt("columns", n).apply();
        applyLayout(old);
    }

    private void setPanelPos(String pos) {
        if (pos == null) pos = "bottom";
        if (pos.equals(panelPos)) return;
        Rect old = mapsBounds();
        panelPos = pos;
        prefs.edit().putString("panel_pos", pos).apply();
        applyLayout(old);
    }

    private String navApp() {
        String app = prefs.getString("nav_app", MAPS);
        return pm.getLaunchIntentForPackage(app) != null ? app : MAPS;
    }

    private void setNavApp(String pkg) {
        if (pkg.equals(navApp())) return;
        Rect old = mapsBounds();
        String oldApp = navApp();
        prefs.edit().putString("nav_app", pkg).apply();
        rebuildPanel();
        updateMapSlot();
        resizeMaps(old, oldApp);
    }

    private String assistantMode() {
        String mode = prefs.getString("assistant_mode", "builtin");
        return "app".equals(mode) && assistantApp() == null ? "builtin" : mode;
    }

    private String assistantApp() {
        String app = prefs.getString("assistant_app", null);
        return app != null && pm.getLaunchIntentForPackage(app) != null ? app : null;
    }

    private void setPref(String key, boolean on) {
        prefs.edit().putBoolean(key, on).apply();
        rebuildPanel();
    }

    private void setString(String key, String value) {
        prefs.edit().putString(key, value).apply();
        rebuildPanel();
    }

    private void setSignalPlace(String place) {
        prefs.edit().putString("signals", place).apply();
        syncStatusIcons();
        rebuildPanel();
    }

    private static final List<String> STATUS_SIGNAL_ICONS = java.util.Arrays.asList("wifi", "mobile");

    private void syncStatusIcons() {
        boolean hide = "top".equals(signalPlace());
        try {
            String saved = Settings.Secure.getString(getContentResolver(), "icon_blacklist");
            java.util.Set<String> icons = new java.util.LinkedHashSet<>();
            if (saved != null) for (String icon : saved.split(",")) if (!icon.trim().isEmpty()) icons.add(icon.trim());
            boolean changed = hide ? icons.addAll(STATUS_SIGNAL_ICONS) : icons.removeAll(STATUS_SIGNAL_ICONS);
            if (changed) Settings.Secure.putString(getContentResolver(), "icon_blacklist", TextUtils.join(",", icons));
        } catch (SecurityException e) {
            Log.w(TAG, "can't change the status bar icons without WRITE_SECURE_SETTINGS", e);
        }
    }

    private void applyLayout(Rect oldMaps) {
        rebuildPanel();
        updateMapSlot();
        pickController();
        resizeMaps(oldMaps);
    }

    private List<String> shortcuts() {
        List<String> out = new ArrayList<>();
        String saved = prefs.getString("shortcuts", null);
        if (saved == null) Collections.addAll(out, SHORTCUTS);
        else if (!saved.isEmpty()) Collections.addAll(out, saved.split("\\|"));
        return out;
    }

    private void saveShortcuts(List<String> list) {
        prefs.edit().putString("shortcuts", TextUtils.join("|", list)).apply();
        rebuildPanel();
    }

    private void moveShortcut(int i, int by) {
        List<String> list = shortcuts();
        int j = i + by;
        if (i < 0 || i >= list.size() || j < 0 || j >= list.size()) return;
        Collections.swap(list, i, j);
        saveShortcuts(list);
    }

    private void removeShortcut(int i) {
        List<String> list = shortcuts();
        if (i < 0 || i >= list.size()) return;
        list.remove(i);
        saveShortcuts(list);
    }

    private void addShortcut(String entry) {
        List<String> list = shortcuts();
        if (!list.contains(entry)) list.add(entry);
        saveShortcuts(list);
    }

    private void addShortcut(ComponentName cn) {
        List<String> list = shortcuts();
        String flat = cn.flattenToString();
        if (!list.contains(flat) && !list.contains(cn.getPackageName())) list.add(flat);
        saveShortcuts(list);
    }

    private void hidePanel() {
        if (!panelShown) return;
        try { wm.removeView(panel); } catch (RuntimeException ignored) { }
        panelShown = false;
    }

    private void showBrowser() { openBrowser(PICK_NONE); }
    private void showPicker() { openBrowser(PICK_SHORTCUT); }
    private void showNavPicker() { openBrowser(PICK_NAV); }
    private void showAssistantPicker() { openBrowser(PICK_ASSISTANT); }
    private void showOfflinePicker() { openBrowser(PICK_OFFLINE); }

    private void openBrowser(int pick) {
        if (browserShown || !Settings.canDrawOverlays(this)) return;
        browser = new BrowserView(this, pick);
    
        Rect screen = wm.getMaximumWindowMetrics().getBounds();
        boolean horizontalPanel = "top".equals(panelPos) || "bottom".equals(panelPos);    

        int width = horizontalPanel ? screen.width() : screen.width() - panelWidth();
        int gravity = horizontalPanel
            ? Gravity.TOP | Gravity.START
            : ("left".equals(panelPos) ? Gravity.TOP | Gravity.END : Gravity.TOP | Gravity.START);

        wm.addView(browser, overlayParams(
                width, WindowManager.LayoutParams.MATCH_PARENT, gravity, true));
        browserShown = true;
    }

    private void hideBrowser() {
        if (!browserShown) return;
        browser.hideKeyboard();
        try { wm.removeView(browser); } catch (RuntimeException ignored) { }
        browserShown = false;
        browser = null;
    }

    private View buildBackdrop() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);

        mapSlot = new TextClock(this);
        applyMapSlotClock();
        mapSlot.setGravity(Gravity.CENTER);
        mapSlot.setBackgroundColor(BG);
        mapSlot.setOnClickListener(v -> launchMaps());

        root.addView(mapSlot);
        main.post(this::updateMapSlot);
        return root;
    }

    /** Big white digital clock shown behind the main app window. */
    private void applyMapSlotClock() {
        TextClock c = (TextClock) mapSlot;
        String f = prefs.getBoolean("clock_24h", true) ? "H:mm" : "h:mm";
        c.setFormat12Hour(f);
        c.setFormat24Hour(f);
        c.setTextColor(0xFFFFFFFF);
        c.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        c.setSingleLine(true);
        c.setIncludeFontPadding(false);
        c.setAutoSizeTextTypeUniformWithConfiguration(24, 400, 2, TypedValue.COMPLEX_UNIT_SP);
    }

    private void updateMapSlot() {
        if (mapSlot == null) return;
        applyMapSlotClock();
        if ("top".equals(panelPos) || "bottom".equals(panelPos)) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMargins(dp(8), dp(8), dp(8), dp(8));
            mapSlot.setLayoutParams(lp);
            return;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                wm.getMaximumWindowMetrics().getBounds().width() - panelWidth() - dp(12), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(8), dp(8), dp(4), dp(8));
        mapSlot.setLayoutParams(lp);
        ((LinearLayout) mapSlot.getParent()).setGravity("left".equals(panelPos) ? Gravity.END : Gravity.START);
    }

    private View buildPanel() {
        if ("top".equals(panelPos) || "bottom".equals(panelPos)) {
                if (settingsOpen) {
                    ScrollView scroller = new ScrollView(this);
                    scroller.setFillViewport(true);
                    scroller.setVerticalScrollBarEnabled(false);
                    scroller.setBackgroundColor(BG);
                    scroller.setPadding(dp(12), dp(12), dp(12), dp(12));
                    scroller.addView(buildSettings(columns));
                    return scroller;
                }

            return buildCompactBar();
        }
        return buildSidePanel();
    }
    
    private View buildCompactBar() {
    FrameLayout bar = new FrameLayout(this);
    bar.setBackgroundColor(BG);
    bar.setPadding(dp(4), dp(4), dp(4), dp(4));

    LinearLayout icons = new LinearLayout(this);
    icons.setOrientation(LinearLayout.HORIZONTAL);
    icons.setGravity(Gravity.CENTER_VERTICAL);
    icons.setPadding(0, 0, 0, 0);

List<View> userTiles = new ArrayList<>();
List<View> compactIcons = new ArrayList<>();

    // Fixed icons: app drawer, optional assistant, then home app.
LinearLayout.LayoutParams drawerParams = new LinearLayout.LayoutParams(
        dp(36), ViewGroup.LayoutParams.MATCH_PARENT);
drawerParams.rightMargin = dp(2);
final View drawerTile = appsGridTile("All apps", v -> showBrowser());
icons.addView(drawerTile, drawerParams);

if (prefs.getBoolean("show_mic", false)) {
    FrameLayout micSlot = new FrameLayout(this);
    micSlot.addView(assistantButton(), new FrameLayout.LayoutParams(
            dp(36), dp(36), Gravity.CENTER));

    LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(
            dp(36), ViewGroup.LayoutParams.MATCH_PARENT);
    micParams.rightMargin = dp(2);
    icons.addView(micSlot, micParams);
    compactIcons.add(micSlot);
}

View home = appTile(navApp());
home.setOnClickListener(v -> goHome());
LinearLayout.LayoutParams homeParams = new LinearLayout.LayoutParams(
        dp(36), ViewGroup.LayoutParams.MATCH_PARENT);
homeParams.rightMargin = dp(2);
icons.addView(home, homeParams);
compactIcons.add(home);

// User shortcuts follow the fixed icons.
List<String> list = shortcuts();
for (String entry : list) {
    if (navApp().equals(entry.split("/")[0])) continue;

    View tile = null;
    if (isWazeEntry(entry)) {
        tile = wazeTile(entry);
    } else if (entry.contains("/")) {
        tile = activityTile(ComponentName.unflattenFromString(entry));
    } else if (pm.getLaunchIntentForPackage(entry) != null) {
        tile = appTile(entry);
    }

    if (tile != null) {
        if (editing) tile = editable(tile, userTiles.size(), list.size());
        userTiles.add(tile);
        compactIcons.add(tile);

        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(
                dp(36), ViewGroup.LayoutParams.MATCH_PARENT);
        tileParams.rightMargin = dp(2);
        icons.addView(tile, tileParams);
    }
}

if (editing) {
    View add = iconTile("+", "Add", v -> showPicker());
    LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
            dp(36), ViewGroup.LayoutParams.MATCH_PARENT);
    addParams.rightMargin = dp(2);
    icons.addView(add, addParams);
    compactIcons.add(add);

    View done = iconTile("✓", "Done", v -> {
        editing = false;
        rebuildPanel();
    });
    icons.addView(done, new LinearLayout.LayoutParams(
            dp(36), ViewGroup.LayoutParams.MATCH_PARENT));
    compactIcons.add(done);
}

    // Right-side indicators and weather.
    LinearLayout rightGroup = new LinearLayout(this);
    rightGroup.setOrientation(LinearLayout.HORIZONTAL);
    rightGroup.setGravity(Gravity.CENTER_VERTICAL);

    String place = signalPlace();
    if ("bottom".equals(place) || "top".equals(place)) {
        rightGroup.addView(signalsRowCompact(),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    rightGroup.addView(buildCompactWeather(),
            new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

    // Media is centered in the bar when possible, and clamped between the
    // icons and right-side group to prevent overlap.
    FrameLayout mediaSlot = new FrameLayout(this);
    mediaSlot.addView(buildCompactMedia(), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER));

    boolean showMedia = prefs.getBoolean("show_media", true);
    mediaSlot.setVisibility(showMedia ? View.INVISIBLE : View.GONE);

    // 1. Add mediaSlot FIRST so it sits underneath icons and rightGroup in Z-order
    FrameLayout.LayoutParams mediaParams = new FrameLayout.LayoutParams(
            dp(240),
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.LEFT | Gravity.CENTER_VERTICAL);
    bar.addView(mediaSlot, mediaParams);

    // 2. Add icons and rightGroup AFTER mediaSlot so their touch targets always take priority
    FrameLayout.LayoutParams iconsParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.LEFT | Gravity.CENTER_VERTICAL);
    bar.addView(icons, iconsParams);

    FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.RIGHT | Gravity.CENTER_VERTICAL);
    bar.addView(rightGroup, rightParams);

    repositionCompactMedia = () ->
            positionCompactMedia(bar, mediaSlot, drawerTile, rightGroup, compactIcons);

    View.OnLayoutChangeListener relayout = (v, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) -> {
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
                || left != oldLeft) {
            // Never reposition inside a layout pass: a layout request made here is
            // swallowed, so the new width/margin would not be applied until a later
            // pass. Post it so it runs after the pass has finished.
            v.removeCallbacks(repositionCompactMedia);
            v.post(repositionCompactMedia);
        }
    };
    bar.addOnLayoutChangeListener(relayout);
    // Weather text and icon count can change width without the bar itself changing.
    rightGroup.addOnLayoutChangeListener(relayout);
    icons.addOnLayoutChangeListener(relayout);
    bar.post(repositionCompactMedia);

    return bar;
}

private Runnable repositionCompactMedia;
    
private void positionCompactMedia(
        FrameLayout bar,
        View mediaSlot,
        View drawer,
        View rightGroup,
        List<View> compactIcons) {

    if (mediaSlot.getVisibility() == View.GONE || bar.getWidth() == 0
            || drawer.getWidth() == 0) return;

    final int gap = dp(2);
    final int barWidth = bar.getWidth();
    int[] barLoc = new int[2];
    bar.getLocationOnScreen(barLoc);

    // Left limit: right edge of the app drawer icon (+gap). The same distance is
    // reserved on the right edge so the text mode stays symmetric. The right-side
    // group (signals, weather, clock) is a hard limit with a 2dp gap.
    int[] loc = new int[2];
    drawer.getLocationOnScreen(loc);
    int drawerRight = loc[0] - barLoc[0] + drawer.getWidth();
    int leftLimit = drawerRight + gap;
    int weatherLimit = rightGroup.getLeft() - gap;

    boolean showingTrackInfo = compactTrackInfo != null
            && compactTrackInfo.getVisibility() == View.VISIBLE;

    int width;
    int left;

    if (showingTrackInfo && mediaTitle != null && mediaArtist != null) {
        // Track info mode: expand as far as needed, always centered on the bar.
        float textWidth = Math.max(
                mediaTitle.getPaint().measureText(mediaTitle.getText().toString()),
                mediaArtist.getPaint().measureText(mediaArtist.getText().toString()));

        // Album art (26dp) + spacing/padding (6dp + 8dp + 4dp) + text width + safety buffer.
        int desiredWidth = dp(26 + 6 + 8 + 4 + 8) + (int) Math.ceil(textWidth);

        int rightLimit = Math.min(barWidth - leftLimit, weatherLimit);
        int half = Math.min(barWidth / 2 - leftLimit, rightLimit - barWidth / 2);
        if (half <= 0) {
            mediaSlot.setVisibility(View.INVISIBLE);
            return;
        }
        width = Math.min(desiredWidth, 2 * half);
        left = (barWidth - width) / 2;
    } else {
        // Button mode: fixed width. Stay centered while there is room, otherwise
        // slide right away from the icons until the gap to the weather is reached.
        int available = weatherLimit - leftLimit;
        if (available <= 0) {
            mediaSlot.setVisibility(View.INVISIBLE);
            return;
        }
        // Album art + horizontal padding + three compact playback buttons.
        width = Math.min(dp(24 + 12 + 3 * 28), available);
        left = (barWidth - width) / 2;

        int iconsEnd = drawerRight;
        for (View icon : compactIcons) {
            if (icon.getParent() == null) continue;
            icon.getLocationOnScreen(loc);
            iconsEnd = Math.max(iconsEnd, loc[0] - barLoc[0] + icon.getWidth());
        }
        left = Math.max(left, iconsEnd + gap);
        left = Math.min(left, weatherLimit - width);
    }

    FrameLayout.LayoutParams params =
            (FrameLayout.LayoutParams) mediaSlot.getLayoutParams();
    int margin = left - bar.getPaddingLeft();
    if (params.width != width || params.leftMargin != margin
            || params.gravity != (Gravity.LEFT | Gravity.CENTER_VERTICAL)) {
        params.width = width;
        params.leftMargin = margin;
        params.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        mediaSlot.setLayoutParams(params);
    }
    mediaSlot.setTranslationX(0);
    mediaSlot.setVisibility(View.VISIBLE);

    // Hide every icon that would touch the media bar (or sits beyond it), so the
    // number of visible shortcuts is limited when the media bar cannot move further.
    for (View icon : compactIcons) {
        if (icon.getParent() == null) continue;
        icon.getLocationOnScreen(loc);
        int iconRight = loc[0] - barLoc[0] + icon.getWidth();
        icon.setVisibility(iconRight + gap > left ? View.INVISIBLE : View.VISIBLE);
    }
}

    private View buildSidePanel() {
        int n = columns;
        wifiBars = cellBars = null;
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(panelBackground());
        p.setPadding(dp("left".equals(panelPos) ? PAD_EDGE : PAD_INNER), dp(4), dp("left".equals(panelPos) ? PAD_INNER : PAD_EDGE), dp(4));

        boolean showClock = prefs.getBoolean("show_clock", true);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(n >= 3 ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        clockBox.setBaselineAligned(true);

        boolean h24 = prefs.getBoolean("clock_24h", true);
        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.HORIZONTAL);
        time.setBaselineAligned(true);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour(h24 ? "H:mm" : "h:mm");
        clock.setFormat24Hour(h24 ? "H:mm" : "h:mm");
        style(clock, n >= 3 ? 28 : 24, TEXT);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setSingleLine(true);
        time.addView(clock);
        time.setBaselineAlignedChildIndex(0);

        if (!h24) {
            TextClock ampm = new TextClock(this);
            ampm.setFormat12Hour("a");
            ampm.setFormat24Hour("a");
            style(ampm, 13, TEXT);
            ampm.setSingleLine(true);
            ampm.setPadding(dp(4), 0, 0, 0);
            time.addView(ampm);
        }

        TextClock date = new TextClock(this);
        String dateFormat = n >= 4 ? "EEE, MMM d" : "MMM d";
        date.setFormat12Hour(dateFormat);
        date.setFormat24Hour(dateFormat);
        style(date, n >= 3 ? 13 : 12, 0xFFDCE4E7);
        date.setSingleLine(true);
        if (n >= 3) date.setPadding(dp(6), 0, 0, 0);
        clockBox.addView(time);
        clockBox.addView(date);

        if (!showClock) clockBox.setVisibility(View.GONE);
        header.addView(clockBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        weatherTemp = null;
        weatherRange = null;
        boolean statusIcons = "top".equals(signalPlace());
        if (n >= 3) {
            LinearLayout wx = new LinearLayout(this);
            wx.setOrientation(LinearLayout.HORIZONTAL);
            wx.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            wx.setPadding(dp(2), statusIcons ? dp(STATUS_ROW_DP) : statusBarHeight(), 0, 0);
            weatherTemp = text("", 20, TEXT);
            weatherTemp.setSingleLine(true);
            wx.addView(weatherTemp);
            if (n >= 4) {
                weatherRange = text("", 13, DIM);
                weatherRange.setSingleLine(true);
                weatherRange.setPadding(dp(8), 0, 0, 0);
                wx.addView(weatherRange);
            }
            header.addView(wx);
        }

        if (statusIcons) {
            FrameLayout top = new FrameLayout(this);
            top.addView(header);
            top.addView(statusSignals(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
            p.addView(top);
        } else if (showClock || weatherTemp != null) {
            p.addView(header);
        }

        if (prefs.getBoolean("show_media", true) && !settingsOpen) {
            p.addView(buildMediaCard(n), marginTop(LinearLayout.LayoutParams.MATCH_PARENT, 4));
        } else {
            mediaTitle = new TextView(this);
            mediaArtist = new TextView(this);
            mediaArt = new ImageView(this);
            playButton = new TextView(this);
            progressRow = null;
        }

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(settingsOpen ? buildSettings(n) : buildShortcuts(n));
        p.addView(scroller, weighted(4));

        if (prefs.getBoolean("show_mic", false) || "bottom".equals(signalPlace())) {
            p.addView(buildBottomBar(n), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        return p;
    }

    private int statusBarHeight() {
        int top = wm.getMaximumWindowMetrics().getWindowInsets().getInsets(WindowInsets.Type.statusBars()).top;
        if (top == 0) {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id != 0) top = getResources().getDimensionPixelSize(id);
        }
        return top;
}

private LinearLayout buildCompactWeather() {
    LinearLayout weather = new LinearLayout(this);
    weather.setOrientation(LinearLayout.VERTICAL);
    weather.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
    weather.setPadding(dp(8), 0, 0, 0);

    weatherTemp = text("", 9, TEXT);
    weatherTemp.setSingleLine(true);
    weatherTemp.setGravity(Gravity.END);
    weather.addView(weatherTemp);

    // Compact layout uses the second line for the current time, not the forecast range.
    weatherRange = null;
    boolean use24Hour = prefs.getBoolean("clock_24h", true);
    String timeFormat = use24Hour ? "HH:mm" : "h:mm a";

    TextClock compactClock = new TextClock(this);
    compactClock.setFormat12Hour(timeFormat);
    compactClock.setFormat24Hour(timeFormat);
    style(compactClock, 10, TEXT);
    compactClock.setSingleLine(true);
    compactClock.setGravity(Gravity.END);
    weather.addView(compactClock);

    return weather;
}

private View buildCompactMedia() {
    LinearLayout media = new LinearLayout(this);
    media.setOrientation(LinearLayout.HORIZONTAL);
    media.setGravity(Gravity.CENTER_VERTICAL);
    media.setPadding(dp(6), dp(4), dp(6), dp(4));
    media.setBackground(rounded(0x24FFFFFF, dp(8)));

    mediaArt = new ImageView(this);
    mediaArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
    mediaArt.setBackground(rounded(0xFF26323A, 8));
    mediaArt.setClipToOutline(true);
    media.addView(mediaArt, new LinearLayout.LayoutParams(dp(26), dp(26)));

    FrameLayout content = new FrameLayout(this);
    media.addView(content, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

    // Normal display: previous, play/pause, next.
    LinearLayout controls = new LinearLayout(this);
    controls.setOrientation(LinearLayout.HORIZONTAL);
    controls.setGravity(Gravity.CENTER);

    TextView previous = mediaButton("|◀", false, true);
    previous.setContentDescription("Previous track");
    previous.setPadding(0, 0, 0, dp(4));
    previous.setOnClickListener(v -> {
        if (controller != null) controller.getTransportControls().skipToPrevious();
    });
    controls.addView(previous);

    playButton = mediaButton("▶", false, true);
    playButton.setContentDescription("Play or pause");
    playButton.setPadding(0, 0, 0, dp(4));
    playButton.setOnClickListener(v -> togglePlay());
    controls.addView(playButton);

    TextView next = mediaButton("▶|", false, true);
    next.setContentDescription("Next track");
    next.setPadding(0, 0, 0, dp(4));
    next.setOnClickListener(v -> {
        if (controller != null) controller.getTransportControls().skipToNext();
    });
    controls.addView(next);

    content.addView(controls, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER));
    compactMediaControls = controls;

    // Shown briefly after track metadata changes.
    LinearLayout trackInfo = new LinearLayout(this);
    trackInfo.setOrientation(LinearLayout.VERTICAL);
    trackInfo.setGravity(Gravity.CENTER_VERTICAL);
    trackInfo.setPadding(dp(8), 0, dp(4), 0);

    mediaTitle = text("Nothing playing", 8, TEXT);
    mediaTitle.setSingleLine(true);
    mediaTitle.setEllipsize(TextUtils.TruncateAt.END);
    trackInfo.addView(mediaTitle);

    mediaArtist = text("", 6, DIM);
    mediaArtist.setSingleLine(true);
    mediaArtist.setEllipsize(TextUtils.TruncateAt.END);
    trackInfo.addView(mediaArtist);

    FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER);
    content.addView(trackInfo, infoParams);
    trackInfo.setVisibility(View.GONE);
    compactTrackInfo = trackInfo;

    progressRow = null;
    mediaProgress = null;
    mediaRemaining = null;

    media.setOnClickListener(v -> {
        if (controller != null) open(controller.getPackageName());
    });

    return media;
}
    
    private static final int DEFAULT_DENSITY = 160;
    private static final int[] DENSITIES = {120, 140, 160, 180, 200};

    // Guards against retry loops: a density change can recreate the activity,
    // so only ever try to apply the saved density once per process.
    private static boolean densityStartupTried = false;

    /** Low-level call. Returns true on success, false on any failure (never throws). */
    private boolean forceDensity(int dpi) {
        try {
            Object wms = Class.forName("android.view.WindowManagerGlobal")
                    .getMethod("getWindowManagerService").invoke(null);
            wms.getClass().getMethod("setForcedDisplayDensityForUser", int.class, int.class, int.class)
                    .invoke(wms, 0, dpi, 0);
            return true;
        } catch (Throwable e) {
            Log.w("MiniCarHome", "forceDensity(" + dpi + ") failed", e);
            return false;
        }
    }

    /** Best-effort reset of the system's forced density back to the device default. */
    private boolean clearForcedDensity() {
        try {
            Object wms = Class.forName("android.view.WindowManagerGlobal")
                    .getMethod("getWindowManagerService").invoke(null);
            wms.getClass().getMethod("clearForcedDisplayDensityForUser", int.class, int.class)
                    .invoke(wms, 0, 0);
            return true;
        } catch (Throwable e) {
            Log.w("MiniCarHome", "clearForcedDensity failed", e);
            return false;
        }
    }

    /** Startup: silently apply the saved density if possible; ignore failures. */
    private void applySavedDensity() {
        if (densityStartupTried) return;
        densityStartupTried = true;
        try {
            if (prefs.contains("density")) {
                int saved = prefs.getInt("density", 0);
                int current = getResources().getConfiguration().densityDpi;
                if (saved > 0 && saved != current && !forceDensity(saved)) {
                    Log.w("MiniCarHome", "saved density " + saved + " can't be applied, ignoring");
                }
            } else if (!prefs.getBoolean("density_init", false)) {
                // First run only: try the default once. Not retried if it fails,
                // and not re-applied after the user picks "None".
                prefs.edit().putBoolean("density_init", true).apply();
                if (forceDensity(DEFAULT_DENSITY)) {
                    prefs.edit().putInt("density", DEFAULT_DENSITY).apply();
                }
            }
        } catch (Throwable t) {
            Log.w("MiniCarHome", "applySavedDensity failed", t);
        }
    }

    /** User picked a density in settings. */
    private void setDensity(int dpi) {
        if (forceDensity(dpi)) {
            prefs.edit().putInt("density", dpi).putBoolean("density_init", true).apply();
        } else {
            Toast.makeText(this, "Display size needs setup: adb shell pm grant com.kynrek.minicarhome "
                    + "android.permission.WRITE_SECURE_SETTINGS", Toast.LENGTH_LONG).show();
        }
    }

    /** User picked "None": wipe the saved value and (if permitted) restore system density. */
    private void clearDensity() {
        prefs.edit().remove("density").putBoolean("density_init", true).apply();
        if (!clearForcedDensity()) {
            Toast.makeText(this, "Saved display size removed (system size left unchanged)",
                    Toast.LENGTH_SHORT).show();
        }
        rebuildPanel();
    }

    private static final int PICK_WALLPAPER = 41;
    private static final int WALLPAPER_SCRIM = 0x660E1416;
    private Bitmap wallpaperCache;
    private String wallpaperKey;

    private File wallpaperFile() {
        return new File(getExternalFilesDir(null), "wallpaper.jpg");
    }

    private Drawable panelBackground() {
        File f = wallpaperFile();
        if (!f.exists()) return new ColorDrawable(BG);
        int w = panelWidth(), h = wm.getMaximumWindowMetrics().getBounds().height();
        String key = f.lastModified() + ":" + w + "x" + h;
        if (wallpaperCache == null || !key.equals(wallpaperKey)) {
            Bitmap full = BitmapFactory.decodeFile(f.getPath());
            if (full == null) return new ColorDrawable(BG);
            int bw = full.getWidth(), bh = full.getHeight();
            int cw = bw, ch = bw * h / w;
            if (ch > bh) { ch = bh; cw = bh * w / h; }
            Bitmap crop = Bitmap.createBitmap(full, (bw - cw) / 2, (bh - ch) / 2, cw, ch);
            wallpaperCache = Bitmap.createScaledBitmap(crop, w, h, true);
            wallpaperKey = key;
        }
        BitmapDrawable pic = new BitmapDrawable(getResources(), wallpaperCache);
        return new LayerDrawable(new Drawable[]{pic, new ColorDrawable(WALLPAPER_SCRIM)});
    }

    private void pickWallpaper() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        hidePanel();
        try {
            startActivityForResult(i, PICK_WALLPAPER);
        } catch (Exception e) {
            showPanel();
            Toast.makeText(this, "No picture picker available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_WALLPAPER) return;
        if (result == RESULT_OK && data != null && data.getData() != null) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(data.getData())) { BitmapFactory.decodeStream(in, null, o); }
                o.inSampleSize = 1;
                while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= 1400) o.inSampleSize *= 2;
                o.inJustDecodeBounds = false;
                Bitmap b;
                try (InputStream in = getContentResolver().openInputStream(data.getData())) { b = BitmapFactory.decodeStream(in, null, o); }
                try (FileOutputStream out = new FileOutputStream(wallpaperFile())) { b.compress(Bitmap.CompressFormat.JPEG, 90, out); }
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't use that picture", Toast.LENGTH_SHORT).show();
            }
        }
        rebuildPanel();
        showPanel();
    }

    private void removeWallpaper() {
        wallpaperFile().delete();
        wallpaperCache = null;
        rebuildPanel();
    }

    private void rebuildPanel() {
        if (mapSlot != null) applyMapSlotClock();
        panel.removeAllViews();
        panel.addView(buildPanel());
        if (panelShown) wm.updateViewLayout(panel, panelParams());
        showMedia();
        main.removeCallbacks(signalTick);
        if (panelShown) main.post(signalTick);
        else updateSignals();
        showWeather();
    }

    private final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            Weather last = Weather.cached(prefs);
            if (last == null || System.currentTimeMillis() - last.fetchedAt > WEATHER_REFRESH_MS - 60_000) {
                Context app = getApplicationContext();
                new Thread(() -> {
                    Weather w = Weather.fetch(app);
                    if (w != null) {
                        w.save(prefs);
                        main.post(HomeActivity.this::showWeather);
                    } else {
                        Log.i(TAG, "weather fetch failed; trying again in a minute");
                        main.post(() -> {
                            if (!started) return;
                            main.removeCallbacks(weatherTick);
                            main.postDelayed(weatherTick, WEATHER_RETRY_MS);
                        });
                    }
                }, "weather").start();
            }
            main.postDelayed(this, WEATHER_REFRESH_MS);
        }
    };

    private void showWeather() {
        if (weatherTemp == null) return;
        Weather w = Weather.cached(prefs);
        if (w == null) {
            weatherTemp.setText("");
            if (weatherRange != null) weatherRange.setText("");
            return;
        }
        weatherTemp.setText(w.symbol() + " " + w.temp + "°");
        if (weatherRange != null) weatherRange.setText("H " + w.high + "°  L " + w.low + "°");
    }

    private LinearLayout.LayoutParams weighted(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private View buildSettings(int n) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setBackground(rounded(CARD, 16));
        s.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text("Options", 16, ACCENT), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(choice("Done", true, v -> { settingsOpen = false; rebuildPanel(); }));
        s.addView(top);
        String layoutLabel = ("top".equals(panelPos) || "bottom".equals(panelPos))
                ? "Number of rows"
                : (n >= 2 ? "Icons per row" : "Icons");
        s.addView(text(layoutLabel, 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        LinearLayout stepper = new LinearLayout(this);
        stepper.setOrientation(LinearLayout.HORIZONTAL);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        TextView minus = control("−");
        TextView value = text(String.valueOf(n), 22, ACCENT);
        value.setGravity(Gravity.CENTER);
        TextView plus = control("+");
        minus.setMinWidth(dp(32));
        plus.setMinWidth(dp(32));
        minus.setOnClickListener(v -> setColumns(columns - 1));
        plus.setOnClickListener(v -> setColumns(columns + 1));
        stepper.addView(minus);
        stepper.addView(value, new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));
        stepper.addView(plus);
        s.addView(stepper, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        s.addView(text("Bar position", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        LinearLayout positions = new LinearLayout(this);
        positions.setOrientation(LinearLayout.HORIZONTAL);
        positions.addView(choice("Left", "left".equals(panelPos), v -> setPanelPos("left")));
        positions.addView(choice("Right", "right".equals(panelPos), v -> setPanelPos("right")));
        positions.addView(choice("Top", "top".equals(panelPos), v -> setPanelPos("top")));
        positions.addView(choice("Bottom", "bottom".equals(panelPos), v -> setPanelPos("bottom")));
        s.addView(positions, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        s.addView(text(n >= 3 ? "Display size (other apps)" : "Display size", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        HorizontalScrollView dsScroll = new HorizontalScrollView(this);
        dsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout ds = new LinearLayout(this);
        ds.setOrientation(LinearLayout.HORIZONTAL);
        int dpi = getResources().getConfiguration().densityDpi;
        ds.addView(choice("None", !prefs.contains("density"), v -> clearDensity()));
        for (int d : DENSITIES) ds.addView(choice(String.valueOf(d), prefs.contains("density") && d == dpi, v -> setDensity(d)));
        dsScroll.addView(ds);
        s.addView(dsScroll, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        s.addView(text("Wallpaper", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        LinearLayout wp = new LinearLayout(this);
        wp.setOrientation(LinearLayout.HORIZONTAL);
        wp.addView(choice("Choose", false, v -> pickWallpaper()));
        wp.addView(choice("None", !wallpaperFile().exists(), v -> removeWallpaper()));
        s.addView(wp, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        s.addView(toggleRow("Show app labels", "show_labels", false),
        marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 6));

        s.addView(text(n >= 3 ? "Main display app" : "Main app", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.addView(choice(appName(navApp()), true, v -> showNavPicker()));
        nav.addView(choice("Change", false, v -> showNavPicker()));
        s.addView(nav, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        s.addView(text(n >= 3 ? "Offline app (no internet)" : "Offline app", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        LinearLayout off = new LinearLayout(this);
        off.setOrientation(LinearLayout.HORIZONTAL);
        String offMode = offlineMode(), offApp = offlineApp();
        off.addView(choice("None", "none".equals(offMode), v -> setString("offline_app", "none")));
        off.addView(choice("Wait", "wait".equals(offMode), v -> setString("offline_app", "wait")));
        off.addView(choice(offApp == null ? "Choose…" : appName(offApp), offApp != null, v -> showOfflinePicker()));
        HorizontalScrollView offScroll = new HorizontalScrollView(this);
        offScroll.setHorizontalScrollBarEnabled(false);
        offScroll.addView(off);
        s.addView(offScroll, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        s.addView(toggleRow(n >= 3 ? "Open apps in main window" : "Apps in main", "apps_in_pane"),
                marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 12));
        s.addView(toggleRow("Clock & date", "show_clock"), marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 12));
        s.addView(toggleRow(n >= 3 ? "24-hour time" : "24-hour", "clock_24h", true), marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 6));
        s.addView(toggleRow("Media", "show_media"), marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 6));
        s.addView(toggleRow("Assistant", "show_mic", false), marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 6));
        if (prefs.getBoolean("show_mic", false)) {
            LinearLayout as = new LinearLayout(this);
            as.setOrientation(LinearLayout.HORIZONTAL);
            String app = assistantApp(), mode = assistantMode();
            as.addView(choice("Built-in", "builtin".equals(mode), v -> setString("assistant_mode", "builtin")));
            as.addView(choice("Android", "system".equals(mode), v -> setString("assistant_mode", "system")));
            as.addView(choice(app == null ? "App…" : appName(app), "app".equals(mode), v -> {
                if (app == null || "app".equals(mode)) showAssistantPicker();
                else setString("assistant_mode", "app");
            }));
            HorizontalScrollView asScroll = new HorizontalScrollView(this);
            asScroll.setHorizontalScrollBarEnabled(false);
            asScroll.addView(as);
            s.addView(asScroll, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        }
        s.addView(toggleRow(n >= 3 ? "Hide recents in app drawer" : "Hide recents", "hide_recent", false),
                marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 6));

        s.addView(text("Media buttons", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        LinearLayout mb = new LinearLayout(this);
        mb.setOrientation(LinearLayout.HORIZONTAL);
        String buttons = prefs.getString("media_buttons", "both");
        mb.addView(choice("Both", "both".equals(buttons), v -> setString("media_buttons", "both")));
        mb.addView(choice("Track", "track".equals(buttons), v -> setString("media_buttons", "track")));
        mb.addView(choice("Seek", "seek".equals(buttons), v -> setString("media_buttons", "seek")));
        s.addView(mb, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        s.addView(text("Seek seconds", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        HorizontalScrollView seekScroll = new HorizontalScrollView(this);
        seekScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout sk = new LinearLayout(this);
        sk.setOrientation(LinearLayout.HORIZONTAL);
        for (int secs : new int[]{5, 10, 15, 30, 60}) {
            sk.addView(choice(String.valueOf(secs), secs == seekSeconds(), v -> {
                prefs.edit().putInt("seek_seconds", secs).apply();
                rebuildPanel();
            }));
        }
        seekScroll.addView(sk);
        s.addView(seekScroll, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        s.addView(text("Progress line", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        LinearLayout pl = new LinearLayout(this);
        pl.setOrientation(LinearLayout.HORIZONTAL);
        String style = prefs.getString("progress_style", "wavy");
        pl.addView(choice("Wavy", !"straight".equals(style), v -> setString("progress_style", "wavy")));
        pl.addView(choice("Straight", "straight".equals(style), v -> setString("progress_style", "straight")));
        s.addView(pl, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        LinearLayout an = new LinearLayout(this);
        an.setOrientation(LinearLayout.HORIZONTAL);
        boolean animate = prefs.getBoolean("progress_animate", true);
        an.addView(choice("Animated", animate, v -> { prefs.edit().putBoolean("progress_animate", true).apply(); rebuildPanel(); }));
        an.addView(choice("Still", !animate, v -> { prefs.edit().putBoolean("progress_animate", false).apply(); rebuildPanel(); }));
        s.addView(an, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        HorizontalScrollView thickScroll = new HorizontalScrollView(this);
        thickScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout th = new LinearLayout(this);
        th.setOrientation(LinearLayout.HORIZONTAL);
        int thick = prefs.getInt("progress_thickness", 3);
        String[] names = {"Thin", "Medium", "Thick"};
        int[] values = {2, 3, 5};
        for (int i = 0; i < 3; i++) {
            int thickness = values[i];
            th.addView(choice(names[i], thickness == thick, v -> {
                prefs.edit().putInt("progress_thickness", thickness).apply();
                rebuildPanel();
            }));
        }
        thickScroll.addView(th);
        s.addView(thickScroll, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        s.addView(text("Signal", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        LinearLayout sig = new LinearLayout(this);
        sig.setOrientation(LinearLayout.HORIZONTAL);
        String place = signalPlace();
        sig.addView(choice("Off", "off".equals(place), v -> setSignalPlace("off")));
        sig.addView(choice("Top", "top".equals(place), v -> setSignalPlace("top")));
        sig.addView(choice("Bottom", "bottom".equals(place), v -> setSignalPlace("bottom")));
        s.addView(sig, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        TextView edit = choice(n >= 3 ? "Edit shortcuts" : "Edit apps", false, v -> {
            settingsOpen = false;
            editing = true;
            rebuildPanel();
        });
        s.addView(edit, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        TextView reset = choice(n >= 3 ? "Reset shortcuts" : "Reset apps", false, v -> {
            prefs.edit().remove("shortcuts").apply();
            settingsOpen = false;
            rebuildPanel();
        });
        s.addView(reset, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        TextView done = text("Done", 15, ACCENT);
        done.setPadding(0, dp(10), 0, dp(4));
        done.setOnClickListener(v -> { settingsOpen = false; rebuildPanel(); });
        s.addView(done);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(0, dp(6), 0, 0);
        wrap.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private View toggleRow(String label, String key) { return toggleRow(label, key, true); }

    private View toggleRow(String label, String key, boolean byDefault) {
        boolean on = prefs.getBoolean(key, byDefault);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(label, 14, TEXT);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(choice(on ? "On" : "Off", on, v -> setPref(key, !on)));
        return row;
    }

    private TextView choice(String label, boolean selected, View.OnClickListener click) {
        TextView c = text(label, 14, selected ? BG : TEXT);
        c.setGravity(Gravity.CENTER);
        c.setBackground(rounded(selected ? ACCENT : 0xFF26323A, 12));
        c.setPadding(dp(12), dp(6), dp(12), dp(6));
        c.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        c.setLayoutParams(lp);
        return c;
    }

    private View buildShortcuts(int n) {
        List<View> tiles = new ArrayList<>();
        View home = appTile(navApp());
        home.setOnClickListener(v -> goHome());
        tiles.add(home);

        List<String> list = shortcuts();
        for (int i = 0; i < list.size(); i++) {
            String entry = list.get(i);
            if (navApp().equals(entry.split("/")[0])) continue;
            View t = null;
            if (isWazeEntry(entry)) t = wazeTile(entry);
            else if (entry.contains("/")) t = activityTile(ComponentName.unflattenFromString(entry));
            else if (pm.getLaunchIntentForPackage(entry) != null) t = appTile(entry);
            if (t == null) continue;
            tiles.add(editing ? editable(t, i, list.size()) : t);
        }

        if (editing) {
            tiles.add(iconTile("+", "Add", v -> showPicker()));
            tiles.add(iconTile("✓", "Done", v -> { editing = false; rebuildPanel(); }));
        } else {
            tiles.add(appsGridTile("All apps", v -> showBrowser()));
        }
        return rows(tiles, n);
    }

    private View editable(View tile, int index, int count) {
        tile.setOnClickListener(null);
        boolean compact = isCompactPanel();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        if (compact) {
            // Compact panel: overlay the buttons on the icon itself.
            FrameLayout box = new FrameLayout(this);
            box.addView(tile, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            bar.setBackground(rounded(0xB3000000, 6));
            bar.addView(editButton("◀", index > 0, v -> moveShortcut(index, -1)), overlayButtonParams());
            bar.addView(editButton("✕", true, v -> removeShortcut(index)), overlayButtonParams());
            bar.addView(editButton("▶", index < count - 1, v -> moveShortcut(index, 1)), overlayButtonParams());
            box.addView(bar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            return box;
        }
        LinearLayout box = column();
        box.addView(tile);
        bar.addView(editButton("◀", index > 0, v -> moveShortcut(index, -1)));
        bar.addView(editButton("✕", true, v -> removeShortcut(index)));
        bar.addView(editButton("▶", index < count - 1, v -> moveShortcut(index, 1)));
        box.addView(bar);
        return box;
    }

    private LinearLayout.LayoutParams overlayButtonParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private TextView editButton(String glyph, boolean enabled, View.OnClickListener click) {
        boolean compact = isCompactPanel();
        TextView b = text(glyph, compact ? 11 : 13, enabled ? ACCENT : 0xFF3A474D);
        b.setGravity(Gravity.CENTER);
        if (!compact) b.setMinWidth(dp(24));
        b.setPadding(0, dp(compact ? 6 : 4), 0, dp(compact ? 6 : 4));
        if (enabled) b.setOnClickListener(click);
        return b;
    }

    private View buildBottomBar(int n) {
        boolean mic = prefs.getBoolean("show_mic", false);
        boolean signals = "bottom".equals(signalPlace());
        boolean oneRow = n >= 4 || (n == 3 && !mic);

        LinearLayout box = new LinearLayout(this);
        box.setPadding(0, dp(2), dp(4), dp(6));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);

        voiceStatus = text("", 13, TEXT);
        voiceStatus.setSingleLine(true);
        voiceStatus.setEllipsize(TextUtils.TruncateAt.START);
        voiceStatus.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        voiceStatus.setPadding(dp(4), 0, dp(8), 0);
        buttons.addView(voiceStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (mic) {
            LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(dp(36), dp(36));
            if (signals && oneRow) micLp.rightMargin = dp(16);
            buttons.addView(assistantButton(), micLp);
        }
        if (signals && oneRow) {
            box.setOrientation(LinearLayout.HORIZONTAL);
            buttons.addView(signalsRow());
            box.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            box.setOrientation(LinearLayout.VERTICAL);
            box.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (signals) {
                View sig = n >= 3 ? signalsRow() : signalsStack();
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(4);
                box.addView(sig, lp);
            }
        }
        return box;
    }

    private String signalPlace() { return prefs.getString("signals", "off"); }

    private View signalsRowCompact() {
        newSignalViews();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.addView(wifiLabel);
        row.addView(wifiBars, barParams());
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(20), 1));
        row.addView(cellLabel);
        row.addView(cellBars, barParams());
        return row;
    }

    private View assistantButton() {
        boolean compact = isCompactPanel();
        int iconSize = compact ? dp(36) : dp(48);

        ImageView assistant = new ImageView(this);
        assistant.setImageResource(getResources().getIdentifier("ic_mic", "drawable", getPackageName()));
        assistant.setScaleType(ImageView.ScaleType.CENTER);

        assistant.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));

        assistant.setContentDescription("Assistant");
        assistant.setOnClickListener(v -> startAssistant());
        micDot = null;
        return assistant;
    }

    private void newSignalViews() {
        wifiBars = new SignalBars(this, TEXT, 0xFF3A474D);
        wifiLabel = text("Wi-Fi", 13, DIM);
        cellBars = new SignalBars(this, TEXT, 0xFF3A474D);
        cellLabel = text("", 13, DIM);
    }

    private View statusSignals() {
        newSignalViews();
        wifiBars = new SignalBars(this, TEXT, STATUS_ICON_OFF);
        wifiBars.setFan(true);
        cellBars = new SignalBars(this, TEXT, STATUS_ICON_OFF);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.setPadding(0, dp(STATUS_ROW_DP - 19), sysDp(STATUS_ICON_DP), 0);
        row.addView(wifiBars, new LinearLayout.LayoutParams(dp(26), dp(19)));
        LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(dp(22), dp(17));
        cell.leftMargin = dp(8);
        row.addView(cellBars, cell);
        return row;
    }

    private static final int STATUS_ROW_DP = 22;
    private static final int STATUS_ICON_DP = 44;
    private static final int STATUS_ICON_OFF = 0x59FFFFFF;

    private View signalsRow() {
        newSignalViews();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.addView(wifiLabel);
        row.addView(wifiBars, barParams());
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(20), 1));
        row.addView(cellLabel);
        row.addView(cellBars, barParams());
        return row;
    }

    private View signalsStack() {
        newSignalViews();
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(Gravity.END);
        stack.addView(signalLine(wifiLabel, wifiBars));
        stack.addView(signalLine(cellLabel, cellBars));
        return stack;
    }

    private View signalLine(TextView label, SignalBars bars) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        line.setPadding(0, dp(2), 0, dp(2));
        label.setSingleLine(true);
        line.addView(label);
        line.addView(bars, barParams());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        line.setLayoutParams(lp);
        return line;
    }

    private LinearLayout.LayoutParams barParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(16));
        lp.leftMargin = dp(6);
        return lp;
    }

    private void startAssistant() {
        String mode = assistantMode();
        if ("builtin".equals(mode)) { toggleListening(); return; }
        String app = "app".equals(mode) ? assistantApp() : null;
        String[] actions = {"android.intent.action.VOICE_ASSIST", Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST};
        for (String a : actions) {
            Intent i = new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (app != null) i.setPackage(app);
            if (i.resolveActivity(pm) == null) continue;
            try {
                startActivity(i);
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "assistant " + a + " failed", e);
            }
        }
        if (app != null) { open(app); return; }
        Toast.makeText(this, "No assistant available", Toast.LENGTH_SHORT).show();
    }

    private static final int LISTENING = 0xFFE5534B;
    private SpeechRecognizer recognizer;
    private boolean listening;
    private TextView voiceStatus;
    private GradientDrawable micDot;
    private final Runnable clearVoiceStatus = () -> showVoiceStatus("");

    private void toggleListening() {
        if (listening) {
            recognizer.cancel();
            setListening(false);
            showVoiceStatus("");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showVoiceStatus("Voice isn't available");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(voiceListener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        setListening(true);
        showVoiceStatus("Where to?");
        recognizer.startListening(i);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) toggleListening();
    }

    private void setListening(boolean on) {
        listening = on;
        if (micDot != null) micDot.setColor(on ? LISTENING : ACCENT);
    }

    private void showVoiceStatus(String s) {
        main.removeCallbacks(clearVoiceStatus);
        if (voiceStatus != null) voiceStatus.setText(s);
        if (!s.isEmpty() && !listening) main.postDelayed(clearVoiceStatus, 6000);
    }

    private final RecognitionListener voiceListener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { showVoiceStatus("Listening…"); }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override public void onPartialResults(Bundle partial) {
            String heard = firstResult(partial);
            if (heard != null && !heard.isEmpty()) showVoiceStatus(heard);
        }

        @Override public void onResults(Bundle results) {
            setListening(false);
            String heard = firstResult(results);
            if (heard == null || heard.trim().isEmpty()) { showVoiceStatus("Didn't catch that"); return; }
            navigateTo(destination(heard));
        }

        @Override public void onError(int error) {
            setListening(false);
            showVoiceStatus(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ? "Didn't catch that" : "Voice error " + error);
        }
    };

    private static String firstResult(Bundle b) {
        List<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    static String destination(String heard) {
        String s = heard.trim().replaceAll("[.!?]+$", "");
        String place = s.replaceFirst("(?i)^(please\\s+)?(navigate|navigation|directions|drive|take me|get me|go|head|route)"
                + "(\\s+me)?(\\s+to)?\\s+", "");
        return place.isEmpty() ? s : place;
    }

    private void navigateTo(String place) {
        String pkg = navApp(), q = Uri.encode(place);
        Intent i = new Intent(Intent.ACTION_VIEW).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if ("com.waze".equals(pkg)) i.setData(Uri.parse("https://waze.com/ul?q=" + q + "&navigate=yes"));
        else if (MAPS.equals(pkg)) i.setData(Uri.parse("google.navigation:q=" + q));
        else i.setData(Uri.parse("geo:0,0?q=" + q));
        if (i.resolveActivity(pm) == null) { showVoiceStatus("Can't navigate with " + appName(pkg)); return; }
        showVoiceStatus("→ " + place);
        sendToMainApp(i, pkg);
    }

    private void sendToMainApp(Intent i, String pkg) {
        paneApp = null;
        paneTarget = pkg;
        if (openedAt.containsKey(pkg)) {
            startPane(i, WINDOWING_MODE_MULTI_WINDOW);
        } else {
            launchMaps();
            main.postDelayed(() -> startPane(i, WINDOWING_MODE_MULTI_WINDOW), 1500);
        }
    }

    static boolean isWazeEntry(String entry) { return entry.startsWith("waze:"); }

    static String wazeEntry(String name, String address) {
        return "waze:" + Uri.encode(name) + ":" + Uri.encode(address);
    }

    static String wazeLabel(String entry) {
        String rest = entry.substring(5);
        if (rest.equals("home")) return "Home";
        if (rest.equals("work")) return "Work";
        return Uri.decode(rest.split(":", 2)[0]);
    }

    static String wazeLink(String entry) {
        String rest = entry.substring(5);
        String query;
        if (rest.equals("home") || rest.equals("work")) query = "favorite=" + rest;
        else {
            String[] parts = rest.split(":", 2);
            query = "q=" + Uri.encode(Uri.decode(parts.length > 1 ? parts[1] : parts[0]));
        }
        return "https://waze.com/ul?" + query + "&navigate=yes";
    }

    private void startWaze(String entry) {
        hideBrowser();
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(wazeLink(entry))).setPackage(WAZE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(pm) == null) {
            Toast.makeText(this, "Waze isn't installed", Toast.LENGTH_SHORT).show();
            return;
        }
        if (WAZE.equals(navApp())) { sendToMainApp(i, WAZE); return; }
        if (prefs.getBoolean("apps_in_pane", true)) { launchInPane(i, WAZE); return; }
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Can't open Waze: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

private View wazeTile(String entry) {
    try {
        return panelTile(appIcon(WAZE), wazeLabel(entry), v -> startWaze(entry));
    } catch (PackageManager.NameNotFoundException e) {
        return null;
    }
}

    private final Runnable signalTick = new Runnable() {
        @Override public void run() {
            updateSignals();
            if (wifiBars != null) main.postDelayed(this, SIGNAL_REFRESH_MS);
        }
    };

    @SuppressWarnings("deprecation")
    private void updateSignals() {
        if (wifiBars == null) return;

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        WifiInfo info = null;
        if (cm != null) {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && caps.getTransportInfo() instanceof WifiInfo) {
                    info = (WifiInfo) caps.getTransportInfo();
                    break;
                }
            }
        }
        boolean wifiUp = wifi != null && info != null && info.getRssi() > -127;
        if (wifiUp) {
            int max = wifi.getMaxSignalLevel();
            int lvl = wifi.calculateSignalLevel(info.getRssi());
            wifiBars.setLevel(Math.max(1, Math.round(4f * lvl / Math.max(1, max))));
            wifiLabel.setText("Wi-Fi");
            wifiLabel.setTextColor(TEXT);
        } else {
            wifiBars.setLevel(-1);
            wifiLabel.setText("No Wi-Fi");
            wifiLabel.setTextColor(DIM);
        }

        TelephonyManager tel = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        SignalStrength ss = tel == null ? null : tel.getSignalStrength();
        String operator = tel == null ? "" : tel.getNetworkOperatorName();
        if (ss != null && operator != null && !operator.isEmpty()) {
            cellBars.setLevel(Math.max(1, ss.getLevel()));
            String type = networkType(tel);
            if (columns <= 3) cellLabel.setText(type.isEmpty() ? "Cell" : type);
            else cellLabel.setText(type.isEmpty() ? operator.trim() : operator.trim() + " · " + type);
            cellLabel.setTextColor(TEXT);
        } else {
            cellBars.setLevel(-1);
            cellLabel.setText("No service");
            cellLabel.setTextColor(DIM);
        }
    }

    private static String networkType(TelephonyManager tel) {
        int t;
        try {
            t = tel.getDataNetworkType();
        } catch (SecurityException e) {
            return "";
        }
        switch (t) {
            case TelephonyManager.NETWORK_TYPE_NR: return "5G";
            case TelephonyManager.NETWORK_TYPE_LTE: return "LTE";
            case TelephonyManager.NETWORK_TYPE_HSPAP: case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSDPA: case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_UMTS: return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE: case TelephonyManager.NETWORK_TYPE_GPRS: return "2G";
            default: return "";
        }
    }

    private View rows(List<View> tiles, int perRow) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int n = 0; n < tiles.size(); n++) {
            if (n % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, marginTop(LinearLayout.LayoutParams.MATCH_PARENT, 4));
            }
            row.addView(tiles.get(n), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        for (int pad = tiles.size() % perRow; pad != 0 && pad < perRow; pad++) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        }
        return grid;
    }

    private View buildMediaCard(int n) {
        boolean wide = n >= 3;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(wallpaperFile().exists() ? 0xB31B2428 : CARD, 16));
        card.setPadding(dp(8), dp(8), dp(8), dp(6));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        mediaArt = new ImageView(this);
        mediaArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaArt.setBackground(rounded(0xFF26323A, 10));
        mediaArt.setClipToOutline(true);
        int art = n >= 4 ? 56 : n == 3 ? 48 : 40;
        top.addView(mediaArt, new LinearLayout.LayoutParams(dp(art), dp(art)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(10), 0, 0, 0);
        mediaTitle = text("Nothing playing", wide ? 16 : 14, TEXT);
        mediaTitle.setSingleLine(true);
        mediaTitle.setEllipsize(TextUtils.TruncateAt.END);
        mediaArtist = text("", wide ? 13 : 12, DIM);
        mediaArtist.setSingleLine(true);
        mediaArtist.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(mediaTitle);
        info.addView(mediaArtist);

        String buttons = prefs.getString("media_buttons", "both");
        boolean track = !"seek".equals(buttons), seek = !"track".equals(buttons);
        int secs = seekSeconds();
        boolean five = track && seek;
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        playButton = mediaButton("▶", wide, five);
        playButton.setOnClickListener(v -> togglePlay());
        if (track) {
            TextView prev = mediaButton("|◀", wide, five);
            prev.setContentDescription("Previous track");
            prev.setOnClickListener(v -> { if (controller != null) controller.getTransportControls().skipToPrevious(); });
            controls.addView(prev);
        }
        if (seek) {
            TextView back = mediaButton("↺", wide, five);
            back.setContentDescription("Back " + secs + " seconds");
            back.setOnClickListener(v -> seekBy(-secs * 1000L));
            controls.addView(back);
        }
        controls.addView(playButton);
        if (seek) {
            TextView fwd = mediaButton("↻", wide, five);
            fwd.setContentDescription("Forward " + secs + " seconds");
            fwd.setOnClickListener(v -> seekBy(secs * 1000L));
            controls.addView(fwd);
        }
        if (track) {
            TextView next = mediaButton("▶|", wide, five);
            next.setContentDescription("Next track");
            next.setOnClickListener(v -> { if (controller != null) controller.getTransportControls().skipToNext(); });
            controls.addView(next);
        }

        if (wide && !five) info.addView(controls);
        top.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(top);
        if (!wide || five) card.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setPadding(0, dp(2), 0, 0);
        mediaProgress = new WavyProgress(this, ACCENT, 0xFF3A474D);
        mediaProgress.setStyle(!"straight".equals(prefs.getString("progress_style", "wavy")), prefs.getInt("progress_thickness", 3));
        mediaProgress.setAnimated(prefs.getBoolean("progress_animate", true));
        progressRow.addView(mediaProgress, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mediaRemaining = text("", 12, DIM);
        mediaRemaining.setPadding(dp(8), 0, 0, 0);
        mediaRemaining.setSingleLine(true);
        progressRow.addView(mediaRemaining);
        progressRow.setVisibility(View.GONE);
        card.addView(progressRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setOnClickListener(v -> { if (controller != null) open(controller.getPackageName()); });
        return card;
    }

    private class BrowserView extends FrameLayout {
        private final EditText search;
        private final LinearLayout recentRow;
        private final View recentSection;
        private final boolean hideRecent;
        private View wazeGoTo;
        private final List<App> all = new ArrayList<>();
        private final List<App> shown = new ArrayList<>();
        private final BaseAdapter adapter;
        private final int pick;

        BrowserView(Context c, int pick) {
            super(c);
            this.pick = pick;
            setBackgroundColor(BG);
            setFocusableInTouchMode(true);
            setDefaultFocusHighlightEnabled(false);

            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(12), dp(20), dp(12));
            addView(box);

            LinearLayout header = new LinearLayout(c);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView close = text("✕", 22, TEXT);
            close.setPadding(dp(6), dp(4), dp(18), dp(4));
            close.setOnClickListener(v -> hideBrowser());
            header.addView(close);
            search = new EditText(c);
            search.setHint(pick == PICK_SHORTCUT ? "Add a shortcut: search apps"
                    : pick == PICK_NAV ? "Main display app: search apps"
                    : pick == PICK_ASSISTANT ? "Assistant app: search apps"
                    : pick == PICK_OFFLINE ? "Offline app: search apps" : "Search apps");
            search.setSingleLine(true);
            style(search, 17, TEXT);
            search.setHintTextColor(DIM);
            search.setBackground(rounded(CARD, 12));
            search.setPadding(dp(14), dp(8), dp(14), dp(8));
            header.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            box.addView(header);

            if (pick == PICK_SHORTCUT && pm.getLaunchIntentForPackage(WAZE) != null) box.addView(wazeSection(c));

            LinearLayout recent = new LinearLayout(c);
            recent.setOrientation(LinearLayout.VERTICAL);
            recent.addView(sectionLabel("RECENT"));
            HorizontalScrollView scroller = new HorizontalScrollView(c);
            scroller.setHorizontalScrollBarEnabled(false);
            recentRow = new LinearLayout(c);
            recentRow.setOrientation(LinearLayout.HORIZONTAL);
            scroller.addView(recentRow);
            recent.addView(scroller);
            recentSection = recent;
            hideRecent = prefs.getBoolean("hide_recent", false);
            if (!hideRecent) box.addView(recent);
            box.addView(sectionLabel("ALL APPS"));

            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
                if (r.activityInfo.packageName.equals(getPackageName())) continue;
                ComponentName cn = component(r);
                String label = labelCache.get(cn.flattenToString());
                if (label == null) {
                    label = r.loadLabel(pm).toString();
                    labelCache.put(cn.flattenToString(), label);
                }
                all.add(new App(cn, label));
            }
            Collections.sort(all, (a, b) -> a.label.compareToIgnoreCase(b.label));
            shown.addAll(all);

            GridView grid = new GridView(c);
            grid.setNumColumns(GridView.AUTO_FIT);
            grid.setColumnWidth(dp(96));
            grid.setVerticalSpacing(dp(8));
adapter = new BaseAdapter() {
    private final int extraItems = pick == PICK_NONE ? 1 : 0;

    @Override public int getCount() {
        return shown.size() + extraItems;
    }

    @Override public Object getItem(int i) {
        return i < extraItems ? null : shown.get(i - extraItems);
    }

    @Override public long getItemId(int i) {
        return i;
    }

    @Override public int getViewTypeCount() {
        return 2;
    }

    @Override public int getItemViewType(int i) {
        return extraItems == 1 && i == 0 ? 0 : 1;
    }

    @Override public View getView(int i, View convert, ViewGroup parent) {
        if (extraItems == 1 && i == 0) {
            return optionsTile();
        }

        App a = shown.get(i - extraItems);
        Drawable icon;
        try {
            icon = activityIcon(a.cn);
        } catch (PackageManager.NameNotFoundException e) {
            icon = pm.getDefaultActivityIcon();
        }

        View t = convert != null ? convert : tile(null, "", null);
        bindTile(t, icon, a.label, v -> choose(a.cn));
        return t;
    }
};
            grid.setAdapter(adapter);
            box.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int d) { filter(s.toString()); }
                @Override public void afterTextChanged(Editable s) { }
            });
            fillRecent();
        }

        private View wazeSection(Context c) {
            LinearLayout section = new LinearLayout(c);
            section.setOrientation(LinearLayout.VERTICAL);
            section.addView(sectionLabel("NAVIGATE WITH WAZE (TYPE AN ADDRESS FOR MORE)"));
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Drawable icon;
            try { icon = appIcon(WAZE); } catch (PackageManager.NameNotFoundException e) { icon = pm.getDefaultActivityIcon(); }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(tile(icon, "Home", v -> { hideBrowser(); addShortcut("waze:home"); }), lp);
            row.addView(tile(icon, "Work", v -> { hideBrowser(); addShortcut("waze:work"); }), new LinearLayout.LayoutParams(lp));
            wazeGoTo = tile(icon, "", null);
            wazeGoTo.setVisibility(View.GONE);
            row.addView(wazeGoTo, new LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT));
            section.addView(row);
            return section;
        }

        private void filter(String q) {
            if (wazeGoTo != null) {
                String place = q.trim();
                wazeGoTo.setVisibility(place.isEmpty() ? View.GONE : View.VISIBLE);
                Drawable icon = ((ImageView) ((LinearLayout) wazeGoTo).getChildAt(0)).getDrawable();
                bindTile(wazeGoTo, icon, "Go to " + place, v -> { hideBrowser(); addShortcut(wazeEntry(place, place)); });
            }
            String needle = q.trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (App a : all) {
                if (needle.isEmpty() || a.lower.contains(needle)) shown.add(a);
            }
            if (!hideRecent) recentSection.setVisibility(needle.isEmpty() ? View.VISIBLE : View.GONE);
            adapter.notifyDataSetChanged();
        }

        private void fillRecent() {
            if (hideRecent) return;
            recentRow.removeAllViews();
            for (String flat : recentList()) {
                ComponentName cn = ComponentName.unflattenFromString(flat);
                if (cn == null) continue;
                try {
                    recentRow.addView(tile(activityIcon(cn), activityLabel(cn), v -> choose(cn)), new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
                } catch (PackageManager.NameNotFoundException ignored) { }
            }
            recentSection.setVisibility(recentRow.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        }

        private void choose(ComponentName cn) {
            if (pick == PICK_SHORTCUT) {
                hideBrowser();
                addShortcut(cn);
            } else if (pick == PICK_NAV) {
                hideBrowser();
                setNavApp(cn.getPackageName());
            } else if (pick == PICK_OFFLINE) {
                hideBrowser();
                setString("offline_app", cn.getPackageName());
            } else if (pick == PICK_ASSISTANT) {
                hideBrowser();
                prefs.edit().putString("assistant_app", cn.getPackageName()).putString("assistant_mode", "app").apply();
                rebuildPanel();
            } else {
                launch(cn);
            }
        }

        void hideKeyboard() {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) hideBrowser();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }

    private static final class App {
        final ComponentName cn;
        final String label, lower;

        App(ComponentName cn, String label) {
            this.cn = cn;
            this.label = label;
            this.lower = label.toLowerCase(Locale.ROOT);
        }
    }

    private static ComponentName component(ResolveInfo r) {
        return new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
    }

    private void open(String pkg) {
        if (navApp().equals(pkg)) { launchMaps(); return; }
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) launch(i.getComponent());
    }

    private void launch(ComponentName cn) {
        hideBrowser();
        paneLaunchedAt = 0;
        if (cn == null) return;
        remember(cn);
        if (navApp().equals(cn.getPackageName())) { launchMaps(); return; }
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (opensInPane(cn)) {
            String real = TRAMPOLINES.get(cn.flattenToString());
            Intent target = real == null ? i : new Intent().setComponent(ComponentName.unflattenFromString(real))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchInPane(target, cn.getPackageName());
            return;
        }
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Can't open: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private List<String> recentList() {
        List<String> out = new ArrayList<>();
        String saved = prefs.getString("recent", "");
        if (!saved.isEmpty()) Collections.addAll(out, saved.split("\\|"));
        return out;
    }

    private void remember(ComponentName cn) {
        List<String> list = recentList();
        String flat = cn.flattenToString();
        list.remove(flat);
        list.add(0, flat);
        while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
        prefs.edit().putString("recent", TextUtils.join("|", list)).apply();
    }

    private ComponentName listenerName() { return new ComponentName(this, MediaListener.class); }

    private void pickController() {
        List<MediaController> list;
        try {
            list = sessions.getActiveSessions(listenerName());
        } catch (SecurityException e) {
            return;
        }
        watch(list);
        MediaController best = null, current = null;
        for (MediaController c : list) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
            if (controller != null && c.getSessionToken().equals(controller.getSessionToken())) current = c;
        }
        if (best == null) best = current != null ? current : list.isEmpty() ? null : list.get(0);
        setController(best);
    }

    private void watch(List<MediaController> list) {
        for (MediaController c : watched) c.unregisterCallback(watcher);
        watched.clear();
        for (MediaController c : list) { c.registerCallback(watcher, main); watched.add(c); }
    }

    private void setController(MediaController c) {
        if (c != null && controller != null && c.getSessionToken().equals(controller.getSessionToken())) {
            showMedia();
            return;
        }
        if (controller != null) controller.unregisterCallback(mediaCallback);
        controller = c;
        metadata = c == null ? null : c.getMetadata();
        playback = c == null ? null : c.getPlaybackState();
        if (controller != null) controller.registerCallback(mediaCallback, main);
        showMedia();
    }

    private void showMedia() {
        if (mediaTitle == null) return;
        if (controller == null) {
            mediaTitle.setText("Nothing playing");
            mediaArtist.setText("");
            mediaArt.setImageDrawable(null);
            playButton.setText("▶");
            return;
        }
        MediaMetadata m = metadata;
        String title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null && m != null) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
        mediaTitle.setText(title != null ? title : appName(controller.getPackageName()));
        mediaArtist.setText(artist != null ? artist : appName(controller.getPackageName()));
        Bitmap art = null;
        if (m != null) {
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        if (art != null) {
            Bitmap fit = fitArt(art, m);
            Drawable now = mediaArt.getDrawable();
            if (!(now instanceof BitmapDrawable && ((BitmapDrawable) now).getBitmap() == fit)) mediaArt.setImageBitmap(fit);
        } else {
            try { mediaArt.setImageDrawable(appIcon(controller.getPackageName())); }
            catch (PackageManager.NameNotFoundException e) { mediaArt.setImageDrawable(null); }
        }
        PlaybackState s = playback;
        playButton.setText(s != null && s.getState() == PlaybackState.STATE_PLAYING ? "❚❚" : "▶");
        showProgress();
    }

    private Bitmap artFit;
    private String artKey;

    private Bitmap fitArt(Bitmap art, MediaMetadata m) {
        String key = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) + "|" + m.getString(MediaMetadata.METADATA_KEY_TITLE)
                + "|" + m.getString(MediaMetadata.METADATA_KEY_ALBUM) + "|" + art.getWidth() + "x" + art.getHeight();
        if (!key.equals(artKey)) {
            int max = dp(56);
            float scale = Math.min(1f, (float) max / Math.max(art.getWidth(), art.getHeight()));
            artFit = scale < 1f
                    ? Bitmap.createScaledBitmap(art, Math.round(art.getWidth() * scale), Math.round(art.getHeight() * scale), true)
                    : art;
            artKey = key;
        }
        return artFit;
    }

    private int seekSeconds() { return prefs.getInt("seek_seconds", 15); }

    private long currentPosition(PlaybackState s) {
        long position = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            position += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return Math.max(0, position);
    }

    private void seekBy(long deltaMs) {
        if (controller == null) return;
        PlaybackState s = controller.getPlaybackState();
        MediaMetadata m = controller.getMetadata();
        if (s != null && (s.getActions() & PlaybackState.ACTION_SEEK_TO) != 0 && s.getPosition() >= 0) {
            long target = Math.max(0, currentPosition(s) + deltaMs);
            long duration = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (duration > 0) target = Math.min(duration, target);
            controller.getTransportControls().seekTo(target);
        } else if (deltaMs < 0) {
            controller.getTransportControls().rewind();
        } else {
            controller.getTransportControls().fastForward();
        }
    }

    private final Runnable progressTick = this::showProgress;

    private void showProgress() {
        main.removeCallbacks(progressTick);
        if (progressRow == null) return;
        MediaMetadata m = controller == null ? null : metadata;
        PlaybackState s = controller == null ? null : playback;
        long duration = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (duration <= 0 || s == null || s.getPosition() < 0) {
            progressRow.setVisibility(View.GONE);
            return;
        }
        boolean playing = s.getState() == PlaybackState.STATE_PLAYING;
        long position = Math.min(duration, currentPosition(s));
        progressRow.setVisibility(View.VISIBLE);
        mediaProgress.setProgress(position / (float) duration, playing);
        mediaRemaining.setText("−" + clockTime(duration - position));
        if (playing) main.postDelayed(progressTick, 1000);
    }

    private static String clockTime(long ms) {
        long sec = ms / 1000, h = sec / 3600, m = (sec / 60) % 60, s = sec % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    private void togglePlay() {
        if (controller == null) return;
        PlaybackState s = controller.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    private String appName(String pkg) {
        try { return appLabel(pkg); }
        catch (PackageManager.NameNotFoundException e) { return pkg; }
    }

    private final java.util.Map<String, Drawable.ConstantState> iconCache = new java.util.HashMap<>();
    private final java.util.Map<String, String> labelCache = new java.util.HashMap<>();

    private Drawable appIcon(String pkg) throws PackageManager.NameNotFoundException {
        Drawable.ConstantState cs = iconCache.get(pkg);
        if (cs != null) return cs.newDrawable();
        Drawable d = pm.getApplicationIcon(pkg);
        if (d.getConstantState() != null) iconCache.put(pkg, d.getConstantState());
        return d;
    }

    private Drawable activityIcon(ComponentName cn) throws PackageManager.NameNotFoundException {
        String key = cn.flattenToString();
        Drawable.ConstantState cs = iconCache.get(key);
        if (cs != null) return cs.newDrawable();
        Drawable d = pm.getActivityIcon(cn);
        if (d.getConstantState() != null) iconCache.put(key, d.getConstantState());
        return d;
    }

    private String appLabel(String pkg) throws PackageManager.NameNotFoundException {
        String label = labelCache.get(pkg);
        if (label == null) {
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            labelCache.put(pkg, label);
        }
        return label;
    }

    private String activityLabel(ComponentName cn) throws PackageManager.NameNotFoundException {
        String key = cn.flattenToString();
        String label = labelCache.get(key);
        if (label == null) {
            label = pm.getActivityInfo(cn, 0).loadLabel(pm).toString();
            labelCache.put(key, label);
        }
        return label;
    }

private View appTile(String pkg) {
    try {
        return panelTile(appIcon(pkg), appLabel(pkg), v -> open(pkg));
    } catch (PackageManager.NameNotFoundException e) {
        return new View(this);
    }
}
    
private View activityTile(ComponentName cn) {
    try {
        return panelTile(activityIcon(cn), activityLabel(cn), v -> launch(cn));
    } catch (PackageManager.NameNotFoundException | NullPointerException e) {
        return null;
    }
}

private boolean showPanelLabels() {
    return prefs.getBoolean("show_labels", false);
}

private void addPanelLabel(LinearLayout tile, String name) {
    if (showPanelLabels()) {
        tile.addView(label(name));
    }
}

private View panelTile(Drawable icon, String name, View.OnClickListener click) {
    LinearLayout tile = column();
    int iconSize = isCompactPanel() ? dp(36) : dp(48);
    tile.addView(new ImageView(this),
        new LinearLayout.LayoutParams(iconSize, iconSize));
    addPanelLabel(tile, name);
    bindTile(tile, icon, name, click);
    return tile;
}

    
private View appsGridTile(String name, View.OnClickListener click) {
        boolean compact = isCompactPanel();
        final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(0xFFFFFFFF); // Change app drawer dots to white
        View grid = new View(this) {
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float w = getWidth(), h = getHeight();
                float side = Math.min(w, h) * 0.64f;
                float cell = side / 3f;
                float size = cell * 0.62f;
                float corner = size * 0.32f;
                float x0 = (w - side) / 2f, y0 = (h - side) / 2f;
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 3; col++) {
                        float cx = x0 + cell * (col + 0.5f), cy = y0 + cell * (row + 0.5f);
                        canvas.drawRoundRect(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f,
                                corner, corner, dotPaint);
                    }
                }
            }
        };

        LinearLayout t = column();
        int iconSize = compact ? dp(36) : dp(48);
        t.addView(grid, new LinearLayout.LayoutParams(iconSize, iconSize));
        addPanelLabel(t, name);
        t.setContentDescription(name);
        t.setOnClickListener(click);
        
        t.setOnLongClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Failed to open app settings", e);
                return false;
            }
        });
        
        return t;
    }

    private View iconTile(String glyphText, String name, View.OnClickListener click) {
        boolean compact = isCompactPanel();
        TextView glyph = text(glyphText, compact ? 20 : 30, ACCENT);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(rounded(CARD, 12));

        LinearLayout t = column();
        int iconSize = compact ? dp(36) : dp(48);
        t.addView(glyph, new LinearLayout.LayoutParams(iconSize, iconSize));
        addPanelLabel(t, name);

        t.setOnClickListener(click);
        return t;
    }

    private View optionsTile() {
        ImageView gear = new ImageView(this);
        gear.setImageResource(getResources().getIdentifier("ic_settings", "drawable", getPackageName()));
        gear.setImageTintList(ColorStateList.valueOf(ACCENT));
        gear.setScaleType(ImageView.ScaleType.FIT_CENTER);
        gear.setBackground(rounded(CARD, 12));
        gear.setPadding(dp(11), dp(11), dp(11), dp(11));
        LinearLayout t = column();
        t.addView(gear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        t.addView(label("Options"));
        t.setContentDescription("Home Settings");
        t.setOnClickListener(v -> { hideBrowser(); settingsOpen = true; editing = false; rebuildPanel(); });
        return t;
    }

    private View tile(Drawable icon, String name, View.OnClickListener click) {
        LinearLayout t = column();
        t.addView(new ImageView(this), new LinearLayout.LayoutParams(dp(48), dp(48)));
        t.addView(label(name));
        bindTile(t, icon, name, click);
        return t;
    }

private void bindTile(View tile, Drawable icon, String name, View.OnClickListener click) {
    LinearLayout t = (LinearLayout) tile;
    ((ImageView) t.getChildAt(0)).setImageDrawable(icon);
    if (t.getChildCount() > 1 && t.getChildAt(1) instanceof TextView) {
        ((TextView) t.getChildAt(1)).setText(name);
    }
    t.setContentDescription(name);
    t.setOnClickListener(click);
}

    private TextView sectionLabel(String s) {
        TextView t = text(s, 13, DIM);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(14), 0, dp(4));
        return t;
    }

    private LinearLayout column() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        int padV = isCompactPanel() ? 0 : dp(4);
        t.setPadding(0, padV, 0, padV);
        return t;
    }

    private TextView label(String s) {
        TextView l = text(s, 12, TEXT);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(2), dp(3), dp(2), 0);
        return l;
    }

private TextView mediaButton(String s, boolean wide, boolean five) {
    boolean compact = isCompactPanel();
    TextView b = text(s, compact ? 14 : (five ? (wide ? 20 : 16) : (wide ? 24 : 22)), TEXT);
    b.setGravity(Gravity.CENTER);
    b.setSingleLine(true);
    b.setMinWidth(dp(compact ? 28 : (five ? (wide ? 44 : 38) : (wide ? 56 : 50))));
    b.setMinHeight(dp(compact ? 26 : 34));
    b.setPadding(0, dp(2), 0, dp(2));
    return b;
}

    private TextView control(String s) {
        TextView b = text(s, 18, TEXT);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(52));
        b.setPadding(0, dp(6), 0, dp(2));
        return b;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        style(t, sp, color);
        return t;
    }

    private void style(TextView t, int sp, int color) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * TEXT_SCALE);
        t.setTextColor(color);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams marginTop(int width, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private static final float UI_SCALE = 1.275f;
    private static final float TEXT_SCALE = UI_SCALE * 1.3f;

    private int dp(int v) {
        return Math.round(v * UI_SCALE);
    }

    private int sysDp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
