package com.boxcleaner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
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
    }

    private static class Result {
        int code = -1;
        String out = "";
    }

    private final List<AppItem> allItems = new ArrayList<>();
    private final List<AppItem> visible = new ArrayList<>();
    private ListView listView;
    private TextView status;
    private Button modeBtn;
    private boolean showAll = false;
    private volatile boolean rooted = false;

    // ---------------------------------------------------------------- UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(16);
        status.setText("Đang kiểm tra quyền root... (nếu có hộp thoại hỏi quyền, hãy chọn Cho phép)");
        root.addView(status);

        modeBtn = button("");
        Button selectBtn = button("Chọn / bỏ chọn tất cả");
        root.addView(row(modeBtn, selectBtn));

        Button disableBtn = button("Vô hiệu hóa app đã chọn (khuyên dùng)");
        Button uninstallBtn = button("Gỡ cài đặt app đã chọn");
        root.addView(row(disableBtn, uninstallBtn));

        Button cacheBtn = button("Xóa cache tất cả app");
        Button restoreBtn = button("Khôi phục app đã tắt/gỡ");
        root.addView(row(cacheBtn, restoreBtn));

        listView = new ListView(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setOnItemClickListener((parent, v, position, id) -> {
            if (position < visible.size() && visible.get(position).protectedApp) {
                listView.setItemChecked(position, false);
                toast("Ứng dụng này được bảo vệ, không thể chọn.");
            }
        });
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateModeButton();

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

        new Thread(() -> {
            final boolean ok = checkRoot();
            rooted = ok;
            runOnUiThread(() -> status.setText(ok
                    ? "✔ Có quyền root: có thể vô hiệu hóa/gỡ app hệ thống và xóa cache."
                    : "✘ Không có root: chỉ mở được trang cài đặt từng app (tắt/gỡ thủ công)."));
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

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        return b;
    }

    private LinearLayout row(Button... buttons) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (Button b : buttons) {
            r.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return r;
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

            List<AppItem> items = new ArrayList<>();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                AppItem it = new AppItem();
                it.pkg = ai.packageName;
                it.label = String.valueOf(pm.getApplicationLabel(ai));
                it.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                it.launchable = launchable.contains(it.pkg);
                it.disabled = !ai.enabled;
                it.protectedApp = isProtected(it.pkg) || dynamicProtected.contains(it.pkg)
                        || it.pkg.equals(getPackageName());
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
        List<String> rows = new ArrayList<>();
        for (AppItem it : allItems) {
            if (!showAll && (!it.launchable || it.disabled)) continue;
            visible.add(it);
            String tag = (it.system ? "Hệ thống" : "Người dùng")
                    + (it.protectedApp ? " • ĐƯỢC BẢO VỆ" : "")
                    + (it.disabled ? " • ĐÃ TẮT" : "");
            rows.add(it.label + "\n" + it.pkg + "  (" + tag + ")");
        }
        listView.clearChoices();
        listView.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice, rows));
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
    }

    // ---------------------------------------------------------------- vô hiệu hóa / gỡ

    private void applyToSelected(final boolean uninstall) {
        final List<AppItem> selected = new ArrayList<>();
        SparseBooleanArray checked = listView.getCheckedItemPositions();
        for (int i = 0; i < visible.size(); i++) {
            if (checked.get(i) && !visible.get(i).protectedApp) selected.add(visible.get(i));
        }
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
