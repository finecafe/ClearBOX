package com.boxcleaner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.Checkable;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Box Cleaner - dọn ứng dụng rác và cache trên Android box (Android 8.1).
 *
 * Với quyền root (box userdebug thường có sẵn "su"): vô hiệu hóa / gỡ app hệ thống, xóa cache toàn bộ.
 * Không có root: chỉ mở màn hình cài đặt từng app để bạn tự tắt/gỡ.
 *
 * Danh sách bảo vệ gồm hai phần:
 *  - Phần lõi cố định (PROTECTED_PREFIXES, launcher, bàn phím đang dùng): không thể bỏ.
 *  - Phần do người dùng ghim thêm: lưu trong SharedPreferences, thêm/bỏ ngay trong app.
 */
public class MainActivity extends Activity {

    // Các gói quan trọng: KHÔNG cho phép chọn, tránh làm box treo logo hoặc mất điều khiển.
    private static final String[] PROTECTED_PREFIXES = {
            "com.android.systemui", "com.android.settings", "com.android.providers.",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.shell", "com.android.phone", "com.android.server.",
            "com.android.bluetooth", "com.android.inputdevices", "com.android.keychain",
            "com.android.certinstaller", "com.android.externalstorage", "com.android.documentsui",
            "com.android.defcontainer", "com.android.location", "com.android.webview",
            "com.google.android.webview", "com.android.nfc", "com.android.managedprovisioning",
            "com.android.proxyhandler", "com.android.statementservice", "com.android.carrierconfig",
            "com.android.sharedstoragebackup", "com.android.backupconfirm", "com.android.pacprocessor",
            "com.android.captiveportallogin", "com.android.vending",
            "com.google.android.gms", "com.google.android.gsf", "com.google.android.tv",
            "com.google.android.leanbacklauncher", "com.android.tv", "com.android.launcher",
            // Phần mềm điều khiển phần cứng của box dùng chip Amlogic (p281)
            "com.droidlogic", "com.amlogic", "com.boxcleaner"
    };

    private static final String PREFS = "boxcleaner";
    private static final String KEY_PINNED = "pinned";

    private static final Pattern SAFE_PKG = Pattern.compile("[A-Za-z0-9._]+");

    // Hai cách gọi su khác nhau tùy bản root; ô cuối là chỗ chèn lệnh.
    private static final String[][] SU_VARIANTS = {
            {"su", "-c", ""},
            {"su", "0", "sh", "-c", ""}
    };
    private static int suVariant = 0;

    private static class AppItem {
        String pkg;
        String label;
        boolean system;
        boolean launchable;
        boolean disabled;
        boolean removed;
        boolean protectedApp;
        boolean pinned; // do người dùng ghim bảo vệ (không thuộc phần lõi)
    }

    private static class Result {
        int code = -1;
        String out = "";
    }

    private final List<AppItem> allItems = new ArrayList<>();
    private final List<AppItem> visible = new ArrayList<>();
    private ListView listView;
    private AppAdapter adapter;
    private TextView status;
    private TextView counter;
    private Button modeBtn;
    private boolean showAll = false;
    private volatile boolean rooted = false;

    // ---------------------------------------------------------------- UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // Lề rộng hơn một chút để không bị cắt mép trên TV (overscan)
        root.setPadding(dp(32), dp(16), dp(32), dp(12));

        // Tiêu đề + số app đã chọn
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label("Box Cleaner", 26, R.color.accent, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        counter = label("", 15, R.color.text_dim, false);
        header.addView(counter);
        root.addView(header);

        status = label("Đang kiểm tra quyền root... (nếu có hộp thoại hỏi quyền, hãy chọn Cho phép)",
                15, R.color.text, false);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(4);
        root.addView(status, statusLp);

        root.addView(label("Dùng phím mũi tên để di chuyển, phím OK để chọn hoặc bấm nút.",
                12, R.color.text_dim, false));

        Button disableBtn = button("Vô hiệu hóa đã chọn (khuyên dùng)", R.drawable.btn_primary);
        Button uninstallBtn = button("Gỡ cài đặt đã chọn", R.drawable.btn_danger);
        Button cacheBtn = button("Xóa cache tất cả app", R.drawable.btn_neutral);
        Button restoreBtn = button("Khôi phục app đã tắt/gỡ", R.drawable.btn_neutral);
        Button protectBtn = button("Bảo vệ app đã chọn", R.drawable.btn_neutral);
        Button manageBtn = button("Quản lý danh sách bảo vệ", R.drawable.btn_neutral);
        modeBtn = button("", R.drawable.btn_neutral);
        Button selectBtn = button("Chọn / bỏ chọn tất cả", R.drawable.btn_neutral);

        addButtonGrid(root, disableBtn, uninstallBtn, cacheBtn, restoreBtn,
                protectBtn, manageBtn, modeBtn, selectBtn);

        listView = new ListView(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setDivider(null);
        listView.setDividerHeight(dp(6));
        listView.setSelector(R.drawable.row_selector);
        listView.setDrawSelectorOnTop(true);
        adapter = new AppAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, v, position, id) -> {
            if (position < visible.size() && visible.get(position).protectedApp) {
                listView.setItemChecked(position, false);
                toast(visible.get(position).pinned
                        ? "App này do bạn ghim bảo vệ. Muốn bỏ: bấm \"Quản lý danh sách bảo vệ\"."
                        : "Ứng dụng này được bảo vệ, không thể chọn.");
            }
            updateCounter();
        });
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.topMargin = dp(8);
        root.addView(listView, listLp);

        setContentView(root);
        updateModeButton();
        updateCounter();

        modeBtn.setOnClickListener(v -> {
            showAll = !showAll;
            updateModeButton();
            applyFilter();
            if (showAll) {
                toast("Đang hiện cả thành phần hệ thống không có biểu tượng. Hãy cẩn thận!");
            }
        });
        selectBtn.setOnClickListener(v -> toggleSelectAll());
        disableBtn.setOnClickListener(v -> applyToSelected(false));
        uninstallBtn.setOnClickListener(v -> applyToSelected(true));
        cacheBtn.setOnClickListener(v -> clearAllCaches());
        restoreBtn.setOnClickListener(v -> showRestoreDialog());
        protectBtn.setOnClickListener(v -> pinSelected());
        manageBtn.setOnClickListener(v -> showManageProtected());

        disableBtn.requestFocus();

        new Thread(() -> {
            final boolean ok = checkRoot();
            rooted = ok;
            runOnUiThread(() -> {
                status.setText(ok
                        ? "✔ Có quyền root: có thể vô hiệu hóa/gỡ app hệ thống và xóa cache."
                        : "✘ Không có root: chỉ mở được trang cài đặt từng app (tắt/gỡ thủ công).");
                status.setTextColor(color(ok ? R.color.ok_green : R.color.amber));
            });
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
    }

    private void updateModeButton() {
        modeBtn.setText(showAll
                ? "Đang hiện: TẤT CẢ gói (nguy hiểm)"
                : "Đang hiện: app có biểu tượng (an toàn)");
    }

    private void updateCounter() {
        if (listView == null || counter == null) return;
        int n = 0;
        SparseBooleanArray checked = listView.getCheckedItemPositions();
        if (checked != null) {
            for (int i = 0; i < visible.size(); i++) {
                if (checked.get(i)) n++;
            }
        }
        counter.setText("Đã chọn " + n + " / " + visible.size());
    }

    private int color(int res) {
        return getColor(res);
    }

    private TextView label(String text, int sp, int colorRes, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color(colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String text, int backgroundRes) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(color(R.color.text));
        b.setBackgroundResource(backgroundRes);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setFocusable(true);
        return b;
    }

    /** Xếp nút thành lưới: màn hình rộng (TV) 4 cột, màn hình hẹp 2 cột. */
    private void addButtonGrid(LinearLayout parent, Button... buttons) {
        int cols = getResources().getConfiguration().screenWidthDp >= 800 ? 4 : 2;
        for (int i = 0; i < buttons.length; i += cols) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < cols; j++) {
                if (i + j < buttons.length) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                    r.addView(buttons[i + j], lp);
                } else {
                    r.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
                }
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i == 0) rowLp.topMargin = dp(8);
            parent.addView(r, rowLp);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void showMessage(String title, String msg) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton("OK", null).show();
    }

    // ---------------------------------------------------------------- dòng danh sách

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return visible.size();
        }

        @Override
        public Object getItem(int position) {
            return visible.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowView v = convertView instanceof RowView
                    ? (RowView) convertView
                    : new RowView(MainActivity.this);
            v.bind(visible.get(position));
            return v;
        }
    }

    /** Một dòng app: ô chọn, tên, tên gói và nhãn trạng thái. Hỗ trợ trạng thái "đã chọn" của ListView. */
    private class RowView extends LinearLayout implements Checkable {
        private final View box;
        private final TextView title;
        private final TextView sub;
        private final TextView tag;
        private boolean checked;

        RowView(Context c) {
            super(c);
            setOrientation(LinearLayout.HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackgroundResource(R.drawable.row_bg);
            setPadding(dp(14), dp(10), dp(14), dp(10));

            box = new View(c);
            box.setBackgroundResource(R.drawable.check_box);
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(dp(24), dp(24));
            boxLp.rightMargin = dp(14);
            addView(box, boxLp);

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);

            title = new TextView(c);
            title.setTextSize(17);
            title.setTextColor(color(R.color.text));
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(title);

            sub = new TextView(c);
            sub.setTextSize(12);
            sub.setTextColor(color(R.color.text_dim));
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(sub);

            addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            tag = new TextView(c);
            tag.setTextSize(12);
            tag.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            tag.setPadding(dp(12), 0, 0, 0);
            addView(tag, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind(AppItem it) {
            title.setText(it.label);
            sub.setText(it.pkg);

            StringBuilder t = new StringBuilder(it.system ? "Hệ thống" : "Người dùng");
            int colorRes = it.system ? R.color.sys_blue : R.color.ok_green;
            if (it.protectedApp) {
                t.append(" • ").append(it.pinned ? "ĐƯỢC BẢO VỆ (bạn ghim)" : "ĐƯỢC BẢO VỆ");
                colorRes = R.color.amber;
            }
            if (it.disabled) {
                t.append(" • ĐÃ TẮT");
                if (!it.protectedApp) colorRes = R.color.text_dim;
            }
            tag.setText(t);
            tag.setTextColor(color(colorRes));

            box.setVisibility(it.protectedApp ? View.INVISIBLE : View.VISIBLE);
            setAlpha(it.protectedApp ? 0.75f : 1f);
        }

        @Override
        public void setChecked(boolean c) {
            checked = c;
            setActivated(c); // lan xuống ô chọn và nền dòng
        }

        @Override
        public boolean isChecked() {
            return checked;
        }

        @Override
        public void toggle() {
            setChecked(!checked);
        }
    }

    // ---------------------------------------------------------------- danh sách bảo vệ do người dùng ghim

    private Set<String> getPinned() {
        Set<String> saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getStringSet(KEY_PINNED, new HashSet<String>());
        return new HashSet<>(saved);
    }

    private void savePinned(Set<String> pinned) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putStringSet(KEY_PINNED, new HashSet<>(pinned)).apply();
    }

    private void pinSelected() {
        List<AppItem> selected = getSelected();
        if (selected.isEmpty()) {
            toast("Bạn chưa chọn ứng dụng nào để bảo vệ.");
            return;
        }
        Set<String> pinned = getPinned();
        for (AppItem it : selected) pinned.add(it.pkg);
        savePinned(pinned);
        toast("Đã bảo vệ " + selected.size()
                + " ứng dụng. Muốn bỏ: bấm \"Quản lý danh sách bảo vệ\".");
        loadApps();
    }

    private void showManageProtected() {
        final List<String> pkgs = new ArrayList<>(getPinned());
        if (pkgs.isEmpty()) {
            showMessage("Danh sách bảo vệ",
                    "Bạn chưa ghim ứng dụng nào.\n\nChọn app trong danh sách rồi bấm \"Bảo vệ app đã chọn\"."
                            + "\n\nCác gói hệ thống quan trọng luôn được bảo vệ sẵn và không thể bỏ.");
            return;
        }
        Collections.sort(pkgs);
        PackageManager pm = getPackageManager();
        String[] labels = new String[pkgs.size()];
        for (int i = 0; i < labels.length; i++) {
            String pkg = pkgs.get(i);
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                labels[i] = pm.getApplicationLabel(ai) + "\n" + pkg;
            } catch (PackageManager.NameNotFoundException e) {
                labels[i] = pkg + "\n(không còn cài trên máy)";
            }
        }
        final boolean[] checked = new boolean[pkgs.size()];
        new AlertDialog.Builder(this)
                .setTitle("App bạn đã ghim bảo vệ")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Bỏ bảo vệ", (d, w) -> {
                    Set<String> pinned = getPinned();
                    int n = 0;
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i] && pinned.remove(pkgs.get(i))) n++;
                    }
                    if (n > 0) {
                        savePinned(pinned);
                        toast("Đã bỏ bảo vệ " + n + " ứng dụng.");
                        loadApps();
                    }
                })
                .setNegativeButton("Đóng", null)
                .show();
    }

    // ---------------------------------------------------------------- danh sách app

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();

            Set<String> launchable = new HashSet<>();
            for (String cat : new String[]{Intent.CATEGORY_LAUNCHER, "android.intent.category.LEANBACK_LAUNCHER"}) {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                    launchable.add(ri.activityInfo.packageName);
                }
            }

            // Launcher hiện tại và bàn phím đang dùng luôn được bảo vệ
            Set<String> dynamicProtected = new HashSet<>();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
                dynamicProtected.add(ri.activityInfo.packageName);
            }
            String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null && ime.contains("/")) {
                dynamicProtected.add(ime.substring(0, ime.indexOf('/')));
            }

            Set<String> pinned = getPinned();

            List<AppItem> items = new ArrayList<>();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                AppItem it = new AppItem();
                it.pkg = ai.packageName;
                it.label = String.valueOf(pm.getApplicationLabel(ai));
                it.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                it.launchable = launchable.contains(it.pkg);
                it.disabled = !ai.enabled;
                boolean core = isProtected(it.pkg) || dynamicProtected.contains(it.pkg)
                        || it.pkg.equals(getPackageName());
                it.pinned = !core && pinned.contains(it.pkg);
                it.protectedApp = core || it.pinned;
                items.add(it);
            }
            Collections.sort(items, (a, b) -> a.label.toLowerCase(Locale.ROOT)
                    .compareTo(b.label.toLowerCase(Locale.ROOT)));

            runOnUiThread(() -> {
                allItems.clear();
                allItems.addAll(items);
                applyFilter();
            });
        }).start();
    }

    private static boolean isProtected(String pkg) {
        if (pkg.equals("android")) return true;
        for (String p : PROTECTED_PREFIXES) {
            if (pkg.startsWith(p)) return true;
        }
        return false;
    }

    private void applyFilter() {
        visible.clear();
        for (AppItem it : allItems) {
            if (!showAll && (!it.launchable || it.disabled)) continue;
            visible.add(it);
        }
        listView.clearChoices();
        adapter.notifyDataSetChanged();
        updateCounter();
    }

    private void toggleSelectAll() {
        SparseBooleanArray checked = listView.getCheckedItemPositions();
        boolean allSelected = true;
        for (int i = 0; i < visible.size(); i++) {
            if (!visible.get(i).protectedApp && !checked.get(i)) {
                allSelected = false;
                break;
            }
        }
        for (int i = 0; i < visible.size(); i++) {
            listView.setItemChecked(i, !allSelected && !visible.get(i).protectedApp);
        }
        updateCounter();
    }

    /** Các app đang được chọn, đã loại bỏ app được bảo vệ. */
    private List<AppItem> getSelected() {
        List<AppItem> selected = new ArrayList<>();
        SparseBooleanArray checked = listView.getCheckedItemPositions();
        for (int i = 0; i < visible.size(); i++) {
            if (checked.get(i) && !visible.get(i).protectedApp) selected.add(visible.get(i));
        }
        return selected;
    }

    // ---------------------------------------------------------------- vô hiệu hóa / gỡ

    private void applyToSelected(final boolean uninstall) {
        final List<AppItem> selected = getSelected();
        if (selected.isEmpty()) {
            toast("Bạn chưa chọn ứng dụng nào.");
            return;
        }
        StringBuilder names = new StringBuilder();
        for (AppItem it : selected) names.append("• ").append(it.label).append('\n');

        String what = uninstall ? "GỠ CÀI ĐẶT" : "VÔ HIỆU HÓA";
        String hint = uninstall
                ? "\nApp hệ thống bị gỡ cho người dùng hiện tại; khôi phục được bằng nút \"Khôi phục\"."
                : "\nCó thể bật lại bất cứ lúc nào bằng nút \"Khôi phục\".";
        new AlertDialog.Builder(this)
                .setTitle(what + " " + selected.size() + " ứng dụng?")
                .setMessage(names + hint)
                .setPositiveButton("Đồng ý", (d, w) -> doApply(selected, uninstall))
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void doApply(final List<AppItem> selected, final boolean uninstall) {
        if (!rooted) {
            openSettingsOneByOne(selected, uninstall);
            return;
        }
        toast("Đang xử lý, vui lòng chờ...");
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            int ok = 0;
            for (AppItem it : selected) {
                if (!SAFE_PKG.matcher(it.pkg).matches()) continue;
                runSu("am force-stop " + it.pkg, 15);
                String cmd;
                if (uninstall) {
                    cmd = it.system ? "pm uninstall -k --user 0 " + it.pkg : "pm uninstall " + it.pkg;
                } else {
                    cmd = "pm disable-user --user 0 " + it.pkg;
                }
                Result r = runSu(cmd, 60);
                boolean good = r.out.contains("Success") || r.out.contains("new state");
                if (good) ok++;
                report.append(good ? "✔ " : "✘ ").append(it.label);
                if (!good) report.append(" — ").append(firstLine(r.out));
                report.append('\n');
            }
            final String msg = ok + "/" + selected.size() + " thành công\n\n" + report;
            runOnUiThread(() -> {
                showMessage("Kết quả", msg);
                loadApps();
            });
        }).start();
    }

    private void openSettingsOneByOne(List<AppItem> selected, boolean uninstall) {
        for (AppItem it : selected) {
            Uri uri = Uri.parse("package:" + it.pkg);
            Intent in = (uninstall && !it.system)
                    ? new Intent(Intent.ACTION_DELETE, uri)
                    : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri);
            try {
                startActivity(in);
            } catch (Exception ignored) {
            }
        }
        toast("Không có root: đã mở từng màn hình ứng dụng. Bấm Tắt/Gỡ cài đặt rồi nhấn Back để sang app kế tiếp.");
    }

    // ---------------------------------------------------------------- xóa cache

    private void clearAllCaches() {
        if (!rooted) {
            try {
                startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
                toast("Vào mục \"Dữ liệu đã lưu trong bộ nhớ đệm\" (Cached data) và chọn OK để xóa.");
            } catch (Exception e) {
                toast("Không mở được cài đặt bộ nhớ. Hãy vào Cài đặt → Lưu trữ thủ công.");
            }
            return;
        }
        toast("Đang xóa cache...");
        new Thread(() -> {
            long before = freeBytes();
            runSu("pm trim-caches 99999999999", 180);
            runSu("rm -rf /data/data/*/cache/* /storage/emulated/0/Android/data/*/cache/* 2>/dev/null", 180);
            long freed = Math.max(0, freeBytes() - before);
            final String msg = String.format(Locale.US, "Đã dọn cache xong. Giải phóng khoảng %.1f MB.",
                    freed / 1048576.0);
            runOnUiThread(() -> showMessage("Xóa cache", msg));
        }).start();
    }

    private long freeBytes() {
        try {
            return new StatFs(Environment.getDataDirectory().getPath()).getAvailableBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- khôi phục

    private void showRestoreDialog() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            final List<AppItem> found = new ArrayList<>();
            for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)) {
                boolean removed = (ai.flags & ApplicationInfo.FLAG_INSTALLED) == 0;
                boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean disabled = !removed && !ai.enabled;
                if (disabled || (removed && isSystem)) {
                    AppItem it = new AppItem();
                    it.pkg = ai.packageName;
                    it.label = String.valueOf(pm.getApplicationLabel(ai));
                    it.disabled = disabled;
                    it.removed = removed;
                    found.add(it);
                }
            }
            runOnUiThread(() -> showRestoreList(found));
        }).start();
    }

    private void showRestoreList(final List<AppItem> found) {
        if (found.isEmpty()) {
            showMessage("Khôi phục", "Không có ứng dụng nào đã bị tắt hoặc gỡ.");
            return;
        }
        String[] labels = new String[found.size()];
        final boolean[] checked = new boolean[found.size()];
        for (int i = 0; i < labels.length; i++) {
            AppItem it = found.get(i);
            labels[i] = it.label + "\n" + it.pkg + (it.removed ? "  (đã gỡ)" : "  (đã tắt)");
        }
        new AlertDialog.Builder(this)
                .setTitle("Chọn app cần khôi phục")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Khôi phục", (d, w) -> {
                    List<AppItem> picked = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) picked.add(found.get(i));
                    restore(picked);
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void restore(final List<AppItem> picked) {
        if (picked.isEmpty()) return;
        if (!rooted) {
            toast("Cần root để khôi phục. Hoặc vào Cài đặt → Ứng dụng, chọn app đã tắt rồi bấm Bật.");
            return;
        }
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            int ok = 0;
            for (AppItem it : picked) {
                if (!SAFE_PKG.matcher(it.pkg).matches()) continue;
                String cmd = it.removed ? "pm install-existing " + it.pkg : "pm enable " + it.pkg;
                Result r = runSu(cmd, 60);
                boolean good = r.out.contains("Success") || r.out.contains("new state")
                        || r.out.contains("installed for user");
                if (good) ok++;
                report.append(good ? "✔ " : "✘ ").append(it.label);
                if (!good) report.append(" — ").append(firstLine(r.out));
                report.append('\n');
            }
            final String msg = ok + "/" + picked.size() + " thành công\n\n" + report;
            runOnUiThread(() -> {
                showMessage("Kết quả khôi phục", msg);
                loadApps();
            });
        }).start();
    }

    // ---------------------------------------------------------------- chạy lệnh root

    private static boolean checkRoot() {
        for (int v = 0; v < SU_VARIANTS.length; v++) {
            Result r = runSu("id", 20, v);
            if (r.out.contains("uid=0")) {
                suVariant = v;
                return true;
            }
        }
        return false;
    }

    private static Result runSu(String cmd, long timeoutSec) {
        return runSu(cmd, timeoutSec, suVariant);
    }

    private static Result runSu(String cmd, long timeoutSec, int variant) {
        Result r = new Result();
        try {
            String[] argv = SU_VARIANTS[variant].clone();
            argv[argv.length - 1] = cmd;
            final Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            p.getOutputStream().close();
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (sb) {
                            sb.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                }
            });
            reader.start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                r.out = "hết thời gian chờ";
                return r;
            }
            reader.join(1500);
            r.code = p.exitValue();
            synchronized (sb) {
                r.out = sb.toString().trim();
            }
        } catch (Exception e) {
            r.out = String.valueOf(e.getMessage());
        }
        return r;
    }

    private static String firstLine(String s) {
        if (s == null || s.isEmpty()) return "không rõ lỗi";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
