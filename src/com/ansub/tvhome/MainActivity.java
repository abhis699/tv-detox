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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.ScrollView;
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

    // Layout is designed at 1920x1080 and scaled to the TV's real UI size by px().
    private static final int MARGIN = 96, GAP = 36, RADIUS = 22;
    private static final int ROW1_TOP = 604, ROW2_TOP = 828;
    private float scale = 1f;

    private static Typeface regular, semibold;

    private FrameLayout root;
    private FrameLayout appGrid;
    private ScrollView appScroll;
    private final List<View> tiles = new ArrayList<>();
    private boolean dirty = true;
    private String focusedKey;
    private boolean moving;

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { dirty = true; }
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
        settingsButton.setContentDescription("TV Settings");
        GradientDrawable settingsBg = new GradientDrawable();
        settingsBg.setShape(GradientDrawable.OVAL);
        settingsBg.setColor(0x13FFFFFF);
        settingsButton.setBackground(settingsBg);
        settingsButton.setOnFocusChangeListener((v, has) -> {
            settingsBg.setColor(has ? 0x38FFFFFF : 0x13FFFFFF);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(140).start();
        });
        settingsButton.setOnClickListener(v -> open(SETTINGS));
        FrameLayout.LayoutParams sb = new FrameLayout.LayoutParams(px(76), px(76), Gravity.TOP | Gravity.RIGHT);
        sb.topMargin = px(82);
        sb.rightMargin = px(MARGIN);
        root.addView(settingsButton, sb);

        appScroll = new ScrollView(this);
        appScroll.setFillViewport(false);
        appScroll.setClipChildren(false);
        appGrid = new FrameLayout(this);
        appGrid.setClipChildren(false);
        appScroll.addView(appGrid, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams sp = at(0, px(500), -1, -1);
        root.addView(appScroll, sp);
        setContentView(root);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(packagesChanged, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(packagesChanged, f);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (saveConfig(intent)) buildTiles();
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
        String order = prefs.getString("order", "");
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
        // Rebuild only when apps were installed, removed or updated.
        if (dirty) {
            dirty = false;
            buildTiles();
        }
        View f = getCurrentFocus();
        if (f == null && !tiles.isEmpty()) tiles.get(0).requestFocus();
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
        tiles.clear();

        PackageManager pm = getPackageManager();
        int m = px(MARGIN), g = px(GAP);
        int w = (getResources().getDisplayMetrics().widthPixels - 2 * m - g * (PER_ROW - 1)) / PER_ROW;
        int h = w * 9 / 16;

        int col = 0, row = 0;
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
            int x = m + col * (w + g);
            int y = px(16) + row * (h + px(76));
            addTile(pkg, label, banner, null, icon, x, y, w, h);
            if (++col == PER_ROW) { col = 0; row++; }
        }
        int y = px(16) + row * (h + px(76));
        col = 0;
        if (!apps.contains(PLAY_STORE) && launchIntent(pm, PLAY_STORE) != null)
            addTile(PLAY_STORE, "Play Store", null, LineIcon.PLAY, null, m + col++ * (w + g), y, w, h);
        for (TvInputInfo in : hdmiInputs()) {
            if (col == PER_ROW) { col = 0; y += h + px(76); }
            addTile(in.getId(), inputLabel(in), null, LineIcon.HDMI, null, m + col++ * (w + g), y, w, h);
        }
        if (col == PER_ROW) { col = 0; y += h + px(76); }
        addTile(SETTINGS, "Settings", null, LineIcon.SETTINGS, null, m + col * (w + g), y, w, h);
        TextView manage = text(px(24), 0xE0FFFFFF, regular);
        manage.setText("Manage hidden apps"); manage.setGravity(Gravity.CENTER); manage.setFocusable(true);
        manage.setOnClickListener(v -> manageHiddenApps());
        manage.setOnLongClickListener(v -> { manageHiddenApps(); return true; });
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(px(360), px(70));
        mp.leftMargin = m; mp.topMargin = y + h + px(24);
        appGrid.addView(manage, mp);

        View restore = null;
        for (View t : tiles) if (t.getTag().equals(focusedKey)) restore = t;
        if (restore != null) restore.requestFocus();
    }

    private void addTile(final String key, String label, Drawable banner, int[] lineIcon, Drawable appIcon,
                         int x, int y, int w, int h) {
        FrameLayout card = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));
        if (banner != null) {
            bg.setColor(0xFF1E1A1C);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(fit(banner, w, h));
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
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
            if (glass) bg.setColor(has ? 0x38FFFFFF : 0x13FFFFFF);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? px(26) : 0)
                    .setDuration(140).withLayer().start();
            if (caption != null) caption.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        });
        card.setOnClickListener(v -> open(key));
        if (!isInput(key) && !SETTINGS.equals(key)) {
            card.setLongClickable(true);
            card.setOnLongClickListener(v -> { showAppActions(key); return true; });
        }
        card.setOnKeyListener((v, code, ev) -> {
            if (moving && ev.getAction() == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_DPAD_LEFT
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
                moveApp(key, code); return true;
            }
            if (ev.getAction() == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                open(key);
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
        appGrid.addView(wrap, at(x, y, w, -2));
        tiles.add(card);
    }

    private void showAppActions(String pkg) {
        new AlertDialog.Builder(this).setTitle("App options")
                .setItems(new String[] {"Move app", "Hide app"}, (d, which) -> {
                    if (which == 0) { moving = true; focusedKey = pkg; }
                    else hideApp(pkg);
                }).show();
    }

    private void moveApp(String pkg, int direction) {
        List<String> list = appList(getPackageManager());
        int from = list.indexOf(pkg);
        if (from < 0) { moving = false; return; }
        int step = direction == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : direction == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                : direction == KeyEvent.KEYCODE_DPAD_UP ? -PER_ROW : PER_ROW;
        int to = Math.max(0, Math.min(list.size() - 1, from + step));
        if (to == from) return;
        Collections.swap(list, from, to);
        getSharedPreferences(PREFS, 0).edit().putString("order", join(list)).apply();
        moving = false;
        focusedKey = pkg;
        dirty = true;
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
}
