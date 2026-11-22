package com.sxrec.xunibtn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity implements UpdateChecker.Listener {
    private static final String BILIBILI_URL = "https://space.bilibili.com/255947051";
    private SettingsStore settings;
    private UpdateChecker updateChecker;
    private String versionName;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private boolean syncing;
    private boolean listening;
    private ScrollView page;
    private TextView statusTitle;
    private TextView statusDescription;
    private Button primaryButton;
    private Button collapseButton;
    private Button updateButton;
    private DialView preview;
    private SeekBar sizeSlider;
    private SeekBar opacitySlider;
    private SeekBar intervalSlider;
    private TextView sizeLabel;
    private TextView opacityLabel;
    private TextView intervalLabel;
    private Switch repeatSwitch;
    private Switch hapticSwitch;
    private AlertDialog currentDialog;
    private UpdateChecker.Result pendingUpdate;
    private boolean pendingManual;

    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesListener =
            (preferences, key) -> {
                if (!isDestroyed()) syncSettings();
            };

    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            if (!resumed || isFinishing() || isDestroyed()) return;
            refreshStatus();
            main.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new SettingsStore(this);
        versionName = installedVersion();
        updateChecker = new UpdateChecker(versionName, this);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        buildPage();
        applyBarAppearance();
    }

    private void applyBarAppearance() {
        // The insets controller is only reachable once the window has a decor view.
        WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
        if (controller == null) return;
        controller.setSystemBarsAppearance(0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }

    private String installedVersion() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return version == null ? "未知版本" : version;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "未知版本";
        }
    }

    private void buildPage() {
        Configuration configuration = getResources().getConfiguration();
        boolean twoColumns = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                && configuration.screenWidthDp >= 840;
        int outer = dp(twoColumns ? 24 : 16);
        page = new ScrollView(this);
        page.setBackgroundColor(Ui.BG);
        page.setFillViewport(true);
        page.setClipToPadding(false);
        page.setPadding(0, 0, 0, 0);
        page.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });
        LinearLayout content = Ui.column(this);
        content.setPadding(outer, dp(24), outer, dp(24));
        page.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = Ui.column(this);
        brand.addView(Ui.text(this, "虚拟按钮", 32, Ui.TEXT, true), matchWrap());
        add(brand, Ui.text(this, "xuniBTN / " + versionName, 16, Ui.MUTED, false), 6);
        header.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button help = button("帮助", false, this::showHelp);
        help.setMinWidth(dp(64));
        help.setMinimumWidth(dp(64));
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        helpParams.leftMargin = dp(12);
        header.addView(help, helpParams);
        content.addView(header, matchWrap());
        TextView tagline = Ui.text(this, "把遥控器，放回屏幕上。", 24, Ui.ACCENT, true);
        add(content, tagline, 24);
        add(content, paragraph("为 TV 应用准备的屏幕方向键。先打开目标应用，再用悬浮圆盘操作。"), 10);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(twoColumns ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        columns.setBaselineAligned(false);
        add(content, columns, 24);
        LinearLayout left = Ui.column(this);
        LinearLayout right = Ui.column(this);
        if (twoColumns) {
            columns.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            rightParams.leftMargin = dp(24);
            columns.addView(right, rightParams);
        } else {
            columns.addView(left, matchWrap());
            LinearLayout.LayoutParams rightParams = matchWrap();
            rightParams.topMargin = dp(16);
            columns.addView(right, rightParams);
        }
        buildStatusCard(left);
        buildPreviewCard(left);
        buildSettingsCard(right);
        buildAboutCard(right);
        setContentView(page);
        page.requestApplyInsets();
        syncSettings();
        refreshUpdateButton();
    }

    private void buildStatusCard(LinearLayout parent) {
        LinearLayout card = card();
        card.addView(Ui.text(this, "悬浮遥控器", 16, Ui.MUTED, true), matchWrap());
        statusTitle = Ui.text(this, "读取服务状态", 24, Ui.ACCENT, true);
        statusTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(card, statusTitle, 12);
        statusDescription = paragraph("");
        add(card, statusDescription, 10);
        primaryButton = button("启用悬浮按钮", true, this::toggleOverlay);
        add(card, primaryButton, 20);
        add(card, button("回到桌面", false, this::goHome), 10);
        add(card, paragraph("这里仅展示外观预览，不测试按键，以免打乱目标应用焦点。"), 12);
        parent.addView(card, matchWrap());
    }

    private void buildPreviewCard(LinearLayout parent) {
        LinearLayout card = card();
        card.addView(Ui.text(this, "圆盘预览", 20, Ui.TEXT, true), matchWrap());
        FrameLayout frame = new FrameLayout(this);
        frame.setContentDescription("圆盘外观预览，不可操作");
        frame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        frame.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        preview = new DialView(this, settings, null);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        FrameLayout.LayoutParams dialParams = new FrameLayout.LayoutParams(dp(200), dp(200), Gravity.CENTER);
        frame.addView(preview, dialParams);
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(224));
        frameParams.topMargin = dp(8);
        card.addView(frame, frameParams);
        TextView caption = paragraph("仅预览 · 上下左右与确认\n预览固定 200 dp，实际圆盘按你的设置显示。");
        caption.setGravity(Gravity.CENTER);
        add(card, caption, 4);
        add(parent, card, 16);
    }

    private void buildSettingsCard(LinearLayout parent) {
        LinearLayout card = card();
        card.addView(Ui.text(this, "调到顺手", 22, Ui.TEXT, true), matchWrap());
        add(card, paragraph("更改立即保存，并应用到悬浮圆盘。"), 8);
        sizeLabel = label(card, "圆盘大小", 22);
        sizeSlider = slider(card, "size", 160, 320);
        opacityLabel = label(card, "透明度", 18);
        opacitySlider = slider(card, "opacity", 25, 100);
        repeatSwitch = settingSwitch("方向按住连发", "repeat");
        add(card, repeatSwitch, 16);
        add(card, paragraph("持续发送连续短按，不等于硬件遥控器的长按。"), 2);
        intervalLabel = label(card, "连发间隔", 18);
        intervalSlider = slider(card, "interval", 100, 350);
        hapticSwitch = settingSwitch("触感反馈", "haptic");
        add(card, hapticSwitch, 16);
        add(card, button("重置圆盘位置", false, () -> {
            settings.resetPosition();
            syncSettings();
            toast("已重置位置并展开圆盘");
        }), 20);
        collapseButton = button("收起为小泡泡", false, () -> {
            settings.putBoolean("collapsed", !settings.collapsed());
            syncSettings();
        });
        add(card, collapseButton, 10);
        add(card, button("系统无障碍服务设置", false, () -> {
            if (accessibilityEnabled()) openServiceSettings();
            else showPermissionExplanation(false);
        }), 10);
        parent.addView(card, matchWrap());
    }

    private void buildAboutCard(LinearLayout parent) {
        LinearLayout card = card();
        card.addView(Ui.text(this, "关于 xuniBTN", 20, Ui.TEXT, true), matchWrap());
        add(card, paragraph("作者：莫莫先生\n基于 GPL-3.0 协议开源"), 10);
        add(card, button("查看开源仓库", false,
                () -> openTrustedLink(UpdateChecker.REPOSITORY_URL)), 16);
        add(card, button("B 站主页", false, () -> openTrustedLink(BILIBILI_URL)), 10);
        updateButton = button("检查更新", false, () -> {
            if (!updateChecker.check(true)) toast(UpdateChecker.FAILURE_MESSAGE);
            refreshUpdateButton();
        });
        add(card, updateButton, 10);
        add(parent, card, 16);
    }

    private LinearLayout card() {
        LinearLayout card = Ui.column(this);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(Ui.shape(this, Ui.SURFACE, 24, true));
        return card;
    }

    private TextView label(LinearLayout parent, String text, int margin) {
        TextView label = Ui.text(this, text, 18, Ui.TEXT, true);
        add(parent, label, margin);
        return label;
    }

    private SeekBar slider(LinearLayout parent, String key, int min, int max) {
        SeekBar seek = new SeekBar(this);
        seek.setId(View.generateViewId());
        seek.setMin(min);
        seek.setMax(max);
        seek.setSplitTrack(false);
        seek.setThumbTintList(controlTint(Ui.ACCENT, Ui.MUTED));
        seek.setProgressTintList(controlTint(Ui.ACCENT, Ui.MUTED));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.BORDER));
        seek.setMinimumHeight(dp(48));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && !syncing) settings.putInt(key, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        add(parent, seek, 2);
        return seek;
    }

    private Switch settingSwitch(String label, String key) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextSize(18);
        control.setTextColor(Ui.TEXT);
        control.setMinHeight(dp(56));
        control.setPadding(0, dp(8), 0, dp(8));
        control.setSwitchPadding(dp(16));
        control.setShowText(false);
        control.setThumbTintList(new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {Ui.ACCENT, Ui.MUTED}));
        control.setTrackTintList(new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {0xFF64744D, Ui.BORDER}));
        control.setOnCheckedChangeListener((button, checked) -> {
            if (!syncing) settings.putBoolean(key, checked);
        });
        return control;
    }

    private ColorStateList controlTint(int enabled, int disabled) {
        return new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {disabled, enabled});
    }

    private void syncSettings() {
        if (sizeSlider == null) return;
        syncing = true;
        try {
            sizeSlider.setProgress(settings.size());
            opacitySlider.setProgress(settings.opacity());
            intervalSlider.setProgress(settings.interval());
            setText(sizeLabel, "圆盘大小 · " + settings.size() + " dp");
            setText(opacityLabel, "透明度 · " + settings.opacity() + "%");
            setText(intervalLabel, "连发间隔 · " + settings.interval() + " ms"
                    + (settings.repeat() ? "" : "（连发已关闭）"));
            sizeLabel.setLabelFor(sizeSlider.getId());
            opacityLabel.setLabelFor(opacitySlider.getId());
            intervalLabel.setLabelFor(intervalSlider.getId());
            repeatSwitch.setChecked(settings.repeat());
            hapticSwitch.setChecked(settings.haptic());
            intervalSlider.setEnabled(settings.repeat());
            intervalLabel.setTextColor(settings.repeat() ? Ui.TEXT : Ui.MUTED);
            preview.refreshAppearance();
        } finally {
            syncing = false;
        }
        refreshStatus();
    }

    private boolean accessibilityEnabled() {
        ComponentName target = new ComponentName(this, RemoteAccessibilityService.class);
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        for (String entry : enabled.split(":")) {
            if (target.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    private void refreshStatus() {
        if (statusTitle == null) return;
        RemoteAccessibilityService service = RemoteAccessibilityService.getInstance();
        boolean authorized = accessibilityEnabled();
        boolean showing = service != null && service.isOverlayShowing();
        String error = service == null ? "" : service.getLastError();
        boolean fault = error != null && !error.trim().isEmpty();
        String title;
        String description;
        String action;
        if (!authorized && service == null) {
            title = "无障碍未开启";
            description = "启用系统无障碍服务后，才能显示悬浮圆盘。授权由你在系统页面完成。";
            action = "启用悬浮按钮";
        } else if (service == null) {
            title = "正在连接";
            description = "系统中已开启服务，但当前尚未连接，不代表圆盘正在显示。"
                    + "若长时间未恢复，请检查系统服务开关和后台、省电限制。";
            action = settings.enabled() ? "取消显示请求" : "显示悬浮按钮";
        } else if (fault) {
            title = "服务故障";
            description = "当前悬浮层" + (showing ? "仍在显示。" : "未显示。") + "\n" + error
                    + "\n可先隐藏再显示重试，或检查系统服务设置。";
            action = showing || settings.enabled() ? "隐藏悬浮按钮" : "重新显示悬浮按钮";
        } else if (showing) {
            title = settings.collapsed() ? "收起状态" : "正在显示";
            description = settings.collapsed()
                    ? "小泡泡仍在屏幕上。可拖动它，轻点即可恢复圆盘。"
                    : "悬浮圆盘已显示。回到桌面并打开目标 TV 应用后使用。";
            action = "隐藏悬浮按钮";
        } else if (settings.enabled()) {
            title = "正在连接";
            description = "服务已连接，已请求显示，但尚未检测到悬浮圆盘。"
                    + "若持续不显示，可取消后重试，或查看系统服务设置。";
            action = "取消显示请求";
        } else {
            title = "已隐藏";
            description = "服务已连接，圆盘与小泡泡均未显示。点下方按钮可重新显示。";
            action = "显示悬浮按钮";
        }
        setText(statusTitle, title);
        setText(statusDescription, description);
        setText(primaryButton, action);
        setText(collapseButton, settings.collapsed() ? "重新展开圆盘" : "收起为小泡泡");
        collapseButton.setEnabled(showing);
        collapseButton.setAlpha(showing ? 1f : 0.5f);
    }

    private void toggleOverlay() {
        RemoteAccessibilityService service = RemoteAccessibilityService.getInstance();
        if (service == null && !accessibilityEnabled()) {
            showPermissionExplanation(true);
            return;
        }
        boolean showing = service != null && service.isOverlayShowing();
        // enabled is an intention, never proof that a live overlay exists.
        settings.putBoolean("enabled", !(showing || settings.enabled()));
        refreshStatus();
    }

    private void showPermissionExplanation(boolean enableAfterConsent) {
        showTextDialog("启用前，请了解", "• 虚拟按钮通过 Android 无障碍服务显示悬浮遥控器，"
                        + "只在你操作时发送方向、确认和返回等系统按键。\n\n"
                        + "• 不读取屏幕内容，不采集或上传操作记录与个人数据。\n\n"
                        + "• 仅检查更新时联网：先请求 gh-proxy.com，再在失败时请求 GitHub 的公开发布信息。\n\n"
                        + "• 系统授权由你手动完成；可随时在系统无障碍设置关闭服务。",
                "去系统设置", () -> {
                    if (enableAfterConsent) settings.putBoolean("enabled", true);
                    openServiceSettings();
                }, "暂不开启");
    }

    private void openServiceSettings() {
        // The details action is not exposed as a constant in the public API 34 SDK.
        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        details.putExtra(Intent.EXTRA_COMPONENT_NAME,
                new ComponentName(this, RemoteAccessibilityService.class));
        if (launch(details)) return;
        if (!launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
            toast("无法打开系统设置，请在系统设置中手动查找无障碍服务。");
        }
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!launch(home)) toast("未找到系统桌面，请使用系统主页手势或按键。");
    }

    private boolean launch(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }

    private void openTrustedLink(String url) {
        if (!UpdateChecker.REPOSITORY_URL.equals(url) && !UpdateChecker.RELEASE_URL.equals(url)
                && !BILIBILI_URL.equals(url)) return;
        if (!launch(new Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            toast("未找到可打开链接的应用，请访问 " + url);
        }
    }

    private void showHelp() {
        showTextDialog("使用帮助", "• 启用悬浮按钮后，先回到桌面并打开需要操作的 TV 应用。"
                        + "本页圆盘只是预览，不会发送按键。\n\n"
                        + "• 拖动圆盘顶部手柄可移动位置；圆盘下方依次提供返回、收起和设置。\n\n"
                        + "• 收起后的小泡泡也可拖动，轻点即可恢复圆盘。位置不合适时，可直接重置位置。\n\n"
                        + "• 方向按住连发是连续短按，不是硬件遥控器的长按；可在设置中关闭或调整间隔。\n\n"
                        + "• 受 Android 和目标应用限制，不能保证任何界面都可操作；系统登记动作数量也不等于实际能力。\n\n"
                        + "• Android 13 及以上若无障碍开关灰色，可到系统应用详情的更多菜单，手动允许受限设置"
                        + "（仅部分机型存在此选项），再返回开启服务。\n\n"
                        + "• 厂商后台管理或省电策略可能中止服务，请按需手动调整后台、电池限制。"
                        + "本页会重新读取实际状态，已授权不代表服务仍在运行。\n\n"
                        + "• 隐藏只移除圆盘；如需彻底停用，请在系统无障碍设置关闭虚拟按钮服务。",
                "知道了", null, null);
    }

    @Override public void onUpdateResult(UpdateChecker.Result result, boolean manual) {
        if (isFinishing() || isDestroyed()) return;
        refreshUpdateButton();
        if (!manual && (!result.successful || !result.newer)) return;
        pendingUpdate = result;
        pendingManual = manual;
        showPendingUpdate();
    }

    private void refreshUpdateButton() {
        if (updateButton == null) return;
        // Remains clickable so a manual check can join a silent request and receive its result.
        setText(updateButton, updateChecker.isChecking() ? "正在检查更新…" : "检查更新");
    }

    private void showPendingUpdate() {
        if (!resumed || isFinishing() || isDestroyed() || currentDialog != null || pendingUpdate == null) return;
        UpdateChecker.Result result = pendingUpdate;
        boolean manual = pendingManual;
        pendingUpdate = null;
        if (!result.successful) {
            if (manual) showTextDialog("检查更新", UpdateChecker.FAILURE_MESSAGE,
                    "发布页", () -> openTrustedLink(UpdateChecker.RELEASE_URL), "关闭");
        } else if (result.newer) {
            showTextDialog("发现新版本 v" + result.version,
                    "当前版本：" + versionName + "\n\n" + result.notes
                            + "\n\n更新可稍后进行，下载将打开官方 GitHub 发布页。",
                    "前往下载", () -> openTrustedLink(UpdateChecker.RELEASE_URL), "以后再说");
        } else if (manual) {
            showTextDialog("检查更新", "当前已是最新版本（" + versionName + "）。",
                    "知道了", null, null);
        }
    }

    private void showTextDialog(String title, String message, String positive,
                                Runnable positiveAction, String negative) {
        if (!resumed || isFinishing() || isDestroyed() || currentDialog != null) return;
        TextView heading = Ui.text(this, title, 22, Ui.TEXT, true);
        heading.setPadding(dp(24), dp(24), dp(24), dp(12));
        ScrollView scroll = new ScrollView(this);
        TextView body = Ui.text(this, message, 17, Ui.TEXT, false);
        body.setLineSpacing(dp(5), 1f);
        body.setPadding(dp(24), dp(12), dp(24), dp(16));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setCustomTitle(heading).setView(scroll)
                .setPositiveButton(positive, (dialog, which) -> {
                    if (positiveAction != null) positiveAction.run();
                });
        if (negative != null) builder.setNegativeButton(negative, null);
        AlertDialog dialog = builder.create();
        currentDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (currentDialog == dialog) currentDialog = null;
            if (resumed) main.post(this::showPendingUpdate);
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(Ui.shape(this, Ui.SURFACE, 24, true));
        }
        for (int which : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            button.setTextColor(Ui.ACCENT);
            button.setTextSize(16);
            button.setAllCaps(false);
            button.setMinHeight(dp(48));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!listening) {
            settings.prefs.registerOnSharedPreferenceChangeListener(preferencesListener);
            listening = true;
        }
        syncSettings();
        main.removeCallbacks(statusPoll);
        main.post(statusPoll);
        updateChecker.check(false);
        refreshUpdateButton();
        showPendingUpdate();
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(statusPoll);
        if (listening) {
            settings.prefs.unregisterOnSharedPreferenceChangeListener(preferencesListener);
            listening = false;
        }
        super.onPause();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int previousScroll = page == null ? 0 : page.getScrollY();
        buildPage();
        page.post(() -> page.scrollTo(0, previousScroll));
    }

    @Override protected void onDestroy() {
        resumed = false;
        main.removeCallbacksAndMessages(null);
        if (listening) settings.prefs.unregisterOnSharedPreferenceChangeListener(preferencesListener);
        if (updateChecker != null) updateChecker.close();
        if (currentDialog != null) {
            currentDialog.setOnDismissListener(null);
            currentDialog.dismiss();
            currentDialog = null;
        }
        pendingUpdate = null;
        super.onDestroy();
    }

    private TextView paragraph(String text) {
        TextView view = Ui.text(this, text, 16, Ui.MUTED, false);
        view.setLineSpacing(dp(4), 1f);
        return view;
    }

    private Button button(String label, boolean primary, Runnable action) {
        Button button = Ui.button(this, label, primary);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topMargin);
        parent.addView(child, params);
    }

    private void setText(TextView view, String text) {
        if (view != null && !text.contentEquals(view.getText())) view.setText(text);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
