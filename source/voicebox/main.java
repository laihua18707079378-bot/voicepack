// 语音包发送器 - 三级菜单 · iOS 26 白色风格
//
// 交互：
//   一级：选择「音乐」或「音乐盒」
//   音乐：直接列出 audio/ 下的音频，点选后用底部按钮播放/发送
//   音乐盒 → 云端分类列表（CLOUD_BASE 的 data.json）→ 语音列表，点选后播放/发送
//
// 云端：
//   - 优先读取 CLOUD_BASE + data.json；该路径被回退成首页时，从首页内嵌 DATA_INLINE 提取
//   - 清单会缓存到 cloud_packs.json
//   - 语音文件按需下载到 voice/<包名>/<文件名>（与本地语音同目录），之后直接复用
//   - 云端拉取失败时自动回退到本地 voice/ 目录
//
// 性能：
//   - 网络请求与文件读写都在 IO 线程，不阻塞 UI
//   - 列表分页（每页 80 条），避免一次创建几百个 View
//   - 整页外层统一 ScrollView，可正常上下滑动

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.DecelerateInterpolator;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;

// ===== 宿主已注入 classLoader，无需手动设置 =====

import java.util.ArrayList;
import java.util.List;

addItem("语音盒", "showVoiceMenu");
addItem("会员登录管理", "showLoginDialog");

// ===== iOS 26 白色配色 =====
int C_BG = Color.parseColor("#F5F5F7");
int C_CARD_SOFT = Color.parseColor("#F2FFFFFF");
int C_TEXT = Color.parseColor("#1D1D1F");
int C_SUB = Color.parseColor("#8E8E93");
int C_LINE = Color.parseColor("#E5E5EA");
int C_BLUE = Color.parseColor("#0071E3");
int C_CHEVRON = Color.parseColor("#C7C7CC");
int C_SEL_BG = Color.parseColor("#E8F2FE");

// ===== 状态 =====
int gChatType = 0;
String gPeerUin = "";
AlertDialog gDialog = null;
Context gAct = null;
LinearLayout gRoot = null;
LinearLayout gContent = null;
int gPageLimit = 80;
int gCurCat = 0;
Object gPlayer = null;
java.io.File gPreviewFile = null;

// 选中项 + 底部操作栏
java.io.File gSelectedFile = null;
LinearLayout gActionBar = null;
TextView gSelLabel = null;
TextView gPlayBtn = null;
TextView gSendBtn = null;

// 云端下载进度条
LinearLayout gDlBox = null;
android.widget.ProgressBar gDlBar = null;
TextView gDlText = null;

// 扫描缓存
List gCatNames = new ArrayList();
List gCatFiles = new ArrayList();
List gCatDirs = new ArrayList();
List gCatCounts = new ArrayList();

// ===== 云端语音盒 =====
String CLOUD_BASE = "https://mcer.dpdns.org/";
boolean gCloudMode = true;
boolean gCloudLoaded = false;
boolean gCloudLoading = false;
String gCloudError = "";
// 本地缓存路径 -> 远程地址
java.util.HashMap gCloudUrls = new java.util.HashMap();
// 本地缓存路径 -> 备用地址（站点缺文件时回源）
java.util.HashMap gCloudAlt = new java.util.HashMap();
java.util.Set gDownloading = new java.util.HashSet();
int gRootLimit = 60;
List gShownCats = new ArrayList();
List gCountViews = new ArrayList();
String gQuery = "";
LinearLayout gListBox = null;
LinearLayout gSearchRow = null;
android.widget.EditText gSearchBox = null;
android.os.Handler gUI = null;
boolean gRefreshing = false;
int gRefreshPos = 0;
long gRefreshStart = 0;
List gRefreshQueue = null;
ScrollView gScroll = null;
int gRootScrollY = 0;
android.widget.ProgressBar gRefreshBar = null;
TextView gRefreshText = null;

// 导入音乐（浏览手机存储）
java.io.File gBrowseDir = null;
List gBrowseEntries = new ArrayList();
java.util.Set gImportSel = new java.util.HashSet();
int gBrowseLimit = 100;
int gBrowseDirCount = 0;
int gBrowseFileCount = 0;
int gBrowseAudioCount = 0;

// 音乐多选 / 批量发送
boolean gBatchMode = false;
boolean gBatchSending = false;
java.util.Set gMusicSel = new java.util.HashSet();
int gBatchIndex = 0;
int gBatchTotal = 0;

// ===== 音乐盒（网易云 / QQ音乐） =====
String gNeQuery = "";
String gPlatform = "ne";     // ne=网易云音乐, qq=QQ音乐
String gNeState = "";        // 搜索状态
boolean gNeSearching = false;
List gSongs = new ArrayList();
int gMIndex = -1;
String gMPlayingId = "";
Object gMPlayer = null;
boolean gMPrepared = false;
LinearLayout gPlayerBar = null;
TextView gMNow = null;
TextView gMPlayBtn = null;
android.widget.ProgressBar gMProg = null;
android.os.Handler gMTick = null;
Runnable gMTickRun = null;
List gMRowIcons = new ArrayList();
List gMRowNames = new ArrayList();

// ===== 会员登录（移植自参考插件） =====
String LOGIN_CONFIG = "music_login";
String webLoginPlatform = "";
String webLoginPlatformName = "";
Dialog webLoginDialog = null;
WebView webLoginWebView = null;

// ===== 菜单入口 =====
public void showVoiceMenu(int chatType, String peerUin, String userUin) {
    gChatType = chatType;
    gPeerUin = peerUin;

    // 云端模式按需拉取；本地列表仅作为云端失败时的回退
    if (!gCloudMode) scanVoiceDir();

    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    mainHandler.post(new Runnable() {
        public void run() {
            openMenuUI();
        }
    });
}

void openMenuUI() {
    stopPreview();
    checkPluginUpdate();
    gRefreshing = false;
    gRootScrollY = 0;
    if (gDialog != null) {
        try { gDialog.dismiss(); } catch (Exception e) {}
        gDialog = null;
    }

    gAct = getNowActivity();
    if (gAct == null) gAct = context;
    if (gAct == null) {
        toast("无法获取活动上下文");
        return;
    }

    gRoot = new LinearLayout(gAct);
    gRoot.setOrientation(LinearLayout.VERTICAL);
    gRoot.setPadding(dp2px(gAct, 22), dp2px(gAct, 12), dp2px(gAct, 22), dp2px(gAct, 16));

    GradientDrawable pageBg = new GradientDrawable();
    pageBg.setColor(C_BG);
    pageBg.setCornerRadius(dp2px(gAct, 28));
    gRoot.setBackground(pageBg);

    addGrabber(gAct);

    gScroll = new ScrollView(gAct);
    gScroll.setFillViewport(false);
    gContent = new LinearLayout(gAct);
    gContent.setOrientation(LinearLayout.VERTICAL);
    gScroll.addView(gContent, new android.widget.FrameLayout.LayoutParams(-1, -2));
    gRoot.addView(gScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

    // 云端下载进度（默认隐藏）
    gDlBox = new LinearLayout(gAct);
    gDlBox.setOrientation(LinearLayout.HORIZONTAL);
    gDlBox.setGravity(Gravity.CENTER_VERTICAL);
    gDlBox.setPadding(dp2px(gAct, 2), 0, dp2px(gAct, 2), dp2px(gAct, 6));
    gDlBar = makeProgressBar(gAct, 8);
    LinearLayout.LayoutParams dlBarLp = new LinearLayout.LayoutParams(0, dp2px(gAct, 8), 1f);
    dlBarLp.rightMargin = dp2px(gAct, 8);
    gDlBox.addView(gDlBar, dlBarLp);
    gDlText = new TextView(gAct);
    gDlText.setTextSize(11);
    gDlText.setTextColor(C_SUB);
    gDlBox.addView(gDlText, new LinearLayout.LayoutParams(-2, -2));
    gDlBox.setVisibility(View.GONE);
    gRoot.addView(gDlBox, new LinearLayout.LayoutParams(-1, -2));

    // 底部固定区（不随内容滚动）
    buildPlayerBar(gAct);
    buildActionBar(gAct);
    addCloseButton(gAct);

    gDialog = new AlertDialog.Builder(gAct, android.R.style.Theme_Material_Light_Dialog_MinWidth).create();
    gDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
        public void onDismiss(android.content.DialogInterface d) {
            stopPreview();
            stopMusic();
            gRefreshing = false;
        }
    });
    gDialog.show();

    Window win = gDialog.getWindow();
    if (win != null) {
        // 不清除该标志，弹窗无法获取输入法焦点，键盘弹不出来
        win.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        win.setContentView(gRoot);
        GradientDrawable dlgBg = new GradientDrawable();
        dlgBg.setColor(C_BG);
        dlgBg.setCornerRadius(dp2px(gAct, 28));
        win.setBackgroundDrawable(dlgBg);
        WindowManager.LayoutParams params = win.getAttributes();
        params.width = (int)(gAct.getResources().getDisplayMetrics().widthPixels * 0.92);
        win.setAttributes(params);
    }

    gRootLimit = 60;
    gQuery = "";
    gSearchBox = null;
    gSearchRow = null;
    gRefreshBar = null;
    gRefreshText = null;
    renderHomePage();
}

// ===== 首页：语音包 / 音乐 / 音乐盒 =====
void renderHomePage() {
    if (gContent == null) return;
    exitBatchMode();
    stopPreview();
    stopMusic();
    hidePlayerBar();
    gSelectedFile = null;
    if (gActionBar != null) gActionBar.setVisibility(View.GONE);
    gContent.removeAllViews();
    gListBox = null;
    gShownCats = new ArrayList();
    gCountViews = new ArrayList();
    Context act = gAct;

    addPageTitle(act, "语音包发送器");
    addPageSubtitle(act, "云端语音包 · 在线试听 · 一键发送");

    LinearLayout grid = new LinearLayout(act);
    grid.setOrientation(LinearLayout.VERTICAL);

    LinearLayout row1 = new LinearLayout(act);
    row1.setOrientation(LinearLayout.HORIZONTAL);
    addHomeCard(act, row1, "语音包", "云端语音包，逐级挑选", new View.OnClickListener() {
        public void onClick(View v) {
            gRootScrollY = 0;
            renderRootPage();
        }
    });
    addHomeCard(act, row1, "音乐", "本地音乐，选择并发送", new View.OnClickListener() {
        public void onClick(View v) {
            gPageLimit = 80;
            renderMusicPage();
            scrollTop();
        }
    });
    grid.addView(row1, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout row2 = new LinearLayout(act);
    row2.setOrientation(LinearLayout.HORIZONTAL);
    addHomeCard(act, row2, "音乐盒", "网易云搜索，随点随听", new View.OnClickListener() {
        public void onClick(View v) {
            renderMusicBoxPage();
        }
    });
    addHomeCard(act, row2, "会员账号", "网易云账号 / Cookie", new View.OnClickListener() {
        public void onClick(View v) {
            showNeLogin();
        }
    });
    grid.addView(row2, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
    gridLp.topMargin = dp2px(act, 2);
    gContent.addView(grid, gridLp);

    TextView ver = new TextView(act);
    ver.setText("插件 v" + localPluginVersion() + " · 点此检查更新");
    ver.setTextSize(12);
    ver.setTextColor(C_SUB);
    ver.setGravity(Gravity.CENTER);
    ver.setPadding(0, dp2px(act, 12), 0, dp2px(act, 4));
    ver.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            forceCheckUpdate(true);
        }
    });
    gContent.addView(ver, new LinearLayout.LayoutParams(-1, -2));

    updateDialogSize(480);
}

void addHomeCard(Context act, LinearLayout parent, String title, String desc, View.OnClickListener l) {
    LinearLayout card = new LinearLayout(act);
    card.setOrientation(LinearLayout.HORIZONTAL);
    card.setGravity(Gravity.CENTER_VERTICAL);
    card.setPadding(dp2px(act, 14), dp2px(act, 14), dp2px(act, 8), dp2px(act, 14));
    card.setBackground(makeCardBg(act, 20));
    card.setMinimumHeight(dp2px(act, 84));

    LinearLayout textBox = new LinearLayout(act);
    textBox.setOrientation(LinearLayout.VERTICAL);

    TextView t = new TextView(act);
    t.setText(title);
    t.setTextSize(17);
    t.setTypeface(Typeface.DEFAULT_BOLD);
    t.setTextColor(C_TEXT);
    textBox.addView(t);

    TextView d = new TextView(act);
    d.setText(desc);
    d.setTextSize(12);
    d.setTextColor(C_SUB);
    d.setMaxLines(2);
    d.setEllipsize(TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
    dlp.topMargin = dp2px(act, 5);
    textBox.addView(d, dlp);
    card.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1f));

    TextView chev = new TextView(act);
    chev.setText("›");
    chev.setTextSize(22);
    chev.setTextColor(C_CHEVRON);
    card.addView(chev);

    card.setOnClickListener(l);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
    lp.setMargins(dp2px(act, 5), dp2px(act, 5), dp2px(act, 5), dp2px(act, 5));
    parent.addView(card, lp);
}

// ===== 二级：音乐列表 =====
void renderMusicPage() {
    if (gContent == null) return;
    stopPreview();
    stopMusic();
    hidePlayerBar();
    gContent.removeAllViews();
    gListBox = null;
    gShownCats = new ArrayList();
    gCountViews = new ArrayList();
    Context act = gAct;

    resetSelectionBar();
    if (gActionBar != null) gActionBar.setVisibility(View.VISIBLE);

    java.io.File dir = new java.io.File(getMusicDir());
    try { if (!dir.exists()) dir.mkdirs(); } catch (Exception e) {}
    final List files = new ArrayList();
    java.io.File[] fs = dir.listFiles();
    if (fs != null) {
        for (int i = 0; i < fs.length; i++) {
            if (!fs[i].isDirectory() && isAudio(fs[i].getName())) files.add(fs[i]);
        }
    }
    sortFiles(files);

    int total = files.size();
    int show = gPageLimit;
    if (show > total) show = total;

    // 顶栏：返回 + 标题
    LinearLayout bar = new LinearLayout(act);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);

    TextView backBtn = new TextView(act);
    backBtn.setText("‹ 返回");
    backBtn.setTextSize(17);
    backBtn.setTextColor(C_BLUE);
    backBtn.setPadding(0, dp2px(act, 2), dp2px(act, 12), dp2px(act, 6));
    bar.addView(backBtn);
    backBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            renderHomePage();
        }
    });

    TextView title = new TextView(act);
    title.setText("音乐");
    title.setTextSize(20);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setTextColor(C_TEXT);
    bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

    TextView countView = new TextView(act);
    countView.setText(total + " 首");
    countView.setTextSize(13);
    countView.setTextColor(C_SUB);
    bar.addView(countView);

    TextView importBtn = new TextView(act);
    importBtn.setText("＋ 导入");
    importBtn.setTextSize(14);
    importBtn.setTextColor(C_BLUE);
    importBtn.setPadding(dp2px(act, 10), dp2px(act, 2), 0, dp2px(act, 2));
    bar.addView(importBtn);
    importBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            openImportPage();
        }
    });

    TextView batchBtn = new TextView(act);
    batchBtn.setText(gBatchMode ? "完成" : "多选");
    batchBtn.setTextSize(14);
    batchBtn.setTextColor(C_BLUE);
    batchBtn.setPadding(dp2px(act, 10), dp2px(act, 2), 0, dp2px(act, 2));
    bar.addView(batchBtn);
    batchBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            if (gBatchMode) {
                exitBatchMode();
            } else {
                gBatchMode = true;
                gSelectedFile = null;
            }
            renderMusicPage();
        }
    });

    gContent.addView(bar);

    if (total == 0) {
        TextView empty = new TextView(act);
        empty.setText("audio 目录下还没有音乐\n\n把 mp3 / m4a / wav 等音频放到:\n" + getMusicDir() + "\n或点右上角「＋ 导入」从手机里选");
        empty.setTextSize(13);
        empty.setTextColor(C_SUB);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp2px(act, 16), dp2px(act, 28), dp2px(act, 16), dp2px(act, 28));
        empty.setBackground(makeCardBg(act, 18));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = dp2px(act, 10);
        gContent.addView(empty, elp);
        updateDialogSize(370);
        return;
    }

    TextView hint = new TextView(act);
    hint.setText(gBatchMode ? "点选多首音乐，再用底部「批量发送」" : "点选一首音乐，再用下方按钮播放或发送");
    hint.setTextSize(12);
    hint.setTextColor(C_SUB);
    LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
    hintLp.topMargin = dp2px(act, 2);
    hintLp.bottomMargin = dp2px(act, 10);
    gContent.addView(hint, hintLp);

    // 多选工具行
    final List tileViews = new ArrayList();
    if (gBatchMode) {
        LinearLayout tools = new LinearLayout(act);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        TextView selAll = makeSmallPill(act, "全选");
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(-2, -2);
        pillLp.rightMargin = dp2px(act, 8);
        tools.addView(selAll, pillLp);
        TextView clearAll = makeSmallPill(act, "清空");
        tools.addView(clearAll, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams toolsLp = new LinearLayout.LayoutParams(-1, -2);
        toolsLp.bottomMargin = dp2px(act, 8);
        gContent.addView(tools, toolsLp);

        selAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                for (int k = 0; k < files.size(); k++) {
                    gMusicSel.add(((java.io.File) files.get(k)).getAbsolutePath());
                }
                for (int k = 0; k < tileViews.size(); k++) {
                    applyMusicTileStyle(gAct, (TextView) tileViews.get(k), true);
                }
                updateBatchUI();
            }
        });
        clearAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                gMusicSel = new java.util.HashSet();
                for (int k = 0; k < tileViews.size(); k++) {
                    applyMusicTileStyle(gAct, (TextView) tileViews.get(k), false);
                }
                updateBatchUI();
            }
        });
    }

    LinearLayout row = null;
    for (int i = 0; i < show; i++) {
        if (i % 2 == 0) {
            row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            gContent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        final java.io.File musicFile = (java.io.File) files.get(i);
        String label = displayLabel(musicFile.getName());

        final TextView btn = new TextView(act);
        btn.setText(label);
        btn.setTextSize(14);
        btn.setTextColor(C_TEXT);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp2px(act, 8), dp2px(act, 8), dp2px(act, 8), dp2px(act, 8));
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setBackground(makeCardBg(act, 14));
        if (gBatchMode && gMusicSel.contains(musicFile.getAbsolutePath())) {
            applyMusicTileStyle(act, btn, true);
        }

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, dp2px(act, 52), 1f);
        btnLp.setMargins(dp2px(act, 4), dp2px(act, 4), dp2px(act, 4), dp2px(act, 4));
        row.addView(btn, btnLp);

        final int myIndex = i;
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (gBatchMode) {
                    String p = musicFile.getAbsolutePath();
                    boolean sel;
                    if (gMusicSel.contains(p)) {
                        gMusicSel.remove(p);
                        sel = false;
                    } else {
                        gMusicSel.add(p);
                        sel = true;
                    }
                    applyMusicTileStyle(gAct, btn, sel);
                    updateBatchUI();
                    return;
                }
                stopPreview();
                gSelectedFile = musicFile;
                applyTileSelection(gAct, tileViews, myIndex);
                showSelectionBar(musicFile);
            }
        });
        tileViews.add(btn);

        if (i == show - 1 && i % 2 == 0) {
            View spacer = new View(act);
            LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(0, 1, 1f);
            spacerLp.setMargins(dp2px(act, 4), 0, dp2px(act, 4), 0);
            row.addView(spacer, spacerLp);
        }
    }

    int rest = total - show;
    if (rest > 0) {
        TextView more = new TextView(act);
        more.setText("加载更多（还有 " + rest + " 首）");
        more.setTextSize(14);
        more.setTextColor(C_BLUE);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, dp2px(act, 14), 0, dp2px(act, 14));
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(-1, -2);
        moreLp.topMargin = dp2px(act, 4);
        gContent.addView(more, moreLp);
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                gPageLimit = gPageLimit + 80;
                renderMusicPage();
            }
        });
    }

    int rows = (show + 1) / 2;
    updateBatchUI();
    updateDialogSize(230 + rows * 60 + (rest > 0 ? 46 : 0) + 64);
}

// ===== 三级：导入音乐（浏览手机存储） =====
String getBrowseStartDir() {
    try {
        java.io.File ext = android.os.Environment.getExternalStorageDirectory();
        if (ext != null && ext.exists()) return ext.getAbsolutePath();
    } catch (Exception e) {}
    return "/sdcard";
}

void openImportPage() {
    exitBatchMode();
    gBrowseDir = new java.io.File(getBrowseStartDir());
    gImportSel = new java.util.HashSet();
    gBrowseLimit = 100;
    if (!checkImportPermission()) {
        toast("请允许存储/音频权限，授权后会自动刷新");
    }
    renderImportPage();
    scrollTop();
}

// 没有读音频权限时，系统会把文件隐藏，只剩文件夹
boolean checkImportPermission() {
    try {
        String perm = (android.os.Build.VERSION.SDK_INT >= 33)
            ? "android.permission.READ_MEDIA_AUDIO"
            : "android.permission.READ_EXTERNAL_STORAGE";
        int state = -1;
        try {
            state = gAct.checkSelfPermission(perm);
        } catch (Exception e1) {
            try {
                state = gAct.getPackageManager().checkPermission(perm, gAct.getPackageName());
            } catch (Exception e2) {}
        }
        if (state == android.content.pm.PackageManager.PERMISSION_GRANTED) return true;

        try {
            java.util.List perms = new java.util.ArrayList();
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                perms.add("android.permission.READ_MEDIA_AUDIO");
            } else {
                perms.add("android.permission.READ_EXTERNAL_STORAGE");
                perms.add("android.permission.WRITE_EXTERNAL_STORAGE");
            }
            String[] arr = (String[]) perms.toArray(new String[perms.size()]);
            gAct.requestPermissions(arr, 1003);
        } catch (Exception e3) {}
        return false;
    } catch (Throwable t) {
        return true;
    }
}

public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    if (requestCode == 1003) {
        try {
            renderImportPage();
        } catch (Throwable t) {}
    }
}

void scanBrowseDir() {
    gBrowseEntries = new ArrayList();
    gBrowseDirCount = 0;
    gBrowseFileCount = 0;
    gBrowseAudioCount = 0;
    if (gBrowseDir == null || !gBrowseDir.isDirectory()) return;
    java.io.File[] fs = gBrowseDir.listFiles();
    if (fs == null) return;
    List dirs = new ArrayList();
    List files = new ArrayList();
    for (int i = 0; i < fs.length; i++) {
        String n = fs[i].getName();
        if (n.startsWith(".")) continue;
        // 不按扩展名过滤，避免文件因格式判断失败而整块消失
        if (fs[i].isDirectory()) dirs.add(fs[i]);
        else files.add(fs[i]);
    }
    sortFiles(dirs);
    sortFiles(files);
    gBrowseEntries.addAll(dirs);
    gBrowseEntries.addAll(files);
    gBrowseDirCount = dirs.size();
    gBrowseFileCount = files.size();
    for (int i = 0; i < files.size(); i++) {
        if (isAudio(((java.io.File) files.get(i)).getName())) gBrowseAudioCount++;
    }
}

String formatSize(long size) {
    if (size < 1024) return size + " B";
    if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
    return String.format("%.1f MB", size / (1024.0 * 1024));
}

void applyImportBtnStyle(Context act, TextView b) {
    boolean on = gImportSel.size() > 0;
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(on ? C_BLUE : C_LINE);
    bg.setCornerRadius(dp2px(act, 13));
    b.setBackground(bg);
    b.setTextColor(on ? Color.WHITE : C_SUB);
    b.setEnabled(on);
    b.setText(on ? ("导入选中的 " + gImportSel.size() + " 个") : "先选择要导入的音乐");
}

void applyImportRowStyle(Context act, LinearLayout row, TextView name, TextView meta, boolean sel) {
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(sel ? C_SEL_BG : C_CARD_SOFT);
    bg.setCornerRadius(dp2px(act, 12));
    bg.setStroke(1, sel ? C_BLUE : C_LINE);
    row.setBackground(bg);
    name.setTextColor(sel ? C_BLUE : C_TEXT);
    meta.setTextColor(sel ? C_BLUE : C_SUB);
}

void renderImportPage() {
    if (gContent == null) return;
    stopPreview();
    gContent.removeAllViews();
    gListBox = null;
    gShownCats = new ArrayList();
    gCountViews = new ArrayList();
    Context act = gAct;

    resetSelectionBar();
    if (gActionBar != null) gActionBar.setVisibility(View.GONE);

    scanBrowseDir();
    int total = gBrowseEntries.size();
    int show = gBrowseLimit;
    if (show > total) show = total;

    // 顶栏：返回 + 标题 + 已选数
    LinearLayout bar = new LinearLayout(act);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);

    TextView backBtn = new TextView(act);
    backBtn.setText("‹ 返回");
    backBtn.setTextSize(17);
    backBtn.setTextColor(C_BLUE);
    backBtn.setPadding(0, dp2px(act, 2), dp2px(act, 12), dp2px(act, 6));
    bar.addView(backBtn);
    backBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            renderMusicPage();
        }
    });

    TextView title = new TextView(act);
    title.setText("导入音乐");
    title.setTextSize(20);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setTextColor(C_TEXT);
    bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

    final TextView selCount = new TextView(act);
    selCount.setText("已选 " + gImportSel.size());
    selCount.setTextSize(13);
    selCount.setTextColor(C_SUB);
    bar.addView(selCount);
    gContent.addView(bar);

    // 当前目录
    TextView path = new TextView(act);
    path.setText(gBrowseDir == null ? "" : gBrowseDir.getAbsolutePath());
    path.setTextSize(11);
    path.setTextColor(C_SUB);
    path.setSingleLine(true);
    path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(-1, -2);
    pathLp.topMargin = dp2px(act, 2);
    pathLp.bottomMargin = dp2px(act, 2);
    gContent.addView(path, pathLp);

    TextView stat = new TextView(act);
    stat.setText("文件夹 " + gBrowseDirCount + " · 文件 " + gBrowseFileCount + "（音频 " + gBrowseAudioCount + "）");
    stat.setTextSize(11);
    stat.setTextColor(C_SUB);
    LinearLayout.LayoutParams statLp = new LinearLayout.LayoutParams(-1, -2);
    statLp.bottomMargin = dp2px(act, 8);
    gContent.addView(stat, statLp);

    // 导入按钮
    final TextView importBtn = new TextView(act);
    importBtn.setGravity(Gravity.CENTER);
    importBtn.setTextSize(15);
    importBtn.setTypeface(Typeface.DEFAULT_BOLD);
    applyImportBtnStyle(act, importBtn);
    LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(-1, dp2px(act, 44));
    ibLp.bottomMargin = dp2px(act, 8);
    gContent.addView(importBtn, ibLp);
    importBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            if (gImportSel.size() == 0) return;
            importSelectedMusic();
        }
    });

    // 上一级
    if (gBrowseDir != null && gBrowseDir.getParentFile() != null) {
        final java.io.File parent = gBrowseDir.getParentFile();
        TextView up = new TextView(act);
        up.setText("..  上一级");
        up.setTextSize(15);
        up.setTextColor(C_TEXT);
        up.setPadding(dp2px(act, 14), dp2px(act, 12), dp2px(act, 14), dp2px(act, 12));
        up.setBackground(makeCardBg(act, 12));
        LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(-1, -2);
        upLp.topMargin = dp2px(act, 2);
        upLp.bottomMargin = dp2px(act, 2);
        gContent.addView(up, upLp);
        up.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                gBrowseDir = parent;
                gBrowseLimit = 100;
                renderImportPage();
                scrollTop();
            }
        });
    }

    if (total == 0) {
        TextView empty = new TextView(act);
        empty.setText("这个目录里没有可显示的文件\n\n如果确定有音乐却看不到，是系统把文件隐藏了：\n去系统设置给 QQ 开启「音频/存储」权限后\n重新进入导入页");
        empty.setTextSize(13);
        empty.setTextColor(C_SUB);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp2px(act, 16), dp2px(act, 26), dp2px(act, 16), dp2px(act, 26));
        empty.setBackground(makeCardBg(act, 16));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = dp2px(act, 6);
        gContent.addView(empty, elp);
        updateDialogSize(430);
        return;
    }

    for (int i = 0; i < show; i++) {
        final java.io.File entry = (java.io.File) gBrowseEntries.get(i);
        final boolean isDir = entry.isDirectory();

        final LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp2px(act, 14), dp2px(act, 11), dp2px(act, 14), dp2px(act, 11));

        final TextView name = new TextView(act);
        name.setText(entry.getName());
        name.setTextSize(15);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView meta = new TextView(act);
        final boolean audioFile = !isDir && isAudio(entry.getName());
        if (isDir) {
            meta.setText("文件夹");
        } else if (audioFile) {
            meta.setText(formatSize(entry.length()));
        } else {
            meta.setText("非音频 · " + formatSize(entry.length()));
        }
        meta.setTextSize(11);
        meta.setPadding(dp2px(act, 8), 0, 0, 0);
        row.addView(meta);

        boolean sel = audioFile && gImportSel.contains(entry.getAbsolutePath());
        applyImportRowStyle(act, row, name, meta, sel);
        if (!isDir && !audioFile) name.setTextColor(C_SUB);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp2px(act, 2);
        rowLp.bottomMargin = dp2px(act, 2);
        gContent.addView(row, rowLp);

        if (isDir) {
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    gBrowseDir = entry;
                    gBrowseLimit = 100;
                    renderImportPage();
                    scrollTop();
                }
            });
        } else if (!audioFile) {
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    toast("不是支持的音频格式");
                }
            });
        } else {
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String p = entry.getAbsolutePath();
                    boolean now;
                    if (gImportSel.contains(p)) {
                        gImportSel.remove(p);
                        now = false;
                    } else {
                        gImportSel.add(p);
                        now = true;
                    }
                    applyImportRowStyle(gAct, row, name, meta, now);
                    selCount.setText("已选 " + gImportSel.size());
                    applyImportBtnStyle(gAct, importBtn);
                }
            });
        }
    }

    int rest = total - show;
    if (rest > 0) {
        TextView more = new TextView(act);
        more.setText("加载更多（还有 " + rest + " 项）");
        more.setTextSize(14);
        more.setTextColor(C_BLUE);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, dp2px(act, 14), 0, dp2px(act, 14));
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(-1, -2);
        moreLp.topMargin = dp2px(act, 4);
        gContent.addView(more, moreLp);
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                gBrowseLimit = gBrowseLimit + 100;
                renderImportPage();
            }
        });
    }

    updateDialogSize(270 + show * 48 + (rest > 0 ? 46 : 0));
}

// 返回 1 成功 / 2 已存在 / 0 失败
int copyMusicFile(java.io.File src, java.io.File targetDir) {
    java.io.FileInputStream fis = null;
    java.io.FileOutputStream fos = null;
    try {
        if (src == null || !src.exists() || !src.canRead()) return 0;
        if (!targetDir.exists()) targetDir.mkdirs();
        java.io.File dst = new java.io.File(targetDir, src.getName());
        if (dst.exists()) return 2;
        fis = new java.io.FileInputStream(src);
        fos = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int len;
        while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
        fos.flush();
        return (dst.exists() && dst.length() == src.length()) ? 1 : 0;
    } catch (Exception e) {
        return 0;
    } finally {
        try { if (fos != null) fos.close(); } catch (Exception e) {}
        try { if (fis != null) fis.close(); } catch (Exception e) {}
    }
}

void importSelectedMusic() {
    if (gImportSel.size() == 0) return;
    final List paths = new ArrayList(gImportSel);
    final java.io.File targetDir = new java.io.File(getMusicDir());
    toast("正在导入 " + paths.size() + " 个，请稍候");
    new Thread(new Runnable() {
        public void run() {
            int ok = 0;
            int exist = 0;
            int fail = 0;
            for (int i = 0; i < paths.size(); i++) {
                int r = copyMusicFile(new java.io.File((String) paths.get(i)), targetDir);
                if (r == 1) ok++;
                else if (r == 2) exist++;
                else fail++;
            }
            final int fok = ok;
            final int fexist = exist;
            final int ffail = fail;
            if (gUI == null) gUI = new android.os.Handler(android.os.Looper.getMainLooper());
            gUI.post(new Runnable() {
                public void run() {
                    gImportSel = new java.util.HashSet();
                    toast("导入完成：成功 " + fok + "，已存在 " + fexist + "，失败 " + ffail);
                    renderMusicPage();
                }
            });
        }
    }).start();
}

// ===== 云端语音盒：拉取 / 解析 / 下载 =====
String enc(String s) {
    try {
        return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    } catch (Exception e) {
        return s;
    }
}

String cloudUrl(String dir, String fileName) {
    return CLOUD_BASE + "audio/" + enc(dir) + "/" + enc(fileName);
}

String getCloudCacheDir() {
    // 云端下载与本地语音共用同一个 voice 目录
    return getVoiceDir();
}

java.io.File cloudCacheFile(String dir, String fileName) {
    return new java.io.File(getCloudCacheDir() + "/" + dir + "/" + fileName);
}

String httpGet(String url) throws Exception {
    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
    conn.setConnectTimeout(10000);
    conn.setReadTimeout(25000);
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
    conn.setRequestProperty("Accept", "*/*");
    conn.setInstanceFollowRedirects(true);
    int code = conn.getResponseCode();
    if (code != 200) {
        try { conn.disconnect(); } catch (Exception e) {}
        throw new Exception("HTTP " + code);
    }
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    int total = conn.getContentLength();
    java.io.InputStream in = conn.getInputStream();
    updateCloudBar(0, 0, total);
    byte[] buf = new byte[8192];
    int len;
    long got = 0;
    long lastGot = 0;
    int lastPct = -1;
    while ((len = in.read(buf)) > 0) {
        bos.write(buf, 0, len);
        got += len;
        if (total > 0) {
            int pct = (int) (got * 100 / total);
            if (pct != lastPct) {
                lastPct = pct;
                updateCloudBar(pct, got, total);
            }
        } else if (got - lastGot >= 65536) {
            lastGot = got;
            updateCloudBar(-1, got, total);
        }
    }
    try { in.close(); } catch (Exception e) {}
    try { conn.disconnect(); } catch (Exception e) {}
    return new String(bos.toByteArray(), "UTF-8");
}

// 静默 GET：不触发下载进度条，用于拉取很小的版本数字
String httpGetSilent(String url) throws Exception {
    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
    conn.setConnectTimeout(8000);
    conn.setReadTimeout(12000);
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
    conn.setRequestProperty("Accept", "*/*");
    conn.setRequestProperty("Cache-Control", "no-cache");
    conn.setInstanceFollowRedirects(true);
    int code = conn.getResponseCode();
    if (code != 200) {
        try { conn.disconnect(); } catch (Exception e) {}
        throw new Exception("HTTP " + code);
    }
    java.io.InputStream in = conn.getInputStream();
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int len;
    while ((len = in.read(buf)) > 0) bos.write(buf, 0, len);
    try { in.close(); } catch (Exception e) {}
    try { conn.disconnect(); } catch (Exception e) {}
    return new String(bos.toByteArray(), "UTF-8");
}

String httpGetCookie(String url, String cookie, boolean mobile) throws Exception {
    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
    conn.setConnectTimeout(10000);
    conn.setReadTimeout(15000);
    conn.setRequestProperty("User-Agent", mobile
        ? "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0 Mobile Safari/537.36"
        : "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
    conn.setRequestProperty("Accept", "*/*");
    conn.setRequestProperty("Referer", "https://music.163.com/");
    if (cookie != null && cookie.length() > 0) {
        conn.setRequestProperty("Cookie", cookie);
    }
    conn.setInstanceFollowRedirects(true);
    int code = conn.getResponseCode();
    if (code != 200) {
        try { conn.disconnect(); } catch (Exception e) {}
        throw new Exception("HTTP " + code);
    }
    java.io.InputStream in = conn.getInputStream();
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int len;
    while ((len = in.read(buf)) > 0) bos.write(buf, 0, len);
    try { in.close(); } catch (Exception e) {}
    try { conn.disconnect(); } catch (Exception e) {}
    return new String(bos.toByteArray(), "UTF-8");
}

int cloudTotalFiles() {
    int n = 0;
    for (int i = 0; i < gCatCounts.size(); i++) {
        try { n += ((Integer) gCatCounts.get(i)).intValue(); } catch (Throwable t) {}
    }
    return n;
}

// ===== 下载进度条 =====
android.widget.ProgressBar makeProgressBar(Context ctx, int hDp) {
    android.widget.ProgressBar pb = new android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
    int h = dp2px(ctx, hDp);
    pb.setMax(100);
    GradientDrawable track = new GradientDrawable();
    track.setColor(0x1A000000);
    track.setCornerRadius(h / 2f);
    GradientDrawable fill = new GradientDrawable();
    fill.setColors(new int[]{Color.parseColor("#3A9BFF"), C_BLUE});
    fill.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
    fill.setCornerRadius(h / 2f);
    android.graphics.drawable.ClipDrawable clip =
        new android.graphics.drawable.ClipDrawable(fill, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
    android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
        new android.graphics.drawable.Drawable[]{ track, clip });
    pb.setProgressDrawable(ld);
    pb.setMinimumHeight(h);
    return pb;
}

void updateCloudBar(final int pct, final long got, final long total) {
    postUi(new Runnable() {
        public void run() {
            if (gDlBox == null) return;
            gDlBox.setVisibility(View.VISIBLE);
            if (gDlBar != null) gDlBar.setIndeterminate(false);
            if (pct >= 0 && total > 0) {
                if (gDlBar != null) gDlBar.setProgress(pct);
                if (gDlText != null) gDlText.setText("列表 " + pct + "%");
            } else if (gDlText != null) {
                gDlText.setText("列表 " + (got / 1024) + " KB");
            }
        }
    });
}

void showCloudBar() {
    postUi(new Runnable() {
        public void run() {
            if (gDlBox == null) return;
            gDlBox.setVisibility(View.VISIBLE);
            if (gDlBar != null) {
                gDlBar.setIndeterminate(false);
                gDlBar.setProgress(0);
            }
            if (gDlText != null) gDlText.setText("获取列表中…");
        }
    });
}

void postUi(Runnable r) {
    if (gUI == null) gUI = new android.os.Handler(android.os.Looper.getMainLooper());
    gUI.post(r);
}

void showDlStart() {
    postUi(new Runnable() {
        public void run() {
            if (gDlBox == null) return;
            gDlBox.setVisibility(View.VISIBLE);
            if (gDlBar != null) gDlBar.setProgress(0);
            if (gDlText != null) gDlText.setText("开始下载…");
        }
    });
}

void updateDlProgress(final int pct, final long got, final long total) {
    postUi(new Runnable() {
        public void run() {
            if (gDlBox == null) return;
            gDlBox.setVisibility(View.VISIBLE);
            if (pct >= 0) {
                if (gDlBar != null) gDlBar.setProgress(pct);
                if (gDlText != null) gDlText.setText("下载中 " + pct + "%");
            } else if (gDlText != null) {
                gDlText.setText("已下载 " + (got / 1024) + " KB");
            }
        }
    });
}

void hideDlBar() {
    postUi(new Runnable() {
        public void run() {
            if (gDlBox == null) return;
            gDlBox.setVisibility(View.GONE);
            if (gDlBar != null) gDlBar.setProgress(0);
            if (gDlText != null) gDlText.setText("");
        }
    });
}

boolean httpDownload(String url, java.io.File dest) {
    return httpDownloadEx(url, dest, null);
}

boolean httpDownloadEx(String url, java.io.File dest, String cookie) {
    java.io.FileOutputStream fos = null;
    java.io.InputStream in = null;
    try {
        if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0 Mobile Safari/537.36");
        String dlRef = "https://music.163.com/";
        if (url != null && url.indexOf("qqmusic") >= 0) dlRef = "https://y.qq.com/";
        else if (url != null && url.indexOf("kugou") >= 0) dlRef = "https://www.kugou.com/";
        conn.setRequestProperty("Referer", dlRef);
        conn.setRequestProperty("Accept", "*/*");
        if (cookie != null && cookie.length() > 0) {
            conn.setRequestProperty("Cookie", cookie);
        }
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code != 200) {
            try { conn.disconnect(); } catch (Exception e) {}
            return false;
        }
        int total = conn.getContentLength();
        in = conn.getInputStream();
        fos = new java.io.FileOutputStream(dest);
        showDlStart();
        byte[] buf = new byte[8192];
        int len;
        long got = 0;
        long lastGot = 0;
        int lastPct = -1;
        while ((len = in.read(buf)) > 0) {
            fos.write(buf, 0, len);
            got += len;
            int pct = total > 0 ? (int) (got * 100 / total) : -1;
            if (pct >= 0) {
                if (pct != lastPct) {
                    lastPct = pct;
                    updateDlProgress(pct, got, total);
                }
            } else if (got - lastGot >= 65536) {
                lastGot = got;
                updateDlProgress(-1, got, total);
            }
        }
        fos.flush();
        return dest.length() > 0;
    } catch (Throwable t) {
        try { dest.delete(); } catch (Exception e) {}
        return false;
    } finally {
        try { if (fos != null) fos.close(); } catch (Exception e) {}
        try { if (in != null) in.close(); } catch (Exception e) {}
    }
}

String readTextFile(String path) {
    java.io.File f = new java.io.File(path);
    if (!f.exists()) return null;
    java.io.FileInputStream fis = null;
    try {
        fis = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = fis.read(buf)) > 0) bos.write(buf, 0, len);
        return new String(bos.toByteArray(), "UTF-8");
    } catch (Exception e) {
        return null;
    } finally {
        try { if (fis != null) fis.close(); } catch (Exception e) {}
    }
}

void writeTextFile(String path, String text) {
    java.io.FileOutputStream fos = null;
    try {
        fos = new java.io.FileOutputStream(path);
        fos.write(text.getBytes("UTF-8"));
        fos.flush();
    } catch (Exception e) {
    } finally {
        try { if (fos != null) fos.close(); } catch (Exception e) {}
    }
}

// 解析站点 data.json：{"packs":[{"name","dir","files":[{"f","n"}]}]}
boolean parseCloudData(String json) {
    try {
        org.json.JSONObject root = new org.json.JSONObject(json);
        org.json.JSONArray packs = root.optJSONArray("packs");
        if (packs == null) return false;
        List names = new ArrayList();
        List dirs = new ArrayList();
        List filesAll = new ArrayList();
        List counts = new ArrayList();
        for (int i = 0; i < packs.length(); i++) {
            org.json.JSONObject p = packs.getJSONObject(i);
            String dir = p.optString("dir", "");
            String name = p.optString("name", dir);
            if (name.length() == 0) name = dir;
            if (dir.length() == 0) dir = name;
            org.json.JSONArray fs = p.optJSONArray("files");
            List items = new ArrayList();
            if (fs != null) {
                for (int j = 0; j < fs.length(); j++) {
                    org.json.JSONObject f = fs.getJSONObject(j);
                    String fn = f.optString("f", "");
                    if (fn.length() == 0) continue;
                    String dn = f.optString("n", fn);
                    String alt = f.optString("u", "");
                    items.add(new String[]{dn, fn, alt});
                }
            }
            names.add(name);
            dirs.add(dir);
            filesAll.add(items);
            counts.add(Integer.valueOf(items.size()));
        }
        if (names.size() == 0) return false;
        gCatNames = names;
        gCatDirs = dirs;
        gCatFiles = filesAll;
        gCatCounts = counts;
        return true;
    } catch (Throwable t) {
        return false;
    }
}

// 站点未单独部署 data.json 时（访问该路径会回退成首页），从首页内嵌的 DATA_INLINE 里提取
String extractInlineData(String text) {
    if (text == null) return null;
    int i = text.indexOf("DATA_INLINE");
    if (i < 0) return null;
    int eq = text.indexOf('=', i + 11);
    if (eq < 0) return null;
    int start = text.indexOf('{', eq);
    if (start < 0) return null;
    int depth = 0;
    boolean inStr = false;
    boolean esc = false;
    for (int k = start; k < text.length(); k++) {
        char c = text.charAt(k);
        if (inStr) {
            if (esc) esc = false;
            else if (c == '\\') esc = true;
            else if (c == '"') inStr = false;
            continue;
        }
        if (c == '"') {
            inStr = true;
        } else if (c == '{') {
            depth++;
        } else if (c == '}') {
            depth--;
            if (depth == 0) return text.substring(start, k + 1);
        }
    }
    return null;
}

void startCloudLoad() {
    if (gCloudLoading) return;
    String cachePath = pluginPath + "/cloud_packs.json";

    // 1) 先用本地缓存秒开，不等网络
    boolean hadCache = false;
    String cached = readTextFile(cachePath);
    if (cached != null && parseCloudData(cached)) {
        hadCache = true;
        gCloudLoaded = true;
        gCloudMode = true;
        gCloudError = "";
        renderRootPage();
    }

    gCloudLoading = true;
    if (!hadCache) {
        gCloudLoaded = false;
        gCloudMode = true;
        gCloudError = "";
        renderRootPage();
    }

    final boolean fhadCache = hadCache;
    final int localPacks = gCatNames.size();
    final int localFiles = cloudTotalFiles();

    new Thread(new Runnable() {
        public void run() {
            String json = null;
            String err = "";
            boolean same = false;

            // 2) 有缓存时只取两个数字（packs / files）比对，不下载整表
            if (fhadCache) {
                try {
                    String vraw = httpGetSilent(CLOUD_BASE + "version.json");
                    if (vraw != null) {
                        int sp = -1;
                        int sf = -1;
                        try {
                            org.json.JSONObject v = new org.json.JSONObject(vraw);
                            sp = v.optInt("packs", -1);
                            sf = v.optInt("files", -1);
                        } catch (Throwable t) {}
                        if (sp >= 0 && sf >= 0) {
                            same = (sp == localPacks && sf == localFiles);
                        }
                    }
                } catch (Throwable t) {}
            }

            if (same) {
                if (gUI == null) gUI = new android.os.Handler(android.os.Looper.getMainLooper());
                gUI.post(new Runnable() {
                    public void run() {
                        gCloudLoading = false;
                        gCloudLoaded = true;
                        gCloudMode = true;
                        gCloudError = "";
                        hideDlBar();
                    }
                });
                return;
            }

            // 3) 数字对不上 / 没有缓存：下载完整列表
            if (json == null) {
                showCloudBar();
                try {
                    String raw = httpGet(CLOUD_BASE + "data.json");
                    if (raw != null) {
                        String t = raw.trim();
                        if (t.startsWith("{") && t.indexOf("\"packs\"") >= 0) {
                            json = t;
                        } else {
                            // 站点把 data.json 回退成了首页，从首页内嵌数据里提取
                            json = extractInlineData(raw);
                        }
                    }
                } catch (Throwable t) {
                    err = "网络不可用";
                }
                if (json == null) {
                    try {
                        String html = httpGet(CLOUD_BASE);
                        json = extractInlineData(html);
                        if (json != null) err = "";
                    } catch (Throwable t) {
                        if (err.length() == 0) err = "网络不可用";
                    }
                }
            }

            boolean ok = false;
            if (json != null) {
                ok = parseCloudData(json);
                if (ok) writeTextFile(cachePath, json);
            } else if (fhadCache) {
                // 拉取失败但本地有缓存：继续用缓存，不清空列表
                ok = true;
                err = "";
            }
            final boolean fok = ok;
            final String ferr = ok ? "" : (err.length() > 0 ? err : "数据解析失败");
            if (gUI == null) gUI = new android.os.Handler(android.os.Looper.getMainLooper());
            gUI.post(new Runnable() {
                public void run() {
                    gCloudLoading = false;
                    gCloudLoaded = fok;
                    gCloudMode = fok;
                    gCloudError = ferr;
                    hideDlBar();
                    if (!fok) {
                        // 云端失败时回退到本地 voice/
                        scanVoiceDir();
                    }
                    renderRootPage();
                }
            });
        }
    }).start();
}

// 发送前才落盘：云端语音下载到本地
void downloadCloudFile(final java.io.File dest) {
    final String url = (String) gCloudUrls.get(dest.getAbsolutePath());
    if (url == null) {
        toast("缺少云端地址");
        return;
    }
    if (gDownloading.contains(dest.getAbsolutePath())) {
        toast("正在下载中…");
        return;
    }
    gDownloading.add(dest.getAbsolutePath());
    new Thread(new Runnable() {
        public void run() {
            boolean ok = httpDownload(url, dest);
            if (!ok) {
                // 站点缺文件时用清单里的备用地址回源
                String alt = (String) gCloudAlt.get(dest.getAbsolutePath());
                if (alt != null && alt.length() > 0) ok = httpDownload(alt, dest);
            }
            final boolean fok = ok;
            if (gUI == null) gUI = new android.os.Handler(android.os.Looper.getMainLooper());
            gUI.post(new Runnable() {
                public void run() {
                    gDownloading.remove(dest.getAbsolutePath());
                    hideDlBar();
                    if (!fok) {
                        toast("下载失败，请检查网络");
                        return;
                    }
                    gSelectedFile = dest;
                    sendItem(dest);
                }
            });
        }
    }).start();
}

// ===== 二级：音乐盒（分类列表） =====
void renderRootPage() {
    if (gContent == null) return;
    exitBatchMode();
    stopPreview();
    stopMusic();
    hidePlayerBar();
    gSelectedFile = null;
    if (gActionBar != null) gActionBar.setVisibility(View.GONE);
    gContent.removeAllViews();
    gListBox = null;
    gShownCats = new ArrayList();
    gCountViews = new ArrayList();
    gPageLimit = 80;
    Context act = gAct;

    // 顶栏：返回首页
    LinearLayout topBar = new LinearLayout(act);
    topBar.setOrientation(LinearLayout.HORIZONTAL);
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    TextView topBack = new TextView(act);
    topBack.setText("‹ 返回");
    topBack.setTextSize(17);
    topBack.setTextColor(C_BLUE);
    topBack.setPadding(0, dp2px(act, 2), dp2px(act, 12), dp2px(act, 2));
    topBar.addView(topBack);
    topBack.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            gRootScrollY = 0;
            renderHomePage();
        }
    });
    gContent.addView(topBar);

    addPageTitle(act, "语音包");
    addPageSubtitle(act, (gCloudMode ? "云端 · 共 " : (gCloudError.length() > 0 ? "云端失败，本地回退 · 共 " : "共 ")) + gCatNames.size() + " 类");

    // 云端拉取中 / 失败重试
    if (gCloudMode && !gCloudLoaded) {
        if (!gCloudLoading) {
            startCloudLoad();
            return;
        }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp2px(act, 16), dp2px(act, 20), dp2px(act, 16), dp2px(act, 20));
        box.setBackground(makeCardBg(act, 18));

        TextView st = new TextView(act);
        st.setText(gCloudLoading ? "正在获取云端语音包…" : (gCloudError.length() > 0 ? ("获取失败：" + gCloudError) : "准备获取云端语音包"));
        st.setTextSize(13);
        st.setTextColor(gCloudLoading ? C_SUB : C_TEXT);
        st.setGravity(Gravity.CENTER);
        box.addView(st, new LinearLayout.LayoutParams(-1, -2));

        if (!gCloudLoading) {
            TextView retry = new TextView(act);
            retry.setText("重新获取");
            retry.setTextSize(14);
            retry.setTextColor(C_BLUE);
            retry.setGravity(Gravity.CENTER);
            retry.setPadding(0, dp2px(act, 12), 0, 0);
            box.addView(retry, new LinearLayout.LayoutParams(-1, -2));
            retry.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    gCloudMode = true;
                    startCloudLoad();
                }
            });
        }
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(-1, -2);
        boxLp.topMargin = dp2px(act, 8);
        gContent.addView(box, boxLp);
        updateDialogSize(340);
        return;
    }

    if (gCatNames.size() == 0) {
        TextView empty = new TextView(act);
        if (gCloudMode) {
            empty.setText("云端暂时没有语音包\n点下方「刷新云端列表」重试");
        } else {
            empty.setText((gCloudError.length() > 0 ? ("云端获取失败：" + gCloudError + "\n\n") : "")
                + "voice 目录下还没有语音包\n\n请把语音文件夹放到:\n" + getVoiceDir());
        }
        empty.setTextSize(13);
        empty.setTextColor(C_SUB);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp2px(act, 16), dp2px(act, 28), dp2px(act, 16), dp2px(act, 28));
        empty.setBackground(makeCardBg(act, 18));
        gContent.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        updateDialogSize(320);
        return;
    }

    // 刷新入口：云端重新拉取 / 本地重新统计数量
    if (gCloudMode) {
        TextView refresh = new TextView(act);
        refresh.setText("刷新云端列表");
        refresh.setTextSize(13);
        refresh.setTextColor(C_BLUE);
        refresh.setGravity(Gravity.END);
        refresh.setPadding(0, dp2px(act, 2), dp2px(act, 2), dp2px(act, 3));
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startCloudLoad();
            }
        });
        gContent.addView(refresh, new LinearLayout.LayoutParams(-1, -2));
    } else {
        // 手动刷新数量入口 + 统计进度
        LinearLayout refreshBox = new LinearLayout(act);
        refreshBox.setOrientation(LinearLayout.VERTICAL);

        TextView refresh = new TextView(act);
        refresh.setText(gRefreshing ? "正在统计…" : "刷新语音数量");
        refresh.setTextSize(13);
        refresh.setTextColor(C_BLUE);
        refresh.setGravity(Gravity.END);
        refresh.setPadding(0, dp2px(act, 2), dp2px(act, 2), dp2px(act, 3));
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startRefreshCounts();
            }
        });
        refreshBox.addView(refresh, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout progRow = new LinearLayout(act);
        progRow.setOrientation(LinearLayout.HORIZONTAL);
        progRow.setGravity(Gravity.CENTER_VERTICAL);

        gRefreshBar = new android.widget.ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        gRefreshBar.setMax(1);
        gRefreshBar.setVisibility(gRefreshing ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, -2, 1f);
        barLp.rightMargin = dp2px(act, 8);
        progRow.addView(gRefreshBar, barLp);

        gRefreshText = new TextView(act);
        gRefreshText.setTextSize(11);
        gRefreshText.setTextColor(C_SUB);
        gRefreshText.setVisibility(gRefreshing ? View.VISIBLE : View.GONE);
        progRow.addView(gRefreshText, new LinearLayout.LayoutParams(-2, -2));
        if (gRefreshing) gRefreshText.setText("0/0，约 10 秒");

        refreshBox.addView(progRow, new LinearLayout.LayoutParams(-1, -2));
        gContent.addView(refreshBox, new LinearLayout.LayoutParams(-1, -2));
        if (gRefreshing) updateRefreshProgress();
    }

    addSearchBox(act);

    gListBox = new LinearLayout(act);
    gListBox.setOrientation(LinearLayout.VERTICAL);
    gContent.addView(gListBox, new LinearLayout.LayoutParams(-1, -2));

    renderRootList();
    restoreRootScroll();
}

void restoreRootScroll() {
    if (gScroll == null) return;
    final int y = gRootScrollY;
    gScroll.post(new Runnable() {
        public void run() { gScroll.scrollTo(0, y); }
    });
    gScroll.postDelayed(new Runnable() {
        public void run() { gScroll.scrollTo(0, y); }
    }, 150);
}

void scrollTop() {
    if (gScroll == null) return;
    gScroll.post(new Runnable() {
        public void run() { gScroll.scrollTo(0, 0); }
    });
    gScroll.postDelayed(new Runnable() {
        public void run() { gScroll.scrollTo(0, 0); }
    }, 150);
}

void addSearchBox(Context act) {
    if (gSearchRow == null) {
        gSearchRow = new LinearLayout(act);
        gSearchRow.setOrientation(LinearLayout.HORIZONTAL);
        gSearchRow.setGravity(Gravity.CENTER_VERTICAL);

        gSearchBox = new android.widget.EditText(act);
        gSearchBox.setSingleLine(true);
        gSearchBox.setHint("搜索语音包");
        gSearchBox.setTextSize(15);
        gSearchBox.setTextColor(C_TEXT);
        gSearchBox.setHintTextColor(C_SUB);
        gSearchBox.setPadding(dp2px(act, 14), dp2px(act, 9), dp2px(act, 14), dp2px(act, 9));
        gSearchBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        gSearchBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F2F2F7"));
        bg.setCornerRadius(dp2px(act, 12));
        gSearchBox.setBackground(bg);
        gSearchBox.setText(gQuery);
        gSearchBox.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSearchKeyboard(); }
        });
        gSearchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    hideSearchKeyboard();
                    return true;
                }
                return false;
            }
        });
        gSearchBox.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(android.text.Editable s) {
                gQuery = s.toString();
                gRootLimit = 60;
                renderRootList();
            }
        });
        gSearchRow.addView(gSearchBox, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView searchBtn = new TextView(act);
        searchBtn.setText("搜索");
        searchBtn.setTextSize(14);
        searchBtn.setTextColor(C_BLUE);
        searchBtn.setPadding(dp2px(act, 12), dp2px(act, 9), dp2px(act, 2), dp2px(act, 9));
        searchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (gQuery != null && gQuery.trim().length() > 0) {
                    hideSearchKeyboard();
                    renderRootList();
                } else {
                    showSearchKeyboard();
                }
            }
        });
        gSearchRow.addView(searchBtn, new LinearLayout.LayoutParams(-2, -2));
    }
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp2px(act, 8);
    lp.bottomMargin = dp2px(act, 4);
    gContent.addView(gSearchRow, lp);
}

void showSearchKeyboard() {
    if (gSearchBox == null || gAct == null) return;
    try {
        gSearchBox.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) gAct.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(gSearchBox, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    } catch (Exception e) {}
    // 部分 QQ 版本上首次 showSoftInput 会被吞掉，延迟再试一次
    gSearchBox.postDelayed(new Runnable() {
        public void run() {
            if (gSearchBox == null || gAct == null) return;
            try {
                android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) gAct.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(gSearchBox, 0);
            } catch (Exception e) {}
        }
    }, 120);
}

void hideSearchKeyboard() {
    if (gSearchBox == null || gAct == null) return;
    try {
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) gAct.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(gSearchBox.getWindowToken(), 0);
        }
    } catch (Exception e) {}
}

void renderRootList() {
    if (gListBox == null) return;
    gListBox.removeAllViews();
    gShownCats = new ArrayList();
    gCountViews = new ArrayList();
    Context act = gAct;

    String q = gQuery == null ? "" : gQuery.trim().toLowerCase();
    for (int i = 0; i < gCatNames.size(); i++) {
        String n = (String) gCatNames.get(i);
        if (q.length() == 0 || n.toLowerCase().indexOf(q) >= 0) {
            gShownCats.add(Integer.valueOf(i));
        }
    }

    if (gShownCats.size() == 0) {
        TextView empty = new TextView(act);
        empty.setText("没有找到相关语音包");
        empty.setTextSize(13);
        empty.setTextColor(C_SUB);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp2px(act, 16), dp2px(act, 28), dp2px(act, 16), dp2px(act, 28));
        empty.setBackground(makeCardBg(act, 18));
        gListBox.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        updateDialogSize(320);
        return;
    }

    int showCats = gShownCats.size();
    if (showCats > gRootLimit) showCats = gRootLimit;

    // 与网页一致：两列卡片网格
    LinearLayout gridWrap = new LinearLayout(act);
    gridWrap.setOrientation(LinearLayout.VERTICAL);
    LinearLayout rowBox = null;
    for (int j = 0; j < showCats; j++) {
        final int catIndex = ((Integer) gShownCats.get(j)).intValue();
        if (j % 2 == 0) {
            rowBox = new LinearLayout(act);
            rowBox.setOrientation(LinearLayout.HORIZONTAL);
            gridWrap.addView(rowBox, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout cell = new LinearLayout(act);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(dp2px(act, 14), dp2px(act, 14), dp2px(act, 10), dp2px(act, 14));
        cell.setBackground(makeCardBg(act, 20));
        cell.setMinimumHeight(dp2px(act, 76));

        LinearLayout textBox = new LinearLayout(act);
        textBox.setOrientation(LinearLayout.VERTICAL);

        TextView nameView = new TextView(act);
        nameView.setText((String) gCatNames.get(catIndex));
        nameView.setTextSize(16);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        nameView.setTextColor(C_TEXT);
        nameView.setMaxLines(2);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        textBox.addView(nameView, new LinearLayout.LayoutParams(-1, -2));

        TextView countView = new TextView(act);
        int cnt = catCount(catIndex);
        countView.setText(cnt < 0 ? "" : cnt + " 条语音");
        countView.setTextSize(12);
        countView.setTextColor(C_SUB);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(-1, -2);
        countLp.topMargin = dp2px(act, 6);
        textBox.addView(countView, countLp);
        gCountViews.add(countView);

        cell.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arrow = new TextView(act);
        arrow.setText("›");
        arrow.setTextSize(22);
        arrow.setTextColor(C_CHEVRON);
        cell.addView(arrow);

        cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (gScroll != null) gRootScrollY = gScroll.getScrollY();
                gPageLimit = 80;
                gCurCat = catIndex;
                renderCategoryPage(catIndex);
                scrollTop();
            }
        });

        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, -2, 1f);
        cellLp.setMargins(dp2px(act, 5), dp2px(act, 5), dp2px(act, 5), dp2px(act, 5));
        rowBox.addView(cell, cellLp);

        if (j == showCats - 1 && showCats % 2 == 1) {
            View spacer = new View(act);
            LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 1, 1f);
            spLp.setMargins(dp2px(act, 5), 0, dp2px(act, 5), 0);
            rowBox.addView(spacer, spLp);
        }
    }

    LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
    gridLp.topMargin = dp2px(act, 2);
    gridLp.bottomMargin = dp2px(act, 10);
    gListBox.addView(gridWrap, gridLp);

    int restCats = gShownCats.size() - showCats;
    if (restCats > 0) {
        TextView more = new TextView(act);
        more.setText("加载更多（还有 " + restCats + " 类）");
        more.setTextSize(14);
        more.setTextColor(C_BLUE);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, dp2px(act, 14), 0, dp2px(act, 14));
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(-1, -2);
        moreLp.topMargin = dp2px(act, 4);
        gListBox.addView(more, moreLp);
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                gRootLimit = gRootLimit + 60;
                renderRootList();
            }
        });
    }

    updateDialogSize(280 + ((showCats + 1) / 2) * 96 + (restCats > 0 ? 46 : 0));
}

int catCount(int catIndex) {
    if (catIndex < 0 || catIndex >= gCatCounts.size()) return -1;
    Object o = gCatCounts.get(catIndex);
    return o == null ? -1 : ((Integer) o).intValue();
}

// 分帧统计：每次只扫一个目录，避免十几秒卡住 UI
void startRefreshCounts() {
    if (gRefreshing) {
        toast("正在统计语音数量…");
        return;
    }
    if (gCatDirs.size() == 0) return;
    if (gUI == null) gUI = new android.os.Handler(android.os.Looper.getMainLooper());

    gRefreshing = true;
    gRefreshPos = 0;
    gRefreshStart = System.currentTimeMillis();
    gRefreshQueue = new ArrayList();
    for (int i = 0; i < gCatDirs.size(); i++) {
        gRefreshQueue.add(Integer.valueOf(i));
    }
    if (gRefreshBar != null) {
        gRefreshBar.setMax(gRefreshQueue.size());
        gRefreshBar.setProgress(0);
        gRefreshBar.setVisibility(View.VISIBLE);
    }
    if (gRefreshText != null) {
        gRefreshText.setText("0/" + gRefreshQueue.size() + "，约 10 秒");
        gRefreshText.setVisibility(View.VISIBLE);
    }
    toast("开始统计语音数量…");

    gUI.postDelayed(new Runnable() {
        public void run() {
            if (!gRefreshing) return;
            if (gRefreshPos < gRefreshQueue.size()) {
                int idx = ((Integer) gRefreshQueue.get(gRefreshPos)).intValue();
                gRefreshPos++;
                if (idx >= 0 && idx < gCatDirs.size() && idx < gCatFiles.size() && idx < gCatCounts.size()) {
                    java.io.File dir = (java.io.File) gCatDirs.get(idx);
                    if (dir != null) {
                        List files = audioFiles(dir);
                        gCatFiles.set(idx, files);
                        gCatCounts.set(idx, Integer.valueOf(files.size()));
                        updateCountView(idx, files.size());
                    }
                }
                if (gRefreshPos % 20 == 0) saveCountsCache();
                updateRefreshProgress();
            }
            if (gRefreshPos < gRefreshQueue.size()) {
                gUI.postDelayed(this, 1);
            } else {
                gRefreshing = false;
                saveCountsCache();
                if (gRefreshBar != null) gRefreshBar.setVisibility(View.GONE);
                if (gRefreshText != null) gRefreshText.setVisibility(View.GONE);
                toast("语音数量统计完成");
            }
        }
    }, 1);
}

void updateCountView(int catIndex, int count) {
    for (int j = 0; j < gCountViews.size() && j < gShownCats.size(); j++) {
        if (((Integer) gShownCats.get(j)).intValue() == catIndex) {
            ((TextView) gCountViews.get(j)).setText(count + " 条");
            return;
        }
    }
}

void updateRefreshProgress() {
    if (gRefreshQueue == null) return;
    int total = gRefreshQueue.size();
    if (gRefreshBar != null) {
        gRefreshBar.setMax(total <= 0 ? 1 : total);
        gRefreshBar.setProgress(gRefreshPos);
    }
    if (gRefreshText != null) {
        int remain = 10;
        if (gRefreshStart > 0 && gRefreshPos > 0) {
            long elapsed = System.currentTimeMillis() - gRefreshStart;
            long per = elapsed / gRefreshPos;
            remain = (int)((total - gRefreshPos) * per / 1000);
        }
        if (remain < 0) remain = 0;
        gRefreshText.setText(gRefreshPos + "/" + total + "，约 " + remain + " 秒");
    }
}

// ===== 二级菜单 =====
void renderCategoryPage(int catIndex) {
    if (gContent == null) return;
    exitBatchMode();
    stopPreview();
    gContent.removeAllViews();
    gListBox = null;
    gShownCats = new ArrayList();
    gCountViews = new ArrayList();
    Context act = gAct;

    resetSelectionBar();
    if (gActionBar != null) gActionBar.setVisibility(View.VISIBLE);

    List files = (List) gCatFiles.get(catIndex);
    if (files == null) {
        if (gCloudMode) {
            files = new ArrayList();
        } else {
            // 数量来自缓存，第一次进入时才真正读取文件列表
            files = audioFiles((java.io.File) gCatDirs.get(catIndex));
            gCatFiles.set(catIndex, files);
            if (catIndex < gCatCounts.size()) gCatCounts.set(catIndex, Integer.valueOf(files.size()));
            saveCountsCache();
        }
    }
    String catName = (String) gCatNames.get(catIndex);
    int total = files.size();
    int show = gPageLimit;
    if (show > total) show = total;

    // 顶栏：返回 + 标题
    LinearLayout bar = new LinearLayout(act);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);

    TextView backBtn = new TextView(act);
    backBtn.setText("‹ 返回");
    backBtn.setTextSize(17);
    backBtn.setTextColor(C_BLUE);
    backBtn.setPadding(0, dp2px(act, 2), dp2px(act, 12), dp2px(act, 6));
    bar.addView(backBtn);
    backBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            renderRootPage();
        }
    });

    TextView title = new TextView(act);
    title.setText(catName);
    title.setTextSize(20);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setTextColor(C_TEXT);
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

    TextView countView = new TextView(act);
    countView.setText(total + " 条");
    countView.setTextSize(13);
    countView.setTextColor(C_SUB);
    bar.addView(countView);
    gContent.addView(bar);

    TextView hint = new TextView(act);
    hint.setText("先点选一条语音，再用下方按钮播放或发送");
    hint.setTextSize(12);
    hint.setTextColor(C_SUB);
    LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
    hintLp.topMargin = dp2px(act, 2);
    hintLp.bottomMargin = dp2px(act, 10);
    gContent.addView(hint, hintLp);

    final List tileViews = new ArrayList();
    LinearLayout row = null;
    for (int i = 0; i < show; i++) {
        if (i % 2 == 0) {
            row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            gContent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        final java.io.File voiceFile;
        final String label;
        if (gCloudMode) {
            String[] cf = (String[]) files.get(i);
            String dirName = (String) gCatDirs.get(catIndex);
            label = cf[0];
            voiceFile = cloudCacheFile(dirName, cf[1]);
            gCloudUrls.put(voiceFile.getAbsolutePath(), cloudUrl(dirName, cf[1]));
            if (cf.length > 2 && cf[2] != null && cf[2].length() > 0) {
                gCloudAlt.put(voiceFile.getAbsolutePath(), cf[2]);
            }
        } else {
            voiceFile = (java.io.File) files.get(i);
            label = displayLabel(voiceFile.getName());
        }

        TextView btn = new TextView(act);
        btn.setText(label);
        btn.setTextSize(14);
        btn.setTextColor(C_TEXT);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp2px(act, 8), dp2px(act, 8), dp2px(act, 8), dp2px(act, 8));
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setBackground(makeCardBg(act, 14));

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, dp2px(act, 52), 1f);
        btnLp.setMargins(dp2px(act, 4), dp2px(act, 4), dp2px(act, 4), dp2px(act, 4));
        row.addView(btn, btnLp);

        final int myIndex = i;
        // 单击：选中（不直接播放/发送）
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopPreview();
                gSelectedFile = voiceFile;
                applyTileSelection(gAct, tileViews, myIndex);
                showSelectionBar(voiceFile, label);
            }
        });
        tileViews.add(btn);

        if (i == show - 1 && i % 2 == 0) {
            View spacer = new View(act);
            LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(0, 1, 1f);
            spacerLp.setMargins(dp2px(act, 4), 0, dp2px(act, 4), 0);
            row.addView(spacer, spacerLp);
        }
    }

    int rest = total - show;
    if (rest > 0) {
        TextView more = new TextView(act);
        more.setText("加载更多（还有 " + rest + " 条）");
        more.setTextSize(14);
        more.setTextColor(C_BLUE);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, dp2px(act, 14), 0, dp2px(act, 14));
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(-1, -2);
        moreLp.topMargin = dp2px(act, 4);
        gContent.addView(more, moreLp);
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                gPageLimit = gPageLimit + 80;
                renderCategoryPage(gCurCat);
            }
        });
    }

    int rows = (show + 1) / 2;
    updateDialogSize(190 + rows * 60 + (rest > 0 ? 46 : 0) + 64);
}

// ===== 选中与底部操作栏 =====
void resetSelectionBar() {
    gSelectedFile = null;
    if (gSelLabel != null) {
        gSelLabel.setText("未选择语音");
        gSelLabel.setTextColor(C_SUB);
    }
    if (gPlayBtn != null) {
        gPlayBtn.setText("播放");
        gPlayBtn.setEnabled(false);
        gPlayBtn.setAlpha(0.4f);
    }
    if (gSendBtn != null) {
        gSendBtn.setEnabled(false);
        gSendBtn.setAlpha(0.4f);
    }
}

void showSelectionBar(java.io.File file) {
    showSelectionBar(file, displayLabel(file.getName()));
}

void showSelectionBar(java.io.File file, String label) {
    if (gSelLabel != null) {
        gSelLabel.setText(label);
        gSelLabel.setTextColor(C_TEXT);
    }
    if (gPlayBtn != null) {
        gPlayBtn.setText("播放");
        gPlayBtn.setEnabled(true);
        gPlayBtn.setAlpha(1f);
    }
    if (gSendBtn != null) {
        gSendBtn.setEnabled(true);
        gSendBtn.setAlpha(1f);
    }
}

void applyTileSelection(Context act, List tiles, int selectedIndex) {
    for (int k = 0; k < tiles.size(); k++) {
        TextView tv = (TextView) tiles.get(k);
        boolean sel = (k == selectedIndex);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(sel ? C_SEL_BG : C_CARD_SOFT);
        bg.setCornerRadius(dp2px(act, 14));
        bg.setStroke(1, sel ? C_BLUE : C_LINE);
        tv.setBackground(bg);
        tv.setTextColor(sel ? C_BLUE : C_TEXT);
    }
}

TextView makeActionButton(Context act, String text, boolean primary) {
    TextView b = new TextView(act);
    b.setText(text);
    b.setTextSize(14);
    b.setTypeface(Typeface.DEFAULT_BOLD);
    b.setGravity(Gravity.CENTER);
    b.setTextColor(primary ? Color.WHITE : C_BLUE);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(primary ? C_BLUE : C_SEL_BG);
    bg.setCornerRadius(dp2px(act, 13));
    b.setBackground(bg);
    return b;
}

void buildActionBar(Context act) {
    gActionBar = new LinearLayout(act);
    gActionBar.setOrientation(LinearLayout.HORIZONTAL);
    gActionBar.setGravity(Gravity.CENTER_VERTICAL);
    gActionBar.setPadding(dp2px(act, 12), dp2px(act, 9), dp2px(act, 12), dp2px(act, 9));
    gActionBar.setBackground(makeCardBg(act, 16));
    gActionBar.setVisibility(View.GONE);

    gSelLabel = new TextView(act);
    gSelLabel.setText("未选择语音");
    gSelLabel.setTextSize(13);
    gSelLabel.setTextColor(C_SUB);
    gSelLabel.setSingleLine(true);
    gSelLabel.setEllipsize(TextUtils.TruncateAt.END);
    gActionBar.addView(gSelLabel, new LinearLayout.LayoutParams(0, -2, 1f));

    gPlayBtn = makeActionButton(act, "播放", false);
    LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp2px(act, 66), dp2px(act, 38));
    playLp.leftMargin = dp2px(act, 6);
    gActionBar.addView(gPlayBtn, playLp);

    gSendBtn = makeActionButton(act, "发送", true);
    LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp2px(act, 66), dp2px(act, 38));
    sendLp.leftMargin = dp2px(act, 8);
    gActionBar.addView(gSendBtn, sendLp);

    gPlayBtn.setEnabled(false);
    gPlayBtn.setAlpha(0.4f);
    gSendBtn.setEnabled(false);
    gSendBtn.setAlpha(0.4f);

    gPlayBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            togglePlaySelected();
        }
    });
    gSendBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            if (gBatchMode) {
                if (gBatchSending) {
                    gBatchSending = false;
                    toast("已停止批量发送");
                    updateBatchUI();
                } else {
                    batchSendSelected();
                }
                return;
            }
            if (gSelectedFile == null) return;
            if (!gSelectedFile.exists() && gCloudUrls.containsKey(gSelectedFile.getAbsolutePath())) {
                downloadCloudFile(gSelectedFile);
                return;
            }
            sendItem(gSelectedFile);
        }
    });

    LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
    barLp.topMargin = dp2px(act, 8);
    barLp.bottomMargin = dp2px(act, 8);
    gRoot.addView(gActionBar, barLp);
}

// ===== 音乐多选 / 批量发送 =====
void exitBatchMode() {
    gBatchMode = false;
    gBatchSending = false;
    gMusicSel = new java.util.HashSet();
    if (gPlayBtn != null) gPlayBtn.setVisibility(View.VISIBLE);
    if (gSendBtn != null) gSendBtn.setText("发送");
}

void updateBatchUI() {
    if (gPlayBtn != null) gPlayBtn.setVisibility(gBatchMode ? View.GONE : View.VISIBLE);
    if (!gBatchMode) {
        if (gSendBtn != null) gSendBtn.setText("发送");
        resetSelectionBar();
        return;
    }
    if (gSelLabel != null) {
        if (gBatchSending) {
            gSelLabel.setText("正在发送 " + (gBatchIndex + 1) + "/" + gBatchTotal);
            gSelLabel.setTextColor(C_TEXT);
        } else if (gMusicSel.size() > 0) {
            gSelLabel.setText("已选 " + gMusicSel.size() + " 首");
            gSelLabel.setTextColor(C_TEXT);
        } else {
            gSelLabel.setText("未选择音乐");
            gSelLabel.setTextColor(C_SUB);
        }
    }
    if (gSendBtn != null) {
        boolean on = gBatchSending || gMusicSel.size() > 0;
        gSendBtn.setText(gBatchSending ? "停止" : "批量发送");
        gSendBtn.setEnabled(on);
        gSendBtn.setAlpha(on ? 1f : 0.4f);
    }
}

void applyMusicTileStyle(Context act, TextView tv, boolean sel) {
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(sel ? C_SEL_BG : C_CARD_SOFT);
    bg.setCornerRadius(dp2px(act, 14));
    bg.setStroke(1, sel ? C_BLUE : C_LINE);
    tv.setBackground(bg);
    tv.setTextColor(sel ? C_BLUE : C_TEXT);
}

TextView makeSmallPill(Context act, String text) {
    TextView b = new TextView(act);
    b.setText(text);
    b.setTextSize(13);
    b.setTextColor(C_BLUE);
    b.setGravity(Gravity.CENTER);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(C_SEL_BG);
    bg.setCornerRadius(dp2px(act, 12));
    b.setBackground(bg);
    b.setPadding(dp2px(act, 14), dp2px(act, 7), dp2px(act, 14), dp2px(act, 7));
    return b;
}

void batchSendSelected() {
    if (gMusicSel.size() == 0) return;
    List files = new ArrayList();
    java.util.Iterator it = gMusicSel.iterator();
    while (it.hasNext()) files.add(new java.io.File((String) it.next()));
    sortFiles(files);
    gBatchSending = true;
    gBatchTotal = files.size();
    gBatchIndex = 0;
    sendBatchStep(files, 0);
}

void sendBatchStep(final List files, final int index) {
    if (!gBatchSending) return;
    if (index >= files.size()) {
        gBatchSending = false;
        gBatchIndex = 0;
        toast("批量发送完成，共 " + files.size() + " 首");
        updateBatchUI();
        return;
    }
    if (gDialog == null || !gDialog.isShowing()) {
        gBatchSending = false;
        return;
    }
    gBatchIndex = index;
    updateBatchUI();
    sendItem((java.io.File) files.get(index));
    if (gUI == null) gUI = new android.os.Handler(android.os.Looper.getMainLooper());
    gUI.postDelayed(new Runnable() {
        public void run() {
            sendBatchStep(files, index + 1);
        }
    }, 1200);
}

void togglePlaySelected() {
    if (gSelectedFile == null) return;
    boolean same = gPlayer != null && gPreviewFile != null
        && gPreviewFile.getAbsolutePath().equals(gSelectedFile.getAbsolutePath());
    stopPreview();
    if (same) return;
    if (playVoice(gSelectedFile) && gPlayBtn != null) {
        gPlayBtn.setText("停止");
    }
}

// ===== 试听播放 =====
boolean playVoice(java.io.File file) {
    if (!file.exists()) {
        String curl = (String) gCloudUrls.get(file.getAbsolutePath());
        if (curl != null && curl.length() > 0) {
            return playVoiceUrl(curl, file);
        }
        toast("文件不存在");
        return false;
    }
    String n = file.getName().toLowerCase();
    boolean playable = n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac")
        || n.endsWith(".wav") || n.endsWith(".ogg");
    if (!playable) {
        toast("该格式不支持试听，可直接点发送");
        return false;
    }
    try {
        android.media.MediaPlayer mp = new android.media.MediaPlayer();
        mp.setDataSource(file.getAbsolutePath());
        mp.prepare();
        mp.start();
        gPlayer = mp;
        gPreviewFile = file;
        mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
            public void onCompletion(android.media.MediaPlayer m) {
                try { m.release(); } catch (Exception e) {}
                if (gPlayer == m) {
                    gPlayer = null;
                    gPreviewFile = null;
                }
                if (gPlayBtn != null) gPlayBtn.setText("播放");
            }
        });
        return true;
    } catch (Exception e) {
        toast("试听失败: " + e.getMessage());
        return false;
    }
}

void stopPreview() {
    if (gPlayer != null) {
        try { ((android.media.MediaPlayer) gPlayer).stop(); } catch (Exception e) {}
        try { ((android.media.MediaPlayer) gPlayer).release(); } catch (Exception e) {}
        gPlayer = null;
    }
    gPreviewFile = null;
    if (gPlayBtn != null) gPlayBtn.setText("播放");
}

// 云端试听：直接流式播放，不落地
boolean playVoiceUrl(final String url, java.io.File keyFile) {
    try {
        android.media.MediaPlayer mp = new android.media.MediaPlayer();
        mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
        mp.setDataSource(url);
        gPlayer = mp;
        gPreviewFile = keyFile;
        mp.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
            public void onPrepared(android.media.MediaPlayer m) {
                try { m.start(); } catch (Throwable t) {}
                if (gPlayBtn != null) gPlayBtn.setText("停止");
            }
        });
        mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
            public void onCompletion(android.media.MediaPlayer m) {
                try { m.release(); } catch (Exception e) {}
                if (gPlayer == m) {
                    gPlayer = null;
                    gPreviewFile = null;
                }
                if (gPlayBtn != null) gPlayBtn.setText("播放");
            }
        });
        mp.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
            public boolean onError(android.media.MediaPlayer m, int what, int extra) {
                try { m.release(); } catch (Exception e) {}
                if (gPlayer == m) {
                    gPlayer = null;
                    gPreviewFile = null;
                }
                if (gPlayBtn != null) gPlayBtn.setText("播放");
                toast("无法在线播放，请检查网络");
                return true;
            }
        });
        mp.prepareAsync();
        return true;
    } catch (Exception e) {
        toast("试听失败: " + e.getMessage());
        return false;
    }
}

// ===== 文件夹扫描（IO 线程执行） =====
String getVoiceDir() {
    return pluginPath + "/voice";
}

String getMusicDir() {
    return pluginPath + "/audio";
}

void scanVoiceDir() {
    gCatNames = new ArrayList();
    gCatFiles = new ArrayList();
    gCatDirs = new ArrayList();
    gCatCounts = new ArrayList();

    java.io.File root = new java.io.File(getVoiceDir());
    List dirs = new ArrayList();
    List rootFiles = new ArrayList();
    java.io.File[] fs = root.listFiles();
    if (fs != null) {
        for (int i = 0; i < fs.length; i++) {
            if (fs[i].isDirectory()) dirs.add(fs[i]);
            else if (isAudio(fs[i].getName())) rootFiles.add(fs[i]);
        }
    }
    sortFiles(dirs);
    sortFiles(rootFiles);

    // 打开菜单只读缓存，不扫子目录；数量可手动刷新，或进入语音包时补全
    java.util.HashMap cache = loadCountsCache();

    for (int i = 0; i < dirs.size(); i++) {
        java.io.File dir = (java.io.File) dirs.get(i);
        String name = dir.getName();
        int count = -1;

        String cv = (String) cache.get(name);
        if (cv != null) {
            int q = cv.indexOf('\t');
            if (q > 0) {
                try {
                    int c = Integer.parseInt(cv.substring(0, q));
                    long mt = Long.parseLong(cv.substring(q + 1));
                    if (mt == dir.lastModified()) count = c;
                } catch (Exception e) {}
            }
        }

        gCatNames.add(name);
        gCatDirs.add(dir);
        gCatFiles.add(null);
        gCatCounts.add(Integer.valueOf(count));
    }

    // voice 根目录下直接放的音频 → 未分类
    if (rootFiles.size() > 0) {
        gCatNames.add("未分类");
        gCatDirs.add(root);
        gCatFiles.add(rootFiles);
        gCatCounts.add(Integer.valueOf(rootFiles.size()));
    }
}

String countsCacheFile() {
    return pluginPath + "/voice_counts.cache";
}

java.util.HashMap loadCountsCache() {
    java.util.HashMap map = new java.util.HashMap();
    java.io.File f = new java.io.File(countsCacheFile());
    if (!f.exists()) return map;
    java.io.BufferedReader br = null;
    try {
        br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            int p1 = line.indexOf('\t');
            if (p1 <= 0) continue;
            map.put(line.substring(0, p1), line.substring(p1 + 1));
        }
    } catch (Exception e) {
    } finally {
        try { if (br != null) br.close(); } catch (Exception e) {}
    }
    return map;
}

void saveCountsCache() {
    if (gCloudMode) return;
    java.io.BufferedWriter bw = null;
    try {
        bw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(countsCacheFile()), "UTF-8"));
        for (int i = 0; i < gCatNames.size(); i++) {
            java.io.File dir = (java.io.File) gCatDirs.get(i);
            int count = ((Integer) gCatCounts.get(i)).intValue();
            if (dir == null || count < 0) continue;
            bw.write((String) gCatNames.get(i));
            bw.write("\t");
            bw.write(String.valueOf(count));
            bw.write("\t");
            bw.write(String.valueOf(dir.lastModified()));
            bw.newLine();
        }
    } catch (Exception e) {
    } finally {
        try { if (bw != null) bw.close(); } catch (Exception e) {}
    }
}

List audioFiles(java.io.File dir) {
    List result = new ArrayList();
    java.io.File[] fs = dir.listFiles();
    if (fs != null) {
        for (int i = 0; i < fs.length; i++) {
            // 只按扩展名判断，省掉每个文件一次 stat，扫描快很多
            if (isAudio(fs[i].getName())) {
                result.add(fs[i]);
            }
        }
    }
    sortFiles(result);
    return result;
}

boolean isAudio(String name) {
    String n = name.toLowerCase();
    return n.endsWith(".mp3") || n.endsWith(".silk") || n.endsWith(".amr")
        || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".ogg")
        || n.endsWith(".wav") || n.endsWith(".flac");
}

void sortFiles(List list) {
    java.util.Collections.sort(list, new java.util.Comparator() {
        public int compare(Object o1, Object o2) {
            String k1 = sortKey(((java.io.File) o1).getName());
            String k2 = sortKey(((java.io.File) o2).getName());
            return k1.compareToIgnoreCase(k2);
        }
    });
}

String sortKey(String name) {
    String base = name;
    int dot = base.lastIndexOf('.');
    if (dot > 0) base = base.substring(0, dot);
    int i = 0;
    while (i < base.length() && Character.isDigit(base.charAt(i))) i++;
    if (i > 0) {
        String num = base.substring(0, i);
        while (num.length() < 8) num = "0" + num;
        return num + base.substring(i);
    }
    return base;
}

String displayLabel(String fileName) {
    String base = fileName;
    int dot = base.lastIndexOf('.');
    if (dot > 0) base = base.substring(0, dot);
    base = base.replaceFirst("^[0-9]+[_-]", "");
    return base;
}

// ===== iOS 风格组件 =====
void addGrabber(Context act) {
    View bar = new View(act);
    GradientDrawable g = new GradientDrawable();
    g.setColor(Color.parseColor("#C7C7CC"));
    g.setCornerRadius(dp2px(act, 3));
    bar.setBackground(g);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp2px(act, 38), dp2px(act, 5));
    lp.gravity = Gravity.CENTER_HORIZONTAL;
    lp.topMargin = dp2px(act, 2);
    lp.bottomMargin = dp2px(act, 12);
    gRoot.addView(bar, lp);
}

void addPageTitle(Context act, String text) {
    TextView title = new TextView(act);
    title.setText(text);
    title.setTextSize(26);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setTextColor(C_TEXT);
    gContent.addView(title);
}

void addPageSubtitle(Context act, String text) {
    TextView sub = new TextView(act);
    sub.setText(text);
    sub.setTextSize(13);
    sub.setTextColor(C_SUB);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp2px(act, 3);
    lp.bottomMargin = dp2px(act, 14);
    gContent.addView(sub, lp);
}

void addDivider(Context act, LinearLayout parent) {
    View div = new View(act);
    div.setBackgroundColor(C_LINE);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
    lp.leftMargin = dp2px(act, 16);
    parent.addView(div, lp);
}

GradientDrawable makeCardBg(Context act, int radiusDp) {
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(C_CARD_SOFT);
    bg.setCornerRadius(dp2px(act, radiusDp));
    bg.setStroke(1, C_LINE);
    return bg;
}

TextView makeCircleBtn(Context act, String text, int sizeDp, boolean primary) {
    TextView tv = new TextView(act);
    tv.setText(text);
    tv.setTextSize(primary ? 17 : 16);
    tv.setGravity(Gravity.CENTER);
    tv.setTextColor(primary ? Color.WHITE : C_TEXT);
    GradientDrawable bg = new GradientDrawable();
    bg.setShape(GradientDrawable.OVAL);
    bg.setColor(primary ? C_BLUE : Color.parseColor("#1F767680"));
    tv.setBackground(bg);
    tv.setWidth(dp2px(act, sizeDp));
    tv.setHeight(dp2px(act, sizeDp));
    return tv;
}

// 根据内容估算高度，既不留大片空白，也保证超长内容可滚动
void updateDialogSize(int contentDp) {
    if (gDialog == null) return;
    Window win = gDialog.getWindow();
    if (win == null) return;
    int screenH = gAct.getResources().getDisplayMetrics().heightPixels;
    int maxH = (int)(screenH * 0.82);
    int h = dp2px(gAct, contentDp);
    if (h > maxH) h = maxH;
    if (h < dp2px(gAct, 280)) h = dp2px(gAct, 280);
    WindowManager.LayoutParams p = win.getAttributes();
    p.height = h;
    win.setAttributes(p);
}

// ===== 音乐盒：在线曲库 + 播放器 =====
String getMusicBoxDir() {
    return pluginPath + "/musicbox";
}

String fmtCacheSize(long b) {
    if (b < 1024) return b + " B";
    if (b < 1048576) return (b / 1024) + " KB";
    long t = b * 10 / 1048576;
    return (t / 10) + "." + (t % 10) + " MB";
}

long musicCacheSize() {
    long total = 0;
    try {
        java.io.File dir = new java.io.File(getMusicBoxDir());
        if (dir.exists()) {
            java.io.File[] fs = dir.listFiles();
            if (fs != null) {
                for (int i = 0; i < fs.length; i++) if (fs[i].isFile()) total += fs[i].length();
            }
        }
    } catch (Throwable t) {}
    return total;
}

String platformName() {
    if ("qq".equals(gPlatform)) return "QQ音乐";
    if ("kg".equals(gPlatform)) return "酷狗音乐";
    return "网易云音乐";
}

String musicPageDesc() {
    String lg;
    if ("qq".equals(gPlatform)) lg = qqLogged() ? "已登录" : "未登录";
    else if ("kg".equals(gPlatform)) lg = kgLogged() ? "已登录" : "未登录";
    else lg = neLogged() ? "已登录" : "未登录";
    if (gNeSearching) return platformName() + " · 正在搜索…";
    if (gSongs.size() > 0) return platformName() + " · " + gSongs.size() + " 首 · " + lg;
    return platformName() + " · " + lg;
}

void renderMusicBoxPage() {
    if (gContent == null) return;
    exitBatchMode();
    stopPreview();
    gSelectedFile = null;
    if (gActionBar != null) gActionBar.setVisibility(View.GONE);
    gContent.removeAllViews();
    gListBox = null;
    gShownCats = new ArrayList();
    gCountViews = new ArrayList();
    gMRowIcons = new ArrayList();
    gMRowNames = new ArrayList();
    Context act = gAct;

    LinearLayout bar = new LinearLayout(act);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    TextView backBtn = new TextView(act);
    backBtn.setText("‹ 返回");
    backBtn.setTextSize(17);
    backBtn.setTextColor(C_BLUE);
    backBtn.setPadding(0, dp2px(act, 2), dp2px(act, 12), dp2px(act, 2));
    backBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            renderHomePage();
        }
    });
    bar.addView(backBtn);
    gContent.addView(bar);

    addPageTitle(act, "音乐盒");
    addPageSubtitle(act, musicPageDesc());

    addPlatformRow(act);
    addNeLoginRow(act);
    addNeSearchRow(act);
    addNeCacheRow(act);

    if (gSongs.size() == 0) {
        TextView empty = new TextView(act);
        String txt = gNeSearching ? "正在搜索…" : (gNeState.length() > 0 ? gNeState : "搜索想听的歌，点一下自动下载并播放");
        empty.setText(txt);
        empty.setTextSize(13);
        empty.setTextColor(C_SUB);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp2px(act, 16), dp2px(act, 30), dp2px(act, 16), dp2px(act, 30));
        empty.setBackground(makeCardBg(act, 18));
        gContent.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        showPlayerBar();
        updateMusicState();
        updateDialogSize(440);
        return;
    }

    for (int i = 0; i < gSongs.size(); i++) {
        final int idx = i;
        final String[] s = (String[]) gSongs.get(i);

        LinearLayout item = new LinearLayout(act);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp2px(act, 12), dp2px(act, 11), dp2px(act, 12), dp2px(act, 11));
        item.setBackground(makeCardBg(act, 16));

        TextView icon = new TextView(act);
        icon.setText("▶");
        icon.setTextSize(13);
        icon.setTextColor(C_CHEVRON);
        icon.setGravity(Gravity.CENTER);
        item.addView(icon, new LinearLayout.LayoutParams(dp2px(act, 26), -2));

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(act);
        title.setText(s[0]);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(title, new LinearLayout.LayoutParams(-1, -2));

        String sub = s[1];
        if (s[4].length() > 0) sub = sub.length() > 0 ? (sub + " · " + s[4]) : s[4];
        TextView subTv = new TextView(act);
        subTv.setText(sub);
        subTv.setTextSize(11);
        subTv.setTextColor(C_SUB);
        subTv.setSingleLine(true);
        subTv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp2px(act, 2);
        mid.addView(subTv, subLp);

        item.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));

        gMRowIcons.add(icon);
        gMRowNames.add(title);

        item.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (idx == gMIndex && gMPlayer != null && gMPlayingId.equals(((String[]) gSongs.get(idx))[2])) {
                    toggleMusicPlay();
                } else {
                    playSongAt(idx);
                }
            }
        });

        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, -2);
        itemLp.bottomMargin = dp2px(act, 7);
        gContent.addView(item, itemLp);
    }

    TextView credit = new TextView(act);
    credit.setText("音乐来自网易云音乐，仅供个人试听，请支持正版");
    credit.setTextSize(10);
    credit.setTextColor(C_SUB);
    credit.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams creditLp = new LinearLayout.LayoutParams(-1, -2);
    creditLp.topMargin = dp2px(act, 4);
    creditLp.bottomMargin = dp2px(act, 6);
    gContent.addView(credit, creditLp);

    showPlayerBar();
    updateMusicState();
    updateDialogSize(210 + gSongs.size() * 62 + 60);
}

// ===== 网易云音乐 =====
String neCookiePath() {
    return pluginPath + "/netease_cookie.txt";
}

String neCookie() {
    // 优先读取会员登录保存的 Cookie（宿主 putString），旧的 cookie 文件作为回退
    String c = "";
    try { c = getLogin("163", loginUin()); } catch (Throwable t) { c = ""; }
    if (c == null || c.trim().length() == 0) c = readTextFile(neCookiePath());
    return c == null ? "" : c.trim();
}

boolean neLogged() {
    return neCookie().indexOf("MUSIC_U=") >= 0;
}

void addNeLoginRow(Context act) {
    LinearLayout row = new LinearLayout(act);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp2px(act, 12), dp2px(act, 10), dp2px(act, 12), dp2px(act, 10));
    row.setBackground(makeCardBg(act, 14));

    TextView t = new TextView(act);
    boolean lg;
    if ("qq".equals(gPlatform)) lg = qqLogged();
    else if ("kg".equals(gPlatform)) lg = kgLogged();
    else lg = neLogged();
    t.setText(lg ? platformName() + "已登录 · 点此管理会员账号" : "会员登录管理 · 网易云 / QQ音乐 / 酷狗");
    t.setTextSize(12);
    t.setTextColor(lg ? C_SUB : C_BLUE);
    row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));

    row.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            showNeLogin();
        }
    });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp2px(act, 8);
    gContent.addView(row, lp);
}

void addNeSearchRow(Context act) {
    LinearLayout row = new LinearLayout(act);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setBackground(makeCardBg(act, 14));
    row.setPadding(dp2px(act, 12), dp2px(act, 2), dp2px(act, 2), dp2px(act, 2));

    final EditText input = new EditText(act);
    input.setHint("在" + platformName() + "搜索歌曲 / 歌手");
    input.setTextSize(14);
    input.setTextColor(C_TEXT);
    input.setHintTextColor(C_SUB);
    input.setSingleLine(true);
    input.setBackground(null);
    input.setText(gNeQuery);
    row.addView(input, new LinearLayout.LayoutParams(0, dp2px(act, 42), 1f));

    TextView go = makeActionButton(act, "搜索", true);
    go.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            String kw = input.getText().toString().trim();
            if (kw.length() == 0) {
                toast("请输入歌名或歌手");
                return;
            }
            startSearch(kw);
        }
    });
    row.addView(go, new LinearLayout.LayoutParams(dp2px(act, 58), dp2px(act, 34)));

    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp2px(act, 8);
    gContent.addView(row, lp);
}

void addPlatformRow(Context act) {
    LinearLayout row = new LinearLayout(act);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setBackground(makeCardBg(act, 14));
    row.setPadding(dp2px(act, 4), dp2px(act, 4), dp2px(act, 4), dp2px(act, 4));

    LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp2px(act, 36), 1f);
    lp1.rightMargin = dp2px(act, 3);
    row.addView(platformChip(act, "网易云", "ne"), lp1);
    LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp2px(act, 36), 1f);
    lp2.rightMargin = dp2px(act, 3);
    row.addView(platformChip(act, "QQ音乐", "qq"), lp2);
    row.addView(platformChip(act, "酷狗音乐", "kg"), new LinearLayout.LayoutParams(0, dp2px(act, 36), 1f));

    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp2px(act, 8);
    gContent.addView(row, lp);
}

TextView platformChip(Context act, String label, final String p) {
    TextView tv = new TextView(act);
    tv.setText(label);
    tv.setTextSize(13);
    tv.setGravity(Gravity.CENTER);
    boolean sel = p.equals(gPlatform);
    tv.setTextColor(sel ? Color.WHITE : C_TEXT);
    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dp2px(act, 10));
    bg.setColor(sel ? C_BLUE : Color.TRANSPARENT);
    tv.setBackground(bg);
    tv.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            if (p.equals(gPlatform)) return;
            gPlatform = p;
            stopMusic();
            gSongs = new ArrayList();
            gNeState = "";
            gNeQuery = "";
            gMIndex = -1;
            renderMusicBoxPage();
        }
    });
    return tv;
}

void addNeCacheRow(Context act) {
    final long sz = musicCacheSize();
    LinearLayout row = new LinearLayout(act);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp2px(act, 12), dp2px(act, 9), dp2px(act, 12), dp2px(act, 9));
    row.setBackground(makeCardBg(act, 14));

    TextView t = new TextView(act);
    t.setText(sz > 0 ? "音乐缓存 " + fmtCacheSize(sz) + " · 点此一键清除" : "暂无音乐缓存");
    t.setTextSize(12);
    t.setTextColor(sz > 0 ? C_BLUE : C_SUB);
    row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));

    row.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            clearMusicCache();
        }
    });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp2px(act, 8);
    gContent.addView(row, lp);
}

void clearMusicCache() {
    final long size = musicCacheSize();
    if (size <= 0) {
        toast("暂无音乐缓存");
        return;
    }
    android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(gAct);
    b.setTitle("清除音乐缓存");
    b.setMessage("将删除已下载的 " + fmtCacheSize(size) + " 音乐文件（不影响账号和语音包）。");
    b.setPositiveButton("一键删除", new android.content.DialogInterface.OnClickListener() {
        public void onClick(android.content.DialogInterface d, int w) {
            stopMusic();
            int n = 0;
            try {
                java.io.File dir = new java.io.File(getMusicBoxDir());
                java.io.File[] fs = dir.listFiles();
                if (fs != null) {
                    for (int i = 0; i < fs.length; i++) {
                        if (fs[i].isFile() && fs[i].delete()) n++;
                    }
                }
            } catch (Throwable t) {}
            toast("已删除 " + n + " 个音乐缓存文件");
            renderMusicBoxPage();
        }
    });
    b.setNegativeButton("取消", null);
    b.show();
}

void showNeLogin() {
    Activity a = null;
    try { a = getNowActivity(); } catch (Throwable t) {}
    if (a == null && gAct instanceof Activity) a = (Activity) gAct;
    if (a == null) { toast("无法获取Activity"); return; }
    final Activity act = a;
    act.runOnUiThread(new Runnable() {
        public void run() {
            try { showLoginUI(act); } catch (Throwable t) { toast("打开账号管理失败: " + t.getMessage()); }
        }
    });
}

public void showLoginDialog(int chatType, String peerUin, String name) {
    showNeLogin();
}

String loginUin() {
    try {
        String u = String.valueOf(myUin);
        if (u != null && u.length() > 0 && !"null".equals(u)) return u;
    } catch (Throwable t) {}
    return "local";
}

void saveLogin(String platform, String uin, String cookie) {
    try { putString(LOGIN_CONFIG, platform + "_" + uin, cookie); return; } catch (Throwable t) {}
    writeTextFile(pluginPath + "/login_" + platform + "_" + uin + ".txt", cookie);
}

String getLogin(String platform, String uin) {
    String c = "";
    try { c = getString(LOGIN_CONFIG, platform + "_" + uin, ""); } catch (Throwable t) { c = ""; }
    if (c == null || c.length() == 0) c = readTextFile(pluginPath + "/login_" + platform + "_" + uin + ".txt");
    return c == null ? "" : c;
}

void clearLogin(String platform, String uin) {
    try { putString(LOGIN_CONFIG, platform + "_" + uin, ""); } catch (Throwable t) {}
    try {
        java.io.File f = new java.io.File(pluginPath + "/login_" + platform + "_" + uin + ".txt");
        if (f.exists()) f.delete();
    } catch (Throwable t) {}
}

boolean hasLogin(String platform, String uin) {
    String c = getLogin(platform, uin);
    return c != null && c.length() > 0;
}

String getMusicUin() {
    String cookie = getLogin("qq", loginUin());
    if (cookie == null || cookie.length() == 0) return "";
    String[] parts = cookie.split(";\\s*");
    for (int i = 0; i < parts.length; i++) {
        String p = parts[i].trim();
        if (p.startsWith("wxuin=")) return p.substring(5);
        if (p.startsWith("uin=")) return p.substring(4);
    }
    return "";
}

String getQQMusicKey() {
    String cookie = getLogin("qq", loginUin());
    if (cookie == null || cookie.length() == 0) return "";
    String[] parts = cookie.split(";\\s*");
    for (int i = 0; i < parts.length; i++) {
        String p = parts[i].trim();
        if (p.startsWith("qqmusic_key=")) return p.substring(12);
        if (p.startsWith("qm_keyst=")) return p.substring(9);
    }
    return "";
}

// ===== 会员登录管理（移植自参考插件：QQ音乐 / 网易云 双平台 + WebView 登录） =====
void showLoginUI(final Activity activity) {
    final Dialog dialog = new Dialog(activity);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    dialog.setCancelable(true);

    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp2px(activity, 20), dp2px(activity, 18), dp2px(activity, 20), dp2px(activity, 18));
    GradientDrawable rootBg = new GradientDrawable();
    rootBg.setColor(C_BG);
    rootBg.setCornerRadius(dp2px(activity, 24));
    root.setBackground(rootBg);

    TextView titleView = new TextView(activity);
    titleView.setText("会员登录");
    titleView.setTextSize(20);
    titleView.setTextColor(C_TEXT);
    titleView.setTypeface(null, Typeface.BOLD);
    titleView.setGravity(Gravity.CENTER);
    titleView.setPadding(0, dp2px(activity, 2), 0, dp2px(activity, 4));
    root.addView(titleView);

    TextView hintView = new TextView(activity);
    hintView.setText("登录音乐平台会员，解锁 HQ/SQ 无损音质\n支持自动检测登录状态并保存 Cookie");
    hintView.setTextSize(12);
    hintView.setTextColor(C_SUB);
    hintView.setGravity(Gravity.CENTER);
    hintView.setPadding(0, 0, 0, dp2px(activity, 14));
    root.addView(hintView);

    addLoginSection(activity, root, "qq", loginUin(), dialog);
    addNetEaseLoginSection(activity, root, "163", loginUin(), dialog);
    addKugouLoginSection(activity, root, "kg", loginUin(), dialog);

    TextView closeBtn = createButton(activity, "关闭", C_BLUE, Color.WHITE);
    closeBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dialog.dismiss(); } });
    root.addView(closeBtn);

    ScrollView scroll = new ScrollView(activity);
    scroll.addView(root);
    dialog.setContentView(scroll);
    dialog.show();
    Window window = dialog.getWindow();
    if (window != null) {
        window.setLayout((int)(activity.getResources().getDisplayMetrics().widthPixels * 0.92), ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
    fadeIn.setDuration(300);
    fadeIn.setInterpolator(new DecelerateInterpolator());
    root.startAnimation(fadeIn);
}

void addLoginSection(final Activity activity, final LinearLayout parent, final String platform, final String uin, final Dialog dialog) {
    boolean hasCookie = hasLogin(platform, uin);
    final String loginUrl = "https://y.qq.com/n/ryqq_v2/login";

    final LinearLayout section = new LinearLayout(activity);
    section.setOrientation(LinearLayout.VERTICAL);
    section.setPadding(dp2px(activity, 14), dp2px(activity, 12), dp2px(activity, 14), dp2px(activity, 12));
    LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    sectionParams.setMargins(0, 0, 0, dp2px(activity, 10));
    section.setLayoutParams(sectionParams);
    section.setBackground(makeCardBg(activity, 18));

    LinearLayout headerRow = new LinearLayout(activity);
    headerRow.setOrientation(LinearLayout.HORIZONTAL);
    headerRow.setGravity(Gravity.CENTER_VERTICAL);

    TextView iconLabel = new TextView(activity);
    iconLabel.setText("♪");
    iconLabel.setTextSize(18);
    iconLabel.setTextColor(Color.parseColor("#12B7F5"));
    headerRow.addView(iconLabel);

    final LinearLayout nameWrap = new LinearLayout(activity);
    nameWrap.setOrientation(LinearLayout.VERTICAL);
    nameWrap.setPadding(dp2px(activity, 8), 0, 0, 0);

    TextView nameView = new TextView(activity);
    nameView.setText("QQ音乐");
    nameView.setTextSize(15);
    nameView.setTextColor(C_TEXT);
    nameView.setTypeface(null, Typeface.BOLD);
    nameWrap.addView(nameView);

    final TextView descView = new TextView(activity);
    descView.setText(hasCookie ? "检测中..." : "未登录，部分歌曲可能无法播放");
    descView.setTextSize(11);
    descView.setTextColor(C_SUB);
    nameWrap.addView(descView);
    headerRow.addView(nameWrap, new LinearLayout.LayoutParams(0, -2, 1f));

    final TextView statusBadge = new TextView(activity);
    statusBadge.setText(hasCookie ? "检测中..." : "FREE");
    statusBadge.setTextSize(11);
    statusBadge.setTextColor(hasCookie ? Color.WHITE : Color.WHITE);
    statusBadge.setPadding(dp2px(activity, 8), dp2px(activity, 3), dp2px(activity, 8), dp2px(activity, 3));
    final GradientDrawable badgeBg = new GradientDrawable();
    badgeBg.setCornerRadius(dp2px(activity, 20));
    badgeBg.setColor(hasCookie ? Color.parseColor("#34C759") : Color.parseColor("#FF9F0A"));
    statusBadge.setBackground(badgeBg);
    headerRow.addView(statusBadge);
    section.addView(headerRow);

    final LinearLayout btnContainer = new LinearLayout(activity);
    btnContainer.setOrientation(LinearLayout.VERTICAL);
    section.addView(btnContainer);
    parent.addView(section);

    if (hasCookie) {
        // 已保存过 Cookie：只展示状态，不做自动校验/清除，避免登录状态被反复踢掉
        descView.setText("已保存登录 Cookie");
        statusBadge.setText("已登录");
        badgeBg.setColor(Color.parseColor("#34C759"));
        statusBadge.setBackground(badgeBg);
        TextView logoutBtn = createButton(activity, "退出登录", Color.parseColor("#FF3B30"), Color.WHITE);
        logoutBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearLogin(platform, uin); toast("已退出QQ音乐登录"); dialog.dismiss(); }
        });
        btnContainer.addView(logoutBtn);
        TextView againBtn = createButton(activity, "重新登录QQ音乐", C_BLUE, Color.WHITE);
        againBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dialog.dismiss(); startWebLogin(activity, platform, loginUrl); }
        });
        btnContainer.addView(againBtn);
    } else {
        TextView loginBtn = createButton(activity, "一键登录QQ音乐", C_BLUE, Color.WHITE);
        loginBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dialog.dismiss(); startWebLogin(activity, platform, loginUrl); }
        });
        btnContainer.addView(loginBtn);
    }
}

void addNetEaseLoginSection(final Activity activity, final LinearLayout parent, final String platform, final String uin, final Dialog dialog) {
    boolean hasCookie = hasLogin(platform, uin);
    final String loginUrl = "https://music.163.com/#/login";

    final LinearLayout section = new LinearLayout(activity);
    section.setOrientation(LinearLayout.VERTICAL);
    section.setPadding(dp2px(activity, 14), dp2px(activity, 12), dp2px(activity, 14), dp2px(activity, 12));
    LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    sectionParams.setMargins(0, 0, 0, dp2px(activity, 10));
    section.setLayoutParams(sectionParams);
    section.setBackground(makeCardBg(activity, 18));

    LinearLayout headerRow = new LinearLayout(activity);
    headerRow.setOrientation(LinearLayout.HORIZONTAL);
    headerRow.setGravity(Gravity.CENTER_VERTICAL);

    TextView iconLabel = new TextView(activity);
    iconLabel.setText("♪");
    iconLabel.setTextSize(18);
    iconLabel.setTextColor(Color.parseColor("#E60026"));
    headerRow.addView(iconLabel);

    final LinearLayout nameWrap = new LinearLayout(activity);
    nameWrap.setOrientation(LinearLayout.VERTICAL);
    nameWrap.setPadding(dp2px(activity, 8), 0, 0, 0);

    TextView nameView = new TextView(activity);
    nameView.setText("网易云音乐");
    nameView.setTextSize(15);
    nameView.setTextColor(C_TEXT);
    nameView.setTypeface(null, Typeface.BOLD);
    nameWrap.addView(nameView);

    final TextView descView = new TextView(activity);
    descView.setText(hasCookie ? "检测中..." : "未登录，部分歌曲可能无法播放");
    descView.setTextSize(11);
    descView.setTextColor(C_SUB);
    nameWrap.addView(descView);
    headerRow.addView(nameWrap, new LinearLayout.LayoutParams(0, -2, 1f));

    final TextView statusBadge = new TextView(activity);
    statusBadge.setText(hasCookie ? "检测中..." : "FREE");
    statusBadge.setTextSize(11);
    statusBadge.setTextColor(Color.WHITE);
    statusBadge.setPadding(dp2px(activity, 8), dp2px(activity, 3), dp2px(activity, 8), dp2px(activity, 3));
    final GradientDrawable badgeBg = new GradientDrawable();
    badgeBg.setCornerRadius(dp2px(activity, 20));
    badgeBg.setColor(hasCookie ? Color.parseColor("#34C759") : Color.parseColor("#FF9F0A"));
    statusBadge.setBackground(badgeBg);
    headerRow.addView(statusBadge);
    section.addView(headerRow);

    final LinearLayout btnContainer = new LinearLayout(activity);
    btnContainer.setOrientation(LinearLayout.VERTICAL);
    section.addView(btnContainer);
    parent.addView(section);

    if (hasCookie) {
        // 已保存过 Cookie：只展示状态，不做自动校验/清除，避免登录状态被反复踢掉
        descView.setText("已保存登录 Cookie");
        statusBadge.setText("已登录");
        badgeBg.setColor(Color.parseColor("#34C759"));
        statusBadge.setBackground(badgeBg);
        TextView logoutBtn = createButton(activity, "退出登录", Color.parseColor("#FF3B30"), Color.WHITE);
        logoutBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearLogin(platform, uin); toast("已退出网易云音乐登录"); dialog.dismiss(); }
        });
        btnContainer.addView(logoutBtn);
        TextView againBtn = createButton(activity, "重新登录网易云音乐", C_BLUE, Color.WHITE);
        againBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dialog.dismiss(); startWebLogin(activity, platform, loginUrl); }
        });
        btnContainer.addView(againBtn);
    } else {
        TextView loginBtn = createButton(activity, "一键登录网易云音乐", C_BLUE, Color.WHITE);
        loginBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dialog.dismiss(); startWebLogin(activity, platform, loginUrl); }
        });
        btnContainer.addView(loginBtn);
    }
}

void addKugouLoginSection(final Activity activity, final LinearLayout parent, final String platform, final String uin, final Dialog dialog) {
    boolean hasCookie = hasLogin(platform, uin);
    final String loginUrl = "https://www.kugou.com/";

    final LinearLayout section = new LinearLayout(activity);
    section.setOrientation(LinearLayout.VERTICAL);
    section.setPadding(dp2px(activity, 14), dp2px(activity, 12), dp2px(activity, 14), dp2px(activity, 12));
    LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    sectionParams.setMargins(0, 0, 0, dp2px(activity, 10));
    section.setLayoutParams(sectionParams);
    section.setBackground(makeCardBg(activity, 18));

    LinearLayout headerRow = new LinearLayout(activity);
    headerRow.setOrientation(LinearLayout.HORIZONTAL);
    headerRow.setGravity(Gravity.CENTER_VERTICAL);

    TextView iconLabel = new TextView(activity);
    iconLabel.setText("♪");
    iconLabel.setTextSize(18);
    iconLabel.setTextColor(Color.parseColor("#2CA2F0"));
    headerRow.addView(iconLabel);

    final LinearLayout nameWrap = new LinearLayout(activity);
    nameWrap.setOrientation(LinearLayout.VERTICAL);
    nameWrap.setPadding(dp2px(activity, 8), 0, 0, 0);

    TextView nameView = new TextView(activity);
    nameView.setText("酷狗音乐");
    nameView.setTextSize(15);
    nameView.setTextColor(C_TEXT);
    nameView.setTypeface(null, Typeface.BOLD);
    nameWrap.addView(nameView);

    final TextView descView = new TextView(activity);
    descView.setText(hasCookie ? "检测中..." : "未登录，部分歌曲可能无法播放");
    descView.setTextSize(11);
    descView.setTextColor(C_SUB);
    nameWrap.addView(descView);
    headerRow.addView(nameWrap, new LinearLayout.LayoutParams(0, -2, 1f));

    final TextView statusBadge = new TextView(activity);
    statusBadge.setText(hasCookie ? "检测中..." : "FREE");
    statusBadge.setTextSize(11);
    statusBadge.setTextColor(Color.WHITE);
    statusBadge.setPadding(dp2px(activity, 8), dp2px(activity, 3), dp2px(activity, 8), dp2px(activity, 3));
    final GradientDrawable badgeBg = new GradientDrawable();
    badgeBg.setCornerRadius(dp2px(activity, 20));
    badgeBg.setColor(hasCookie ? Color.parseColor("#34C759") : Color.parseColor("#FF9F0A"));
    statusBadge.setBackground(badgeBg);
    headerRow.addView(statusBadge);
    section.addView(headerRow);

    final LinearLayout btnContainer = new LinearLayout(activity);
    btnContainer.setOrientation(LinearLayout.VERTICAL);
    section.addView(btnContainer);
    parent.addView(section);

    if (hasCookie) {
        // 已保存过 Cookie：只展示状态，不做自动校验/清除，避免登录状态被反复踢掉
        descView.setText("已保存登录 Cookie");
        statusBadge.setText("已登录");
        badgeBg.setColor(Color.parseColor("#34C759"));
        statusBadge.setBackground(badgeBg);
        TextView logoutBtn = createButton(activity, "退出登录", Color.parseColor("#FF3B30"), Color.WHITE);
        logoutBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearLogin(platform, uin); toast("已退出酷狗音乐登录"); dialog.dismiss(); }
        });
        btnContainer.addView(logoutBtn);
        TextView againBtn = createButton(activity, "重新登录酷狗音乐", C_BLUE, Color.WHITE);
        againBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dialog.dismiss(); startWebLogin(activity, platform, loginUrl); }
        });
        btnContainer.addView(againBtn);
    } else {
        TextView loginBtn = createButton(activity, "一键登录酷狗音乐", C_BLUE, Color.WHITE);
        loginBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dialog.dismiss(); startWebLogin(activity, platform, loginUrl); }
        });
        btnContainer.addView(loginBtn);
    }
}

void startWebLogin(final Activity activity, String platform, String loginUrl) {
    webLoginPlatform = platform;
    if ("163".equals(platform)) {
        webLoginPlatformName = "网易云音乐";
    } else if ("kg".equals(platform)) {
        webLoginPlatformName = "酷狗音乐";
    } else {
        webLoginPlatformName = "QQ音乐";
    }

    if (webLoginWebView != null) { try { webLoginWebView.stopLoading(); webLoginWebView.loadUrl("about:blank"); } catch (Throwable e) {} }
    if (webLoginDialog != null) { try { webLoginDialog.dismiss(); } catch (Throwable e) {} }

    webLoginDialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
    webLoginDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    webLoginDialog.setCancelable(true);

    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(C_BG);

    LinearLayout header = new LinearLayout(activity);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setPadding(dp2px(activity, 16), dp2px(activity, 14), dp2px(activity, 16), dp2px(activity, 8));
    header.setGravity(Gravity.CENTER_VERTICAL);

    TextView iconView = new TextView(activity);
    iconView.setText("♪");
    iconView.setTextSize(22);
    iconView.setTextColor(C_BLUE);
    header.addView(iconView);

    LinearLayout titleWrap = new LinearLayout(activity);
    titleWrap.setOrientation(LinearLayout.VERTICAL);
    titleWrap.setPadding(dp2px(activity, 8), 0, 0, 0);

    TextView titleView = new TextView(activity);
    titleView.setText(webLoginPlatformName + " 会员登录");
    titleView.setTextSize(17);
    titleView.setTextColor(C_TEXT);
    titleView.setTypeface(null, Typeface.BOLD);
    titleWrap.addView(titleView);

    TextView subTitleView = new TextView(activity);
    subTitleView.setText("登录后可解锁 HQ/SQ 无损音质");
    subTitleView.setTextSize(11);
    subTitleView.setTextColor(C_SUB);
    titleWrap.addView(subTitleView);
    header.addView(titleWrap, new LinearLayout.LayoutParams(0, -2, 1f));

    TextView closeBtn = new TextView(activity);
    closeBtn.setText("X");
    closeBtn.setTextSize(18);
    closeBtn.setTextColor(C_SUB);
    closeBtn.setPadding(dp2px(activity, 10), dp2px(activity, 6), dp2px(activity, 10), dp2px(activity, 6));
    closeBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            if (webLoginDialog != null) { webLoginDialog.dismiss(); webLoginDialog = null; }
            if (webLoginWebView != null) { webLoginWebView.stopLoading(); webLoginWebView.loadUrl("about:blank"); webLoginWebView = null; }
        }
    });
    header.addView(closeBtn);
    root.addView(header);

    LinearLayout statusBar = new LinearLayout(activity);
    statusBar.setOrientation(LinearLayout.HORIZONTAL);
    statusBar.setPadding(dp2px(activity, 16), dp2px(activity, 4), dp2px(activity, 16), dp2px(activity, 8));
    statusBar.setGravity(Gravity.CENTER_VERTICAL);

    View dot = new View(activity);
    dot.setLayoutParams(new LinearLayout.LayoutParams(dp2px(activity, 8), dp2px(activity, 8)));
    GradientDrawable dotBg = new GradientDrawable();
    dotBg.setShape(GradientDrawable.OVAL);
    dotBg.setColor(Color.parseColor("#FF9F0A"));
    dot.setBackground(dotBg);
    statusBar.addView(dot);

    final TextView statusText = new TextView(activity);
    statusText.setText("  等待登录...");
    statusText.setTextSize(12);
    statusText.setTextColor(Color.parseColor("#FF9F0A"));
    statusBar.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1f));

    // 手动确认登录按钮（备用）
    final TextView manualBtn = new TextView(activity);
    manualBtn.setText("我已登录");
    manualBtn.setTextSize(12);
    manualBtn.setTextColor(Color.WHITE);
    manualBtn.setPadding(dp2px(activity, 12), dp2px(activity, 5), dp2px(activity, 12), dp2px(activity, 5));
    GradientDrawable manualBg = new GradientDrawable();
    manualBg.setCornerRadius(dp2px(activity, 20));
    manualBg.setColor(C_BLUE);
    manualBtn.setBackground(manualBg);
    manualBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            if (webLoginWebView == null) return;
            String url = webLoginWebView.getUrl();
            if (url == null || url.length() == 0) url = "https://www.kugou.com";
            String cookie = CookieManager.getInstance().getCookie(url);
            boolean ok = cookie != null && cookie.length() > 0;
            if (ok && "163".equals(webLoginPlatform) && !cookie.contains("MUSIC_U=")) ok = false;
            if (ok && "qq".equals(webLoginPlatform) && !cookie.contains("qqmusic_key=") && !cookie.contains("qm_keyst=")) ok = false;
            if (ok && "kg".equals(webLoginPlatform) && !isKgCookie(cookie)) ok = false;
            if (ok) {
                saveLogin(webLoginPlatform, loginUin(), cookie);
                statusText.setText("  已手动保存Cookie");
                statusText.setTextColor(Color.parseColor("#34C759"));
                toast(webLoginPlatformName + "登录成功！");
                if (webLoginDialog != null) { webLoginDialog.dismiss(); webLoginDialog = null; }
                if (webLoginWebView != null) { webLoginWebView.stopLoading(); webLoginWebView.loadUrl("about:blank"); webLoginWebView = null; }
            } else {
                toast("未检测到Cookie，请先在网页中完成登录");
            }
        }
    });
    statusBar.addView(manualBtn);
    root.addView(statusBar);

    webLoginWebView = new WebView(activity);
    webLoginWebView.getSettings().setJavaScriptEnabled(true);
    webLoginWebView.getSettings().setDomStorageEnabled(true);
    webLoginWebView.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(webLoginWebView, true);
    webLoginWebView.setWebViewClient(new WebViewClient() {
        public void onPageFinished(WebView view, String url) {
            // 只在非登录/授权页面检测 cookie，避免打开登录页时误判
            if (url.contains("login") || url.contains("authorize") || url.contains("oauth") || url.contains("passport") || url.contains("acct")) {
                return;
            }
            if (!url.contains("y.qq.com") && !url.contains("music.163.com") && !url.contains("kugou.com")) {
                return;
            }
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && cookie.length() > 0) {
                boolean ok = false;
                if ("163".equals(webLoginPlatform)) {
                    if (cookie.contains("MUSIC_U=")) ok = true;
                } else if ("kg".equals(webLoginPlatform)) {
                    if (isKgCookie(cookie)) ok = true;
                } else {
                    if (cookie.contains("qqmusic_key=") || cookie.contains("qm_keyst=")) ok = true;
                }
                if (ok) {
                    saveLogin(webLoginPlatform, loginUin(), cookie);
                    statusText.setText("  登录成功，正在保存...");
                    statusText.setTextColor(Color.parseColor("#34C759"));
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                        public void run() {
                            toast(webLoginPlatformName + "登录成功！");
                            if (webLoginDialog != null) { webLoginDialog.dismiss(); webLoginDialog = null; }
                            if (webLoginWebView != null) { webLoginWebView.stopLoading(); webLoginWebView.loadUrl("about:blank"); webLoginWebView = null; }
                        }
                    }, 800);
                }
            }
        }
    });
    LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    webParams.setMargins(dp2px(activity, 10), 0, dp2px(activity, 10), dp2px(activity, 12));
    webLoginWebView.setLayoutParams(webParams);
    root.addView(webLoginWebView);

    webLoginDialog.setContentView(root);
    Window window = webLoginDialog.getWindow();
    if (window != null) {
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }
    webLoginDialog.show();
    webLoginWebView.loadUrl(loginUrl);
}

TextView createButton(Activity activity, String text, int bgColor, int textColor) {
    TextView btn = new TextView(activity);
    btn.setText(text);
    btn.setTextSize(14);
    btn.setTextColor(textColor);
    btn.setGravity(Gravity.CENTER);
    btn.setTypeface(null, Typeface.BOLD);
    btn.setPadding(0, dp2px(activity, 11), 0, dp2px(activity, 11));
    GradientDrawable btnBg = new GradientDrawable();
    btnBg.setColor(bgColor);
    btnBg.setCornerRadius(dp2px(activity, 16));
    btn.setBackground(btnBg);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.setMargins(0, dp2px(activity, 8), 0, 0);
    btn.setLayoutParams(params);
    return btn;
}

String getCsrfToken(String cookie) {
    if (cookie == null || cookie.length() == 0) return "";
    String[] parts = cookie.split(";");
    for (int i = 0; i < parts.length; i++) {
        String trimmed = parts[i].trim();
        if (trimmed.startsWith("__csrf=")) return trimmed.substring(7).trim();
    }
    return "";
}

String httpGetWithReferer(String urlStr, String cookie, String referer) {
    java.net.HttpURLConnection conn = null;
    java.io.BufferedReader reader = null;
    try {
        conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        if (referer != null && referer.length() > 0) conn.setRequestProperty("Referer", referer);
        if (cookie != null && cookie.length() > 0) conn.setRequestProperty("Cookie", cookie);
        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } else if (responseCode == 302 || responseCode == 301 || responseCode == 303 || responseCode == 307 || responseCode == 308) {
            String location = conn.getHeaderField("Location");
            if (location != null && location.length() > 0) return location;
        } else {
            log("error.txt", "httpGetWithReferer: HTTP状态码=" + responseCode + ", url=" + urlStr);
        }
    } catch (Throwable e) {
        log("error.txt", "httpGetWithReferer: 异常=" + e.toString() + ", url=" + urlStr);
    } finally {
        try { if (reader != null) reader.close(); } catch (Throwable e) {}
        if (conn != null) conn.disconnect();
    }
    return "";
}

String httpPostJsonWithCookie(String urlStr, String body, String cookie) {
    java.net.HttpURLConnection conn = null;
    java.io.OutputStream os = null;
    java.io.BufferedReader reader = null;
    try {
        conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setRequestProperty("Referer", "https://y.qq.com/");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        if (cookie != null && cookie.length() > 0) conn.setRequestProperty("Cookie", cookie);
        os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.flush();
        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } else {
            log("error.txt", "httpPostJson: HTTP状态码=" + responseCode + ", url=" + urlStr);
        }
    } catch (Throwable e) {
        log("error.txt", "httpPostJson: 异常=" + e.toString() + ", url=" + urlStr);
    } finally {
        try { if (os != null) os.close(); } catch (Throwable e) {}
        try { if (reader != null) reader.close(); } catch (Throwable e) {}
        if (conn != null) conn.disconnect();
    }
    return "";
}

String httpGetNe(String url) throws Exception {
    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
    conn.setConnectTimeout(10000);
    conn.setReadTimeout(20000);
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
    conn.setRequestProperty("Referer", "https://music.163.com/");
    String ck = neCookie();
    conn.setRequestProperty("Cookie", ck.length() > 0 ? ("appver=2.0.2; " + ck) : "appver=2.0.2");
    conn.setInstanceFollowRedirects(true);
    int code = conn.getResponseCode();
    if (code != 200) {
        try { conn.disconnect(); } catch (Exception e) {}
        throw new Exception("HTTP " + code);
    }
    java.io.InputStream in = conn.getInputStream();
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int len;
    while ((len = in.read(buf)) > 0) bos.write(buf, 0, len);
    try { in.close(); } catch (Exception e) {}
    try { conn.disconnect(); } catch (Exception e) {}
    return new String(bos.toByteArray(), "UTF-8");
}

String fmtMs(Object v) {
    long ms = 0;
    try { ms = Long.parseLong(String.valueOf(v)); } catch (Throwable t) {}
    long sec = ms / 1000;
    long m = sec / 60;
    long s2 = sec % 60;
    return m + ":" + (s2 < 10 ? "0" : "") + s2;
}

String qqCookie() {
    try { return getLogin("qq", loginUin()); } catch (Throwable t) { return ""; }
}

boolean qqLogged() {
    String c = qqCookie();
    return c != null && c.length() > 0;
}

String musicUinNum() {
    String u = "";
    try { u = getMusicUin(); } catch (Throwable t) { u = ""; }
    if (u == null) u = "";
    u = u.trim();
    while (u.length() > 0 && !Character.isDigit(u.charAt(0))) u = u.substring(1);
    return u.length() == 0 ? "0" : u;
}

String fmtSec(Object v) {
    long sec = 0;
    try { sec = Long.parseLong(String.valueOf(v)); } catch (Throwable t) {}
    long m = sec / 60;
    long s2 = sec % 60;
    return m + ":" + (s2 < 10 ? "0" : "") + s2;
}

void startSearch(String kw) {
    if ("qq".equals(gPlatform)) startQqSearch(kw);
    else if ("kg".equals(gPlatform)) startKugouSearch(kw);
    else startNeSearch(kw);
}

void startQqSearch(final String kw) {
    if (gNeSearching) return;
    gNeSearching = true;
    gNeQuery = kw;
    gNeState = "";
    gSongs = new ArrayList();
    renderMusicBoxPage();

    new Thread(new Runnable() {
        public void run() {
            List list = new ArrayList();
            String err = "";
            try {
                String u = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?w=" + enc(kw)
                        + "&format=json&n=30&p=1&remoteplace=txt.yqq.center&t=0&aggr=1&cr=1&catZhida=1&lossless=0&flag_qc=0";
                String raw = httpGetWithReferer(u, qqCookie(), "https://y.qq.com/");
                int a = raw == null ? -1 : raw.indexOf('{');
                int b = raw == null ? -1 : raw.lastIndexOf('}');
                if (a >= 0 && b > a) raw = raw.substring(a, b + 1);
                if (raw != null && raw.length() > 0) {
                    org.json.JSONObject root = new org.json.JSONObject(raw);
                    org.json.JSONObject d = root.optJSONObject("data");
                    org.json.JSONObject sg = d == null ? null : d.optJSONObject("song");
                    org.json.JSONArray arr = sg == null ? null : sg.optJSONArray("list");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject o = arr.getJSONObject(i);
                            String mid = o.optString("songmid", "");
                            if (mid.length() == 0) continue;
                            String nm = o.optString("songname", "");
                            String ar = "";
                            org.json.JSONArray as = o.optJSONArray("singer");
                            if (as != null) {
                                for (int k = 0; k < as.length(); k++) {
                                    if (k > 0) ar = ar + "/";
                                    ar = ar + as.getJSONObject(k).optString("name", "");
                                }
                            }
                            String dur = fmtSec(o.opt("interval"));
                            list.add(new String[]{ nm, ar, mid, "", dur });
                        }
                    }
                }
                if (list.size() == 0) err = "没有找到相关歌曲";
            } catch (Throwable t) {
                err = "搜索失败，请检查网络";
            }
            final List fl = list;
            final String fe = err;
            postUi(new Runnable() {
                public void run() {
                    gNeSearching = false;
                    gSongs = fl;
                    gNeState = fe;
                    gMIndex = -1;
                    if (gMPlayingId != null && gMPlayingId.length() > 0) {
                        for (int i = 0; i < gSongs.size(); i++) {
                            if (gMPlayingId.equals(((String[]) gSongs.get(i))[2])) { gMIndex = i; break; }
                        }
                    }
                    renderMusicBoxPage();
                }
            });
        }
    }).start();
}

String qqPlayUrl(String mid) {
    if (mid == null || mid.length() == 0) return "";
    try {
        String uin = musicUinNum();
        String fn = "M500" + mid + mid + ".mp3";
        String body = "{\"req_0\":{\"module\":\"vkey.GetVkeyServer\",\"method\":\"CgiGetVkey\",\"param\":{\"guid\":\"3982823384\",\"songmid\":[\"" + mid + "\"],\"songtype\":[0],\"uin\":\"" + uin + "\",\"loginflag\":1,\"platform\":\"20\",\"filename\":[\"" + fn + "\"]}},\"comm\":{\"uin\":" + uin + ",\"format\":\"json\",\"ct\":24,\"cv\":0}}";
        String u = "https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=" + enc(body);
        String raw = httpGetWithReferer(u, qqCookie(), "https://y.qq.com/");
        if (raw == null || raw.length() == 0) return "";
        org.json.JSONObject root = new org.json.JSONObject(raw);
        org.json.JSONObject r0 = root.optJSONObject("req_0");
        org.json.JSONObject rd = r0 == null ? null : r0.optJSONObject("data");
        if (rd == null) return "";
        String sip = "";
        org.json.JSONArray sips = rd.optJSONArray("sip");
        if (sips != null && sips.length() > 0) sip = sips.getString(0);
        org.json.JSONArray arr = rd.optJSONArray("midurlinfo");
        if (arr == null || arr.length() == 0) return "";
        String purl = arr.getJSONObject(0).optString("purl", "");
        if (purl.length() == 0) return "";
        if (purl.startsWith("http")) return purl;
        return sip + purl;
    } catch (Throwable t) {
        log("error.txt", "qqPlayUrl失败=" + t.toString());
    }
    return "";
}

String kgCookie() {
    try { return getLogin("kg", loginUin()); } catch (Throwable t) { return ""; }
}

boolean kgLogged() {
    String c = kgCookie();
    return c != null && c.length() > 0;
}

boolean isKgCookie(String cookie) {
    if (cookie == null || cookie.length() == 0) return false;
    return cookie.indexOf("userid=") >= 0 || cookie.indexOf("KuGoo=") >= 0;
}

String kgCookieValue(String cookie, String key) {
    if (cookie == null || cookie.length() == 0) return "";
    String[] parts = cookie.split(";");
    for (int i = 0; i < parts.length; i++) {
        String p = parts[i].trim();
        if (p.startsWith(key + "=")) return p.substring(key.length() + 1).trim();
    }
    return "";
}

String md5Hex(String s) {
    try {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] b = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            String h = Integer.toHexString(b[i] & 0xff);
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    } catch (Throwable t) {
        return "";
    }
}

String stripTags(String s) {
    if (s == null) return "";
    return s.replaceAll("<[^>]+>", "");
}

String kgPlayUrl(String hash) {
    if (hash == null || hash.length() == 0) return "";
    String cookie = kgCookie();
    String mid = kgCookieValue(cookie, "kg_mid");
    String dfid = kgCookieValue(cookie, "kg_dfid");
    if (mid.length() == 0) mid = md5Hex("voicebox" + hash);
    if (dfid.length() == 0) dfid = "-";
    String ck = cookie == null ? "" : cookie;
    if (ck.indexOf("kg_mid=") < 0) ck = (ck.length() > 0 ? ck + "; " : "") + "kg_mid=" + mid;
    if (ck.indexOf("kg_dfid=") < 0) ck = ck + "; kg_dfid=" + dfid;
    try {
        String u = "https://wwwapi.kugou.com/yy/index.php?r=play/getdata&hash=" + hash
                + "&album_id=&dfid=" + enc(dfid) + "&mid=" + mid + "&platid=4";
        String raw = httpGetWithReferer(u, ck, "https://www.kugou.com/");
        if (raw != null && raw.length() > 0) {
            org.json.JSONObject root = new org.json.JSONObject(raw);
            org.json.JSONObject d = root.optJSONObject("data");
            if (d != null) {
                String p = d.optString("play_url", "");
                if (p.length() == 0) p = d.optString("url", "");
                if (p.length() > 0 && p.startsWith("http")) return p;
            }
        }
    } catch (Throwable t) {
        log("error.txt", "kgPlayUrl主接口失败=" + t.toString());
    }
    try {
        String u = "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=" + hash;
        String raw = httpGetWithReferer(u, ck, "https://m.kugou.com/");
        if (raw != null && raw.length() > 0) {
            org.json.JSONObject root = new org.json.JSONObject(raw);
            String p = root.optString("url", "");
            if (p.length() > 0 && p.startsWith("http")) return p;
        }
    } catch (Throwable t) {
        log("error.txt", "kgPlayUrl备用接口失败=" + t.toString());
    }
    return "";
}

void startKugouSearch(final String kw) {
    if (gNeSearching) return;
    gNeSearching = true;
    gNeQuery = kw;
    gNeState = "";
    gSongs = new ArrayList();
    renderMusicBoxPage();

    new Thread(new Runnable() {
        public void run() {
            List list = new ArrayList();
            String err = "";
            try {
                String u = "https://songsearch.kugou.com/song_search_v2?keyword=" + enc(kw)
                        + "&page=1&pagesize=30&platform=WebFilter&userid=0&clientver=2000&filter=2&iscorrection=1&privilege_filter=0";
                String raw = httpGetWithReferer(u, kgCookie(), "https://www.kugou.com/");
                if (raw != null && raw.length() > 0) {
                    org.json.JSONObject root = new org.json.JSONObject(raw);
                    org.json.JSONObject d = root.optJSONObject("data");
                    org.json.JSONArray arr = d == null ? null : d.optJSONArray("lists");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject o = arr.getJSONObject(i);
                            String hash = o.optString("FileHash", "");
                            if (hash.length() == 0) continue;
                            String nm = stripTags(o.optString("SongName", ""));
                            String ar = stripTags(o.optString("SingerName", ""));
                            String dur = fmtSec(o.opt("Duration"));
                            list.add(new String[]{ nm, ar, hash, "", dur });
                        }
                    }
                }
                if (list.size() == 0) err = "没有找到相关歌曲";
            } catch (Throwable t) {
                err = "搜索失败，请检查网络";
            }
            final List fl = list;
            final String fe = err;
            postUi(new Runnable() {
                public void run() {
                    gNeSearching = false;
                    gSongs = fl;
                    gNeState = fe;
                    gMIndex = -1;
                    if (gMPlayingId != null && gMPlayingId.length() > 0) {
                        for (int i = 0; i < gSongs.size(); i++) {
                            if (gMPlayingId.equals(((String[]) gSongs.get(i))[2])) { gMIndex = i; break; }
                        }
                    }
                    renderMusicBoxPage();
                }
            });
        }
    }).start();
}

void startNeSearch(final String kw) {
    if (gNeSearching) return;
    gNeSearching = true;
    gNeQuery = kw;
    gNeState = "";
    gSongs = new ArrayList();
    renderMusicBoxPage();

    new Thread(new Runnable() {
        public void run() {
            List list = new ArrayList();
            String err = "";
            try {
                String u = "https://music.163.com/api/search/get/web?s=" + enc(kw) + "&type=1&limit=30&offset=0";
                String raw = httpGetNe(u);
                org.json.JSONObject root = new org.json.JSONObject(raw);
                org.json.JSONObject res = root.optJSONObject("result");
                if (res != null) {
                    org.json.JSONArray arr = res.optJSONArray("songs");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject o = arr.getJSONObject(i);
                            String id = o.optString("id", "");
                            if (id.length() == 0) continue;
                            String nm = o.optString("name", "");
                            String ar = "";
                            org.json.JSONArray as = o.optJSONArray("artists");
                            if (as != null) {
                                for (int k = 0; k < as.length(); k++) {
                                    if (k > 0) ar = ar + "/";
                                    ar = ar + as.getJSONObject(k).optString("name", "");
                                }
                            }
                            String dur = fmtMs(o.opt("duration"));
                            list.add(new String[]{ nm, ar, id, "", dur });
                        }
                    }
                }
                if (list.size() == 0) err = "没有找到相关歌曲";
            } catch (Throwable t) {
                err = "搜索失败，请检查网络";
            }
            final List fl = list;
            final String fe = err;
            postUi(new Runnable() {
                public void run() {
                    gNeSearching = false;
                    gSongs = fl;
                    gNeState = fe;
                    // 新列表可能换了顺序，按正在播放的歌曲 id 重新定位
                    gMIndex = -1;
                    if (gMPlayingId != null && gMPlayingId.length() > 0) {
                        for (int i = 0; i < gSongs.size(); i++) {
                            if (gMPlayingId.equals(((String[]) gSongs.get(i))[2])) { gMIndex = i; break; }
                        }
                    }
                    renderMusicBoxPage();
                }
            });
        }
    }).start();
}

String nePlayUrl(String id) {
    // 已登录时用账号请求播放地址，可播放会员 / 高音质歌曲
    String cookie = neCookie();
    if (cookie != null && cookie.indexOf("MUSIC_U=") >= 0) {
        try {
            String level = "exhigh";
            String u = "https://music.163.com/api/song/enhance/player/url/v1?ids=[" + id + "]&level=" + level + "&encodeType=mp3";
            String csrf = getCsrfToken(cookie);
            if (csrf.length() > 0) u = u + "&csrf_token=" + csrf;
            String raw = httpGetNe(u);
            if (raw != null && raw.length() > 0) {
                org.json.JSONObject root = new org.json.JSONObject(raw);
                org.json.JSONArray arr = root.optJSONArray("data");
                if (arr != null && arr.length() > 0) {
                    String url = arr.getJSONObject(0).optString("url", "");
                    if (url != null && url.length() > 0 && url.startsWith("http")) return url;
                }
            }
        } catch (Throwable t) {
            log("error.txt", "nePlayUrl增强接口失败=" + t.toString());
        }
    }
    return "https://music.163.com/song/media/outer/url?id=" + id + ".mp3";
}

// ===== 插件自动更新 =====
boolean gUpdChecked = false;

String localPluginVersion() {
    String t = readTextFile(pluginPath + "/info.prop");
    if (t == null) return "";
    String[] lines = t.split("\n");
    for (int i = 0; i < lines.length; i++) {
        String ln = lines[i].trim();
        if (ln.startsWith("versionCode=")) return ln.substring("versionCode=".length()).trim();
    }
    return "";
}

int cmpVersion(String a, String b) {
    String[] x = a.split("\\.");
    String[] y = b.split("\\.");
    int n = Math.max(x.length, y.length);
    for (int i = 0; i < n; i++) {
        int xi = 0;
        int yi = 0;
        try { if (i < x.length) xi = Integer.parseInt(x[i].trim()); } catch (Throwable t) {}
        try { if (i < y.length) yi = Integer.parseInt(y[i].trim()); } catch (Throwable t) {}
        if (xi != yi) return xi < yi ? -1 : 1;
    }
    return 0;
}

void upLog(String msg) {
    try {
        java.io.FileWriter fw = new java.io.FileWriter(pluginPath + "/update.log", true);
        fw.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(new java.util.Date()) + " " + msg + "\n");
        fw.close();
    } catch (Throwable t) {}
}

boolean installPluginZip(java.io.File zipFile) {
    java.util.zip.ZipInputStream zin = null;
    try {
        zin = new java.util.zip.ZipInputStream(new java.io.FileInputStream(zipFile));
        java.util.zip.ZipEntry e;
        byte[] buf = new byte[8192];
        int n = 0;
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            if (!name.startsWith("voicebox/")) {
                continue;
            }
            String rel = name.substring(9);
            java.io.File out = new java.io.File(pluginPath, rel);
            if (e.isDirectory()) {
                out.mkdirs();
                continue;
            }
            java.io.File dir = out.getParentFile();
            if (dir != null) dir.mkdirs();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            int len;
            while ((len = zin.read(buf)) > 0) fos.write(buf, 0, len);
            fos.close();
            n++;
        }
        upLog("extract files=" + n);
        return n > 0;
    } catch (Throwable t) {
        upLog("extract error " + t);
        return false;
    } finally {
        try { if (zin != null) zin.close(); } catch (Throwable t) {}
    }
}

boolean installViaHost(java.io.File zip) {
    try {
        ClassLoader cl = gAct.getClassLoader();
        Class c = Class.forName("me.yxp.qfun.plugin.loader.PluginManager", true, cl);
        Object pm = c.getField("INSTANCE").get(null);
        java.lang.reflect.Method m = c.getMethod("installPluginFromZip", new Class[]{java.io.File.class});
        Object r = m.invoke(pm, new Object[]{zip});
        upLog("host install=" + r);
        return true;
    } catch (Throwable t) {
        upLog("host install fail " + t);
        return false;
    }
}

void checkPluginUpdate() {
    forceCheckUpdate(false);
}

void forceCheckUpdate(final boolean manual) {
    if (!manual && gUpdChecked) return;
    gUpdChecked = true;
    final String lv = localPluginVersion();
    if (manual) toast("当前插件 v" + lv + "，正在检查…");
    new Thread(new Runnable() {
        public void run() {
            try {
                String raw = httpGetSilent(CLOUD_BASE + "plugin/latest.json?t=" + System.currentTimeMillis());
                if (raw == null || raw.length() == 0) {
                    upLog("check empty response");
                    if (manual) postUi(new Runnable() { public void run() { toast("检查更新失败：服务器无响应"); } });
                    return;
                }
                org.json.JSONObject o = new org.json.JSONObject(raw);
                final String rv = o.optString("version", "");
                String zip = o.optString("zip", "");
                upLog("check local=" + lv + " remote=" + rv);
                if (rv.length() == 0 || zip.length() == 0) {
                    if (manual) postUi(new Runnable() { public void run() { toast("检查更新失败：数据异常"); } });
                    return;
                }
                if (cmpVersion(lv, rv) >= 0) {
                    if (manual) postUi(new Runnable() { public void run() { toast("已是最新版本 v" + lv); } });
                    return;
                }
                postUi(new Runnable() { public void run() { toast("发现新版本 v" + rv + "，正在下载…"); } });
                java.io.File dest = new java.io.File(gAct.getCacheDir(), "voicebox_" + rv + ".zip");
                boolean ok = httpDownload(zip, dest);
                upLog("download=" + ok + " size=" + dest.length());
                if (!ok || dest.length() < 10000) {
                    postUi(new Runnable() { public void run() { toast("更新包下载失败"); } });
                    return;
                }
                installViaHost(dest);
                boolean ins = cmpVersion(localPluginVersion(), rv) >= 0 || installPluginZip(dest);
                upLog("install=" + ins + " now=" + localPluginVersion());
                if (ins && cmpVersion(localPluginVersion(), rv) >= 0) {
                    postUi(new Runnable() { public void run() { toast("已更新到 v" + rv + "，重新打开插件生效"); } });
                } else {
                    postUi(new Runnable() { public void run() { toast("安装失败，请到插件商店更新"); } });
                }
            } catch (Throwable t) {
                upLog("check error " + t);
                if (manual) postUi(new Runnable() { public void run() { toast("检查更新失败，详见 update.log"); } });
            }
        }
    }).start();
}

void buildPlayerBar(Context act) {
    gPlayerBar = new LinearLayout(act);
    gPlayerBar.setOrientation(LinearLayout.VERTICAL);
    gPlayerBar.setPadding(dp2px(act, 14), dp2px(act, 12), dp2px(act, 14), dp2px(act, 12));
    gPlayerBar.setBackground(makeCardBg(act, 24));
    gPlayerBar.setVisibility(View.GONE);

    LinearLayout top = new LinearLayout(act);
    top.setOrientation(LinearLayout.HORIZONTAL);
    top.setGravity(Gravity.CENTER_VERTICAL);

    gMNow = new TextView(act);
    gMNow.setText("未在播放");
    gMNow.setTextSize(14);
    gMNow.setTypeface(Typeface.DEFAULT_BOLD);
    gMNow.setTextColor(C_TEXT);
    gMNow.setSingleLine(true);
    gMNow.setEllipsize(TextUtils.TruncateAt.END);
    top.addView(gMNow, new LinearLayout.LayoutParams(0, -2, 1f));

    TextView sendBtn = makeActionButton(act, "发送", true);
    sendBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            sendCurrentSong();
        }
    });
    LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp2px(act, 58), dp2px(act, 30));
    sendLp.leftMargin = dp2px(act, 8);
    top.addView(sendBtn, sendLp);
    gPlayerBar.addView(top, new LinearLayout.LayoutParams(-1, -2));

    gMProg = makeProgressBar(act, 4);
    LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(-1, dp2px(act, 4));
    progLp.topMargin = dp2px(act, 8);
    gPlayerBar.addView(gMProg, progLp);

    LinearLayout ctrl = new LinearLayout(act);
    ctrl.setOrientation(LinearLayout.HORIZONTAL);
    ctrl.setGravity(Gravity.CENTER);

    TextView prev = makeCircleBtn(act, "‹", 40, false);
    gMPlayBtn = makeCircleBtn(act, "▶", 46, true);
    TextView next = makeCircleBtn(act, "›", 40, false);

    LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-2, -2);
    pLp.rightMargin = dp2px(act, 10);
    ctrl.addView(prev, pLp);

    LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(-2, -2);
    mLp.rightMargin = dp2px(act, 10);
    ctrl.addView(gMPlayBtn, mLp);

    ctrl.addView(next, new LinearLayout.LayoutParams(-2, -2));

    prev.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            prevSong();
        }
    });
    gMPlayBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            toggleMusicPlay();
        }
    });
    next.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            nextSong();
        }
    });

    LinearLayout.LayoutParams ctrlLp = new LinearLayout.LayoutParams(-1, -2);
    ctrlLp.topMargin = dp2px(act, 8);
    gPlayerBar.addView(ctrl, ctrlLp);

    LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
    barLp.topMargin = dp2px(act, 8);
    gRoot.addView(gPlayerBar, barLp);
}

void showPlayerBar() {
    if (gPlayerBar != null) gPlayerBar.setVisibility(View.VISIBLE);
}

void hidePlayerBar() {
    if (gPlayerBar != null) gPlayerBar.setVisibility(View.GONE);
}

String neCacheName(String[] s) {
    return gPlatform + "_" + Math.abs((s[0] + s[1]).hashCode()) + ".mp3";
}

java.io.File neCacheFile(String[] s) {
    return new java.io.File(getMusicBoxDir(), neCacheName(s));
}

void playSongAt(final int index) {
    if (index < 0 || index >= gSongs.size()) return;
    stopPreview();
    stopMusic();
    gMIndex = index;
    final String[] s = (String[]) gSongs.get(index);
    gMPlayingId = s[2];

    showPlayerBar();
    updateMusicState();

    final java.io.File cache = neCacheFile(s);
    if (cache.exists() && cache.length() > 50000) {
        playLocalFile(cache);
        return;
    }
    if (gMNow != null) gMNow.setText("正在下载…");
    final int fi = index;
    new Thread(new Runnable() {
        public void run() {
            String url = s[3];
            if (url == null || url.length() == 0) {
                if ("qq".equals(gPlatform)) url = qqPlayUrl(s[2]);
                else if ("kg".equals(gPlatform)) url = kgPlayUrl(s[2]);
                else url = nePlayUrl(s[2]);
            }
            String dlCookie = neCookie();
            if ("qq".equals(gPlatform)) dlCookie = qqCookie();
            else if ("kg".equals(gPlatform)) dlCookie = kgCookie();
            boolean ok = url.length() > 0 && httpDownloadEx(url, cache, dlCookie);
            final boolean fok = ok;
            postUi(new Runnable() {
                public void run() {
                    if (gMIndex != fi) return;
                    if (!fok || !cache.exists() || cache.length() < 50000) {
                        try { if (cache.exists()) cache.delete(); } catch (Throwable t) {}
                        if (gMNow != null) gMNow.setText("未在播放");
                        if ("qq".equals(gPlatform)) {
                            toast(qqLogged() ? "QQ音乐该歌曲无版权或需要会员" : "QQ音乐播放需先到会员登录管理登录账号");
                        } else if ("kg".equals(gPlatform)) {
                            toast(kgLogged() ? "酷狗该歌曲无版权或需要VIP" : "酷狗播放需先到会员登录管理登录账号");
                        } else {
                            toast("该歌曲暂无版权或需要会员，换个版本试试");
                        }
                        return;
                    }
                    playLocalFile(cache);
                }
            });
        }
    }).start();
}

void playLocalFile(final java.io.File file) {
    try {
        android.media.MediaPlayer mp = new android.media.MediaPlayer();
        mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
        mp.setDataSource(file.getAbsolutePath());
        mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
            public void onCompletion(android.media.MediaPlayer m) {
                if (gMPlayer == m) {
                    gMPlayer = null;
                    gMPrepared = false;
                    nextSong();
                }
            }
        });
        mp.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
            public boolean onError(android.media.MediaPlayer m, int what, int extra) {
                try { m.release(); } catch (Throwable t) {}
                if (gMPlayer == m) {
                    gMPlayer = null;
                    gMPrepared = false;
                }
                updateMusicState();
                toast("该文件无法播放");
                return true;
            }
        });
        mp.prepare();
        mp.start();
        gMPlayer = mp;
        gMPrepared = true;
        updateMusicState();
        startMTick();
    } catch (Throwable t) {
        gMPlayer = null;
        gMPrepared = false;
        updateMusicState();
        toast("播放失败: " + t.getMessage());
    }
}

// 发送前才落盘：下载到本地
void downloadMusicForSend(final int index) {
    if (index < 0 || index >= gSongs.size()) return;
    final String[] s = (String[]) gSongs.get(index);
    final java.io.File dest = neCacheFile(s);
    new Thread(new Runnable() {
        public void run() {
            String url = s[3];
            if (url == null || url.length() == 0) url = nePlayUrl(s[2]);
            boolean ok = dest.exists() && dest.length() > 50000;
            if (!ok) ok = url.length() > 0 && httpDownloadEx(url, dest, neCookie());
            final boolean fok = ok;
            postUi(new Runnable() {
                public void run() {
                    hideDlBar();
                    if (!fok) {
                        toast("下载失败，请检查网络");
                        return;
                    }
                    sendItem(dest);
                }
            });
        }
    }).start();
}

void toggleMusicPlay() {
    if (gMPlayer == null) {
        if (gMIndex >= 0 && gMIndex < gSongs.size()) playSongAt(gMIndex);
        else if (gSongs.size() > 0) playSongAt(0);
        return;
    }
    try {
        android.media.MediaPlayer mp = (android.media.MediaPlayer) gMPlayer;
        if (mp.isPlaying()) mp.pause();
        else mp.start();
    } catch (Throwable t) {}
    updateMusicState();
    startMTick();
}

void nextSong() {
    if (gSongs.size() == 0) return;
    int n = gMIndex + 1;
    if (n >= gSongs.size()) n = 0;
    playSongAt(n);
}

void prevSong() {
    if (gSongs.size() == 0) return;
    int n = gMIndex - 1;
    if (n < 0) n = gSongs.size() - 1;
    playSongAt(n);
}

void stopMusic() {
    if (gMPlayer != null) {
        try { ((android.media.MediaPlayer) gMPlayer).stop(); } catch (Throwable t) {}
        try { ((android.media.MediaPlayer) gMPlayer).release(); } catch (Throwable t) {}
        gMPlayer = null;
    }
    gMPrepared = false;
    gMPlayingId = "";
    if (gMTick != null) gMTick.removeCallbacksAndMessages(null);
    if (gMProg != null) gMProg.setProgress(0);
}

void startMTick() {
    if (gMTick == null) gMTick = new android.os.Handler(android.os.Looper.getMainLooper());
    if (gMTickRun == null) {
        gMTickRun = new Runnable() {
            public void run() {
                updateMusicProgress();
                if (gMPlayer != null && gMTick != null) gMTick.postDelayed(gMTickRun, 500);
            }
        };
    }
    gMTick.removeCallbacks(gMTickRun);
    gMTick.postDelayed(gMTickRun, 500);
}

void updateMusicProgress() {
    if (gMPlayer == null || gMProg == null) return;
    try {
        android.media.MediaPlayer mp = (android.media.MediaPlayer) gMPlayer;
        int dur = mp.getDuration();
        int pos = mp.getCurrentPosition();
        if (dur > 0) gMProg.setProgress((int)((long) pos * 100 / dur));
    } catch (Throwable t) {}
}

void updatePlayerBarText() {
    int idx = -1;
    if (gMPlayingId != null && gMPlayingId.length() > 0) {
        for (int i = 0; i < gSongs.size(); i++) {
            if (gMPlayingId.equals(((String[]) gSongs.get(i))[2])) { idx = i; break; }
        }
    }
    if (idx < 0) {
        if (gMNow != null) gMNow.setText("未在播放");
        return;
    }
    gMIndex = idx;
    String[] s = (String[]) gSongs.get(idx);
    if (gMNow != null) gMNow.setText(s[0] + (s[1].length() > 0 ? " · " + s[1] : ""));
}

void updateMusicState() {
    boolean playing = false;
    if (gMPlayer != null) {
        try { playing = ((android.media.MediaPlayer) gMPlayer).isPlaying(); } catch (Throwable t) {}
    }
    if (gMPlayBtn != null) gMPlayBtn.setText(playing ? "❚❚" : "▶");
    updatePlayerBarText();
    for (int i = 0; i < gMRowIcons.size() && i < gSongs.size(); i++) {
        TextView ic = (TextView) gMRowIcons.get(i);
        TextView nm = (TextView) gMRowNames.get(i);
        boolean cur = gMPlayingId != null && gMPlayingId.length() > 0 && gMPlayingId.equals(((String[]) gSongs.get(i))[2]);
        if (cur) {
            ic.setText(playing ? "❚❚" : "▶");
            ic.setTextColor(C_BLUE);
            if (nm != null) nm.setTextColor(C_BLUE);
        } else {
            ic.setText("▶");
            ic.setTextColor(C_CHEVRON);
            if (nm != null) nm.setTextColor(C_TEXT);
        }
    }
}

void sendCurrentSong() {
    if (gMIndex < 0 || gMIndex >= gSongs.size()) {
        toast("先选一首歌");
        return;
    }
    String[] s = (String[]) gSongs.get(gMIndex);
    java.io.File cache = neCacheFile(s);
    if (cache.exists() && cache.length() > 10000) {
        sendItem(cache);
        return;
    }
    downloadMusicForSend(gMIndex);
}

// ===== 发送 =====
void sendItem(java.io.File file) {
    if (file == null) return;
    if (!file.exists()) {
        if (gCloudUrls.containsKey(file.getAbsolutePath())) {
            downloadCloudFile(file);
            return;
        }
        toast("文件不存在");
        return;
    }
    stopPreview();
    sendVoiceByApi(gChatType, gPeerUin, file.getAbsolutePath());
}

void addCloseButton(Context act) {
    TextView closeBtn = new TextView(act);
    closeBtn.setText("完成");
    closeBtn.setTextColor(Color.WHITE);
    closeBtn.setTextSize(17);
    closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
    closeBtn.setGravity(Gravity.CENTER);

    GradientDrawable closeBg = new GradientDrawable();
    closeBg.setColor(C_BLUE);
    closeBg.setCornerRadius(dp2px(act, 16));
    closeBtn.setBackground(closeBg);

    gRoot.addView(closeBtn, new LinearLayout.LayoutParams(-1, dp2px(act, 50)));
    closeBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            closeMenu();
        }
    });
}

void closeMenu() {
    stopPreview();
    if (gDialog != null) {
        try { gDialog.dismiss(); } catch (Exception e) {}
        gDialog = null;
    }
    gDlBox = null;
    gDlBar = null;
    gDlText = null;
}

// ===== 发送语音（走 QEdge 内置 sendPtt：自动转 uid、构造 Ptt 元素） =====
void sendVoiceByApi(int chatType, String peerUin, String filePath) {
    try {
        sendPtt(peerUin, filePath, chatType);
    } catch (Exception e) {
        toast("发送失败: " + e.getMessage());
    }
}

int dp2px(Context ctx, float dp) {
    return (int)(dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
}

// ===== QEdge 适配：统一 toast 包装 =====
void toast(String msg) {
    try { qqToast(2, msg); } catch (Throwable e) {}
}

toast("语音盒已加载");
