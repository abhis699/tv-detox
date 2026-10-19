package com.ansub.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.app.AlertDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    // Top-row apps, in this order of preference:
    //  1. a list saved at install time:  am start -n com.ansub.tvhome/.MainActivity --es apps "pkg1,pkg2"
    //     (--es apps auto  goes back to 2)
    //  2. apps.txt (built in as Apps.LIST), topped up with other TV apps installed on this TV.
    // Saved app order is retained; all launchable apps are shown.
    private static final String PREFS = "config";
    private static final List<String> NOT_APPS = Arrays.asList("com.android.vending", "com.android.tv.settings",
            "com.android.tv", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher");
    private static final String PLAY_STORE = "com.android.vending";
    private static final String SETTINGS = "settings";
    private static final int PER_ROW = 6, MAX_INPUTS = 4;
    // Shared, translucent focus tint: no bitmap, blur layer, or per-focus allocation.
    private static final PorterDuffColorFilter FOCUS_GLOW =
            new PorterDuffColorFilter(0x1675D7FF, PorterDuff.Mode.SRC_ATOP);

    // Layout is designed at 1920x1080 and scaled to the TV's real UI size by px().
    private static final int MARGIN = 96, GAP = 36, RADIUS = 22;
    private float scale = 1f;

    private static Typeface regular, semibold;

    private FrameLayout root;
    private LinearLayout appGrid;
    private LinearLayout importantRow;
    private HorizontalScrollView appScroll;
    private ImageView wifiStatusView;
    private WifiStatusIcon wifiStatusIcon;
    private final List<View> tiles = new ArrayList<>();
    private final List<View> appTiles = new ArrayList<>();
    private final List<View> importantTiles = new ArrayList<>();
    private TextView manageButton;
    private TextView moveHint;
    private boolean dirty = true;
    private String focusedKey;
    private boolean moving;
    private String movingKey;
    private List<String> moveOrder;
    private boolean focusFirstOnResume = true;

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { dirty = true; }
    };
    private final BroadcastReceiver wifiChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateWifiStatus(); }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        saveConfig(getIntent());
        loadFonts();
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        getWindow().setBackgroundDrawable(background());

        root = new FrameLayout(this);
        root.setClipChildren(false);

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        TextClock date = clock("EEEE, d MMMM", "EEEE, d MMMM", px(30), 0xA8FFFFFF, regular);
        TextClock time = clock("h:mm", "H:mm", px(176), Color.WHITE, semibold);
        time.setLetterSpacing(-0.028f);
        time.setFontFeatureSettings("tnum");
        time.setIncludeFontPadding(false);
        clock.addView(date);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = px(-20); // pull the big digits up under the date
        clock.addView(time, tp);
        root.addView(clock, at(px(MARGIN), px(92), -2, -2));

        TextView settingsButton = text(px(42), 0xE6FFFFFF, regular);
        settingsButton.setText("⚙");
        settingsButton.setGravity(Gravity.CENTER);
        settingsButton.setFocusable(true);
        settingsButton.setId(View.generateViewId());
        settingsButton.setContentDescription("TV Settings");
        GradientDrawable settingsBg = new GradientDrawable();
        settingsBg.setShape(GradientDrawable.OVAL);
        settingsBg.setColor(0x13FFFFFF);
        GradientDrawable settingsRing = new GradientDrawable();
        settingsRing.setShape(GradientDrawable.OVAL);
        settingsRing.setColor(Color.TRANSPARENT);
        settingsRing.setStroke(px(2), Color.TRANSPARENT);
        settingsButton.setBackground(new LayerDrawable(new Drawable[] {
                settingsBg, new InsetDrawable(settingsRing, px(5))
        }));
        settingsButton.setOnFocusChangeListener((v, has) -> {
            settingsBg.setColor(has ? 0x2875D7FF : 0x13FFFFFF);
            settingsRing.setStroke(px(2), has ? 0xFF75D7FF : Color.TRANSPARENT);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(140).start();
        });
        settingsButton.setOnClickListener(v -> open(SETTINGS));
        FrameLayout.LayoutParams sb = new FrameLayout.LayoutParams(px(76), px(76), Gravity.TOP | Gravity.RIGHT);
        sb.topMargin = px(82);
        sb.rightMargin = px(MARGIN);
        root.addView(settingsButton, sb);

        wifiStatusIcon = new WifiStatusIcon();
        wifiStatusView = new ImageView(this);
        // Keep the outer focus circle the same size as Settings while drawing
        // the Wi-Fi glyph at roughly the same visual size as the gear.
        wifiStatusView.setImageDrawable(new InsetDrawable(wifiStatusIcon, px(22)));
        wifiStatusView.setFocusable(true);
        wifiStatusView.setId(View.generateViewId());
        wifiStatusView.setContentDescription("Wi-Fi status");
        GradientDrawable wifiBg = new GradientDrawable();
        wifiBg.setShape(GradientDrawable.OVAL);
        wifiBg.setColor(0x13FFFFFF);
        GradientDrawable wifiRing = new GradientDrawable();
        wifiRing.setShape(GradientDrawable.OVAL);
        wifiRing.setColor(Color.TRANSPARENT);
        wifiRing.setStroke(px(2), Color.TRANSPARENT);
        wifiStatusView.setBackground(new LayerDrawable(new Drawable[] {
                wifiBg, new InsetDrawable(wifiRing, px(5))
        }));
        wifiStatusView.setOnFocusChangeListener((v, has) -> {
            wifiBg.setColor(has ? 0x2875D7FF : 0x13FFFFFF);
            wifiRing.setStroke(px(2), has ? 0xFF75D7FF : Color.TRANSPARENT);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(140).start();
        });
        wifiStatusView.setOnClickListener(v -> openWifiSettings());
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(px(76), px(76), Gravity.TOP | Gravity.RIGHT);
        wp.topMargin = px(82);
        wp.rightMargin = px(MARGIN + 100);
        root.addView(wifiStatusView, wp);
        settingsButton.setNextFocusLeftId(wifiStatusView.getId());
        wifiStatusView.setNextFocusRightId(settingsButton.getId());
        updateWifiStatus();

        appScroll = new HorizontalScrollView(this);
        appScroll.setHorizontalScrollBarEnabled(false);
        appScroll.setFillViewport(false);
        appScroll.setClipChildren(false);
        appGrid = new LinearLayout(this);
        appGrid.setOrientation(LinearLayout.HORIZONTAL);
        appGrid.setClipChildren(false);
        appScroll.addView(appGrid, new HorizontalScrollView.LayoutParams(-2, -2));
        FrameLayout.LayoutParams sp = at(0, px(588), -1, px(230));
        root.addView(appScroll, sp);
        importantRow = new LinearLayout(this);
        importantRow.setOrientation(LinearLayout.HORIZONTAL);
        importantRow.setClipChildren(false);
        root.addView(importantRow, at(0, px(828), -1, -2));
        moveHint = text(px(24), 0xE0FFFFFF, regular);
        moveHint.setText("Move app:  ← / →   OK saves position");
        moveHint.setVisibility(View.GONE);
        root.addView(moveHint, at(px(MARGIN), px(500), -2, -2));
        setContentView(root);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(packagesChanged, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(packagesChanged, f);
        IntentFilter wf = new IntentFilter();
        wf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        wf.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(wifiChanged, wf, Context.RECEIVER_EXPORTED);
        else registerReceiver(wifiChanged, wf);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        unregisterReceiver(wifiChanged);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        focusFirstOnResume = true;
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        focusFirstOnResume = true;
        if (saveConfig(intent)) buildTiles();
        focusFirstApp();
    }

    // Saves an app list passed with --es apps "...". Returns true if it changed.
    private boolean saveConfig(Intent intent) {
        String apps = intent == null ? null : intent.getStringExtra("apps");
        if (apps == null) return false;
        apps = apps.trim();
        SharedPreferences.Editor e = getSharedPreferences(PREFS, 0).edit();
        if (apps.isEmpty() || apps.equalsIgnoreCase("auto")) e.remove("apps");
        else e.putString("apps", apps);
        e.apply();
        dirty = true;
        return true;
    }

    private List<String> appList(PackageManager pm) {
        List<String> out = new ArrayList<>();
        SharedPreferences prefs = getSharedPreferences(PREFS, 0);
        String hidden = prefs.getString("hidden", "");
        List<String> hiddenApps = Arrays.asList(hidden.split(","));
        String saved = getSharedPreferences(PREFS, 0).getString("apps", null);
        String[] wanted = saved != null ? saved.split("[,\\s]+") : Apps.LIST;
        for (String p : wanted) if (!p.isEmpty() && !out.contains(p) && launchIntent(pm, p) != null) out.add(p);
        // Enumerate both TV and phone launcher activities; only apps with a launcher entry/icon belong here.
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (!out.contains(p) && !NOT_APPS.contains(p) && !p.equals(getPackageName())) out.add(p);
            }
        }
        out.removeAll(hiddenApps);
        String order = moving && moveOrder != null ? join(moveOrder) : prefs.getString("order", "");
        if (!order.isEmpty()) {
            List<String> sorted = new ArrayList<>();
            for (String p : order.split(",")) if (out.contains(p) && !sorted.contains(p)) sorted.add(p);
            for (String p : out) if (!sorted.contains(p)) sorted.add(p);
            return sorted;
        }
        return out;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateWifiStatus();
        // Rebuild only when apps were installed, removed or updated.
        if (dirty) {
            dirty = false;
            buildTiles();
        }
        if (focusFirstOnResume) {
            focusFirstOnResume = false;
            focusFirstApp();
        } else if (getCurrentFocus() == null && !appTiles.isEmpty()) appTiles.get(0).requestFocus();
    }

    @Override
    public void onBackPressed() { /* home screen: nothing to go back to */ }

    // Static warm gradient, drawn once by the window: no extra view, no animation.
    private Drawable background() {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF15100F, 0xFF0A0809, 0xFF050405});
        GradientDrawable warm = radial(0x36FF8048, 0.08f, -0.12f, px(1250));
        GradientDrawable plum = radial(0x2EAA3C8C, 1.0f, 1.1f, px(1000));
        for (GradientDrawable g : new GradientDrawable[] {base, warm, plum}) g.setDither(true);
        return new LayerDrawable(new Drawable[] {base, warm, plum});
    }

    private static GradientDrawable radial(int color, float cx, float cy, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[] {color, color & 0x00FFFFFF});
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(r);
        return g;
    }

    private void buildTiles() {
        appGrid.removeAllViews();
        importantRow.removeAllViews();
        if (manageButton != null) root.removeView(manageButton);
        tiles.clear();
        appTiles.clear();
        importantTiles.clear();

        PackageManager pm = getPackageManager();
        int m = px(MARGIN), g = px(GAP);
        int w = (getResources().getDisplayMetrics().widthPixels - 2 * m - g * (PER_ROW - 1)) / PER_ROW;
        int h = w * 9 / 16;

        List<String> apps = appList(pm);
        for (String pkg : apps) {
            Intent li = launchIntent(pm, pkg);
            if (li == null) continue;
            Drawable banner = null;
            try {
                banner = pm.getActivityBanner(li);
                if (banner == null) banner = pm.getApplicationBanner(pkg);
            } catch (Exception ignored) {}
            String label = pkg;
            try { label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) {}
            // Apps without a TV banner get a plain tile with their normal icon.
            Drawable icon = null;
            if (banner == null) try { icon = pm.getApplicationIcon(pkg); } catch (Exception ignored) {}
            addTile(appGrid, pkg, label, banner, null, icon, w, h, g, true);
        }
        if (!apps.contains(PLAY_STORE) && launchIntent(pm, PLAY_STORE) != null)
            addTile(importantRow, PLAY_STORE, "Play Store", null, LineIcon.PLAY, null, w, h, g, false);
        for (TvInputInfo in : hdmiInputs()) {
            addTile(importantRow, in.getId(), inputLabel(in), null, LineIcon.HDMI, null, w, h, g, false);
        }
        addTile(importantRow, SETTINGS, "Settings", null, LineIcon.SETTINGS, null, w, h, g, false);
        manageButton = text(px(24), 0xE0FFFFFF, regular);
        manageButton.setId(View.generateViewId());
        manageButton.setText("Manage hidden apps"); manageButton.setGravity(Gravity.CENTER); manageButton.setFocusable(true);
        manageButton.setOnClickListener(v -> manageHiddenApps());
        manageButton.setOnLongClickListener(v -> { manageHiddenApps(); return true; });
        root.addView(manageButton, at(m, px(1000), px(360), px(70)));

        if (!appTiles.isEmpty() && !importantTiles.isEmpty()) {
            int firstApp = appTiles.get(0).getId();
            View lastApp = appTiles.get(appTiles.size() - 1);
            for (View app : appTiles) app.setNextFocusDownId(importantTiles.get(0).getId());
            appTiles.get(0).setNextFocusLeftId(firstApp);
            lastApp.setNextFocusRightId(lastApp.getId());
            for (int i = 0; i < importantTiles.size(); i++) {
                View option = importantTiles.get(i);
                option.setNextFocusUpId(firstApp);
                option.setNextFocusDownId(manageButton.getId());
            }
            importantTiles.get(0).setNextFocusLeftId(importantTiles.get(0).getId());
            View lastOption = importantTiles.get(importantTiles.size() - 1);
            lastOption.setNextFocusRightId(lastOption.getId());
            if (!importantTiles.isEmpty()) manageButton.setNextFocusUpId(importantTiles.get(importantTiles.size() - 1).getId());
        }

        View restore = null;
        for (View t : tiles) if (t.getTag().equals(focusedKey)) restore = t;
        if (restore != null) {
            final View target = restore;
            target.requestFocus();
            if (appTiles.contains(target)) appScroll.post(() -> scrollTileIntoView(target));
        }
    }

    private void focusFirstApp() {
        if (appTiles.isEmpty()) return;
        View first = appTiles.get(0);
        focusedKey = (String) first.getTag();
        first.requestFocus();
        appScroll.post(() -> scrollTileIntoView(first));
    }

    private void scrollTileIntoView(View tile) {
        if (tile.getParent() == null || !tile.hasFocus()) return;
        Rect rect = new Rect();
        tile.getDrawingRect(rect);
        appScroll.offsetDescendantRectToMyCoords(tile, rect);
        int target = appScroll.getScrollX();
        int edge = px(MARGIN);
        if (rect.left < target + edge) target = rect.left - edge;
        else if (rect.right > target + appScroll.getWidth() - edge)
            target = rect.right - appScroll.getWidth() + edge;
        appScroll.smoothScrollTo(Math.max(0, target), 0);
    }

    private void addTile(LinearLayout row, final String key, String label, Drawable banner, int[] lineIcon,
                         Drawable appIcon, int w, int h, int gap, boolean appTile) {
        FrameLayout card = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        final ImageView bannerImage = banner == null ? null : new ImageView(this);
        bg.setCornerRadius(px(RADIUS));
        if (banner != null) {
            bg.setColor(0xFF1E1A1C);
            bannerImage.setImageDrawable(fit(banner, w, h));
            card.addView(bannerImage, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // Quiet "glass" tile: icon above label.
            bg.setColor(0x13FFFFFF);
            bg.setStroke(Math.max(1, px(2)), 0x17FFFFFF);
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            int size = lineIcon != null ? px(44) : px(64);
            if (lineIcon != null) iv.setImageDrawable(new LineIcon(lineIcon));
            else if (appIcon != null) iv.setImageDrawable(fit(appIcon, size, size));
            inner.addView(iv, new LinearLayout.LayoutParams(size, size));
            TextView t = text(px(24), 0xE0FFFFFF, regular);
            t.setText(label);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(16), 0, px(16), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = px(12);
            inner.addView(t, lp);
            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        }
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setId(View.generateViewId());
        card.setTag(key);

        // Banner tiles show their name underneath when focused.
        final TextView caption = banner == null ? null : text(px(24), Color.WHITE, regular);
        if (caption != null) {
            caption.setText(label);
            caption.setGravity(Gravity.CENTER_HORIZONTAL);
            caption.setVisibility(View.INVISIBLE);
        }

        final boolean glass = banner == null;
        card.setOnFocusChangeListener((v, has) -> {
            if (has) focusedKey = key;
            if (has && appTile) {
                appScroll.post(() -> scrollTileIntoView(v));
            }
            boolean selectedForMove = has && moving && key.equals(movingKey);
            if (glass) bg.setColor(has
                    ? (selectedForMove ? 0x4075D7FF : 0x2675D7FF) : 0x13FFFFFF);
            if (bannerImage != null) bannerImage.setColorFilter(has ? FOCUS_GLOW : null);
            bg.setStroke(selectedForMove ? px(4) : has ? px(3) : Math.max(1, px(2)),
                    selectedForMove ? 0xFFFFC27A : has ? 0xFF75D7FF : 0x17FFFFFF);
            float lift = selectedForMove ? 1.22f : has ? 1.16f : 1f;
            v.animate().scaleX(lift).scaleY(lift).translationZ(has ? (selectedForMove ? px(40) : px(26)) : 0)
                    .setDuration(140).withLayer().start();
            if (caption != null) caption.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        });
        card.setOnClickListener(v -> open(key));
        final boolean[] longPressTriggered = {false};
        final Runnable markLongPress = () -> longPressTriggered[0] = true;
        card.setOnKeyListener((v, code, ev) -> {
            boolean arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
            if (moving && arrow) {
                if (ev.getAction() == KeyEvent.ACTION_UP
                        && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) moveApp(key, code);
                return true;
            }
            boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (select && ev.getAction() == KeyEvent.ACTION_DOWN) {
                if (ev.getRepeatCount() == 0) {
                    longPressTriggered[0] = false;
                    if (appTile && !moving) v.postDelayed(markLongPress, ViewConfiguration.getLongPressTimeout());
                }
                return true;
            }
            if (select && ev.getAction() == KeyEvent.ACTION_UP) {
                v.removeCallbacks(markLongPress);
                if (longPressTriggered[0]) {
                    longPressTriggered[0] = false;
                    showAppActions(key);
                } else if (moving) {
                    finishMove();
                } else {
                    open(key);
                }
                return true;
            }
            return false;
        });

        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.addView(card, new FrameLayout.LayoutParams(w, h));
        if (caption != null) {
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(w, -2);
            cp.topMargin = h + px(22);
            wrap.addView(caption, cp);
        }
        LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(w, h + (caption == null ? 0 : px(56)));
        tileLp.leftMargin = row.getChildCount() == 0 ? px(MARGIN) : gap;
        tileLp.topMargin = px(16);
        row.addView(wrap, tileLp);
        tiles.add(card);
        if (appTile) appTiles.add(card); else importantTiles.add(card);
    }

    private void showAppActions(String pkg) {
        new AlertDialog.Builder(this).setTitle("App options")
                .setItems(new String[] {"Move app", "Hide app", "App info"}, (d, which) -> {
                    if (which == 0) {
                        moveOrder = new ArrayList<>(appList(getPackageManager()));
                        moving = true;
                        movingKey = pkg;
                        focusedKey = pkg;
                        moveHint.setVisibility(View.VISIBLE);
                        buildTiles();
                    }
                    else if (which == 1) hideApp(pkg);
                    else showAppInfo(pkg);
                }).show();
    }

    private void showAppInfo(String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        try { startActivity(i); } catch (Exception ignored) {}
    }

    private void moveApp(String pkg, int direction) {
        if (moveOrder == null) return;
        int from = moveOrder.indexOf(pkg);
        if (from < 0 || from >= appTiles.size()) return;
        int step = direction == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : direction == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        if (step == 0) return;
        int to = Math.max(0, Math.min(appTiles.size() - 1, from + step));
        if (to == from) return;

        // Reorder the existing wrapper and tile views in place. Rebuilding here
        // re-queries PackageManager and recreates every app's banner bitmap for
        // every remote key press.
        View tile = appTiles.get(from);
        View wrapper = (View) tile.getParent();
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) wrapper.getLayoutParams();
        appGrid.removeViewAt(from);
        appGrid.addView(wrapper, to, params);
        Collections.swap(moveOrder, from, to);
        Collections.swap(appTiles, from, to);
        for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
            View item = appGrid.getChildAt(i);
            LinearLayout.LayoutParams itemParams = (LinearLayout.LayoutParams) item.getLayoutParams();
            itemParams.leftMargin = i == 0 ? px(MARGIN) : px(GAP);
            item.setLayoutParams(itemParams);
        }

        for (View app : appTiles) {
            app.setNextFocusLeftId(View.NO_ID);
            app.setNextFocusRightId(View.NO_ID);
        }
        appTiles.get(0).setNextFocusLeftId(appTiles.get(0).getId());
        View lastApp = appTiles.get(appTiles.size() - 1);
        lastApp.setNextFocusRightId(lastApp.getId());

        focusedKey = pkg;
        tile.requestFocus();
        appScroll.post(() -> scrollTileIntoView(tile));
    }

    private void finishMove() {
        if (moveOrder != null) getSharedPreferences(PREFS, 0).edit().putString("order", join(moveOrder)).apply();
        moving = false;
        movingKey = null;
        moveOrder = null;
        moveHint.setVisibility(View.GONE);
        buildTiles();
    }

    private void hideApp(String pkg) {
        SharedPreferences p = getSharedPreferences(PREFS, 0);
        List<String> hidden = new ArrayList<>();
        String old = p.getString("hidden", "");
        if (!old.isEmpty()) hidden.addAll(Arrays.asList(old.split(",")));
        if (!hidden.contains(pkg)) hidden.add(pkg);
        p.edit().putString("hidden", join(hidden)).apply();
        focusedKey = null;
        buildTiles();
    }

    private void manageHiddenApps() {
        PackageManager pm = getPackageManager();
        List<String> all = new ArrayList<>();
        for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!all.contains(pkg) && !NOT_APPS.contains(pkg) && !pkg.equals(getPackageName())) all.add(pkg);
            }
        }
        Collections.sort(all, (a, b) -> label(pm, a).compareToIgnoreCase(label(pm, b)));
        String[] labels = new String[all.size()]; boolean[] checked = new boolean[all.size()];
        List<String> hidden = Arrays.asList(getSharedPreferences(PREFS, 0).getString("hidden", "").split(","));
        for (int i = 0; i < all.size(); i++) { labels[i] = label(pm, all.get(i)); checked[i] = !hidden.contains(all.get(i)); }
        new AlertDialog.Builder(this).setTitle("Apps (checked apps are visible)")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Done", (d, which) -> {
                    List<String> keepHidden = new ArrayList<>();
                    for (int i = 0; i < all.size(); i++) if (!checked[i]) keepHidden.add(all.get(i));
                    getSharedPreferences(PREFS, 0).edit().putString("hidden", join(keepHidden)).apply();
                    buildTiles();
                }).show();
    }

    private String label(PackageManager pm, String pkg) {
        try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) { if (out.length() > 0) out.append(','); out.append(value); }
        return out.toString();
    }

    private void open(String key) {
        Intent i;
        if (SETTINGS.equals(key)) {
            i = new Intent(Settings.ACTION_SETTINGS);
        } else if (isInput(key)) {
            i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(key));
            // Prefer the stock Live TV app when it's there; otherwise let the TV pick.
            Intent live = new Intent(i).setClassName("com.android.tv", "com.android.tv.MainActivity");
            if (live.resolveActivity(getPackageManager()) != null) i = live;
        } else {
            i = launchIntent(getPackageManager(), key);
            if (i == null) return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    private void updateWifiStatus() {
        if (wifiStatusIcon == null) return;
        boolean connected = false;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                WifiInfo info = wm.getConnectionInfo();
                connected = info != null && info.getNetworkId() >= 0
                        && info.getSupplicantState() == SupplicantState.COMPLETED;
            }
        } catch (Exception ignored) {}
        wifiStatusIcon.setConnected(connected);
        wifiStatusView.setContentDescription(connected ? "Wi-Fi connected" : "Wi-Fi disconnected");
    }

    private void openWifiSettings() {
        try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
        catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
            catch (Exception ignored) { open(SETTINGS); }
        }
    }

    private static boolean isInput(String key) {
        return key.indexOf('/') > 0;
    }

    // The TV's own HDMI inputs (not devices behind them), in order, skipping hidden ones.
    private List<TvInputInfo> hdmiInputs() {
        List<TvInputInfo> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        for (TvInputInfo in : tim.getTvInputList()) {
            if (in.getType() == TvInputInfo.TYPE_HDMI && in.getParentId() == null && !in.isHidden(this)) out.add(in);
        }
        Collections.sort(out, (a, b) -> a.getId().compareTo(b.getId()));
        return out.size() > MAX_INPUTS ? out.subList(0, MAX_INPUTS) : out;
    }

    // The name set in Settings > Inputs (like "Bedroom"), else the TV's own name (like "HDMI 1").
    private String inputLabel(TvInputInfo in) {
        CharSequence custom = in.loadCustomLabel(this);
        if (custom != null && custom.length() > 0) return custom.toString();
        CharSequence label = in.loadLabel(this);
        return label != null && label.length() > 0 ? label.toString() : "HDMI";
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    // Xiaomi TVs ship MiLan Pro but don't register it by name; every other TV gets Roboto.
    private static void loadFonts() {
        if (regular != null) return;
        regular = fromFile("/system/fonts/MILanPro_Normal.ttf", Typeface.create("sans-serif", Typeface.NORMAL));
        semibold = fromFile("/system/fonts/MILanPro_SemiBold.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    private static Typeface fromFile(String path, Typeface fallback) {
        try { return new File(path).exists() ? Typeface.createFromFile(path) : fallback; }
        catch (Exception e) { return fallback; }
    }

    // Draws a banner once into a tile-sized bitmap (center-cropped). Some apps ship huge banners
    // that Android 9 refuses to draw ("Canvas: trying to draw too large bitmap"); this also saves RAM.
    private Drawable fit(Drawable d, int w, int h) {
        try {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) { iw = w; ih = h; }
            float s = Math.max((float) w / iw, (float) h / ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
            d.draw(new Canvas(b));
            return new BitmapDrawable(getResources(), b);
        } catch (Throwable t) {
            return d;
        }
    }

    private int px(int v) {
        return Math.round(v * scale);
    }

    private TextClock clock(String f12, String f24, int px, int color, Typeface tf) {
        TextClock c = new TextClock(this);
        c.setFormat12Hour(f12);
        c.setFormat24Hour(f24);
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        c.setTextColor(color);
        c.setTypeface(tf);
        return c;
    }

    private TextView text(int px, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    private static FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }

    private static final class WifiStatusIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean connected;

        WifiStatusIcon() {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setConnected(boolean value) {
            if (connected != value) {
                connected = value;
                invalidateSelf();
            }
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float size = Math.min(bounds.width(), bounds.height());
            float cx = bounds.centerX();
            float cy = bounds.top + size * 0.76f;
            float stroke = size * 0.065f;
            paint.setStrokeWidth(stroke);
            paint.setColor(connected ? 0xFF7CE5B1 : 0xFFB9B1B0);
            for (float radius : new float[] {0.62f, 0.43f, 0.24f}) {
                float r = size * radius;
                canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 225, 90, false, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, bounds.top + size * 0.90f, size * 0.065f, paint);
            if (!connected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(size * 0.09f);
                paint.setColor(0xFFFF756E);
                canvas.drawLine(cx - size * 0.34f, bounds.top + size * 0.36f,
                        cx + size * 0.34f, bounds.top + size * 0.84f, paint);
            }
            paint.setStyle(Paint.Style.STROKE);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return 44; }
        @Override public int getIntrinsicHeight() { return 44; }
    }
}
