package com.moread.reader;

import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    /**
     * 交给页面判断「还能不能退一层」，返回值见 Web 侧 app/01a-back.js 的 __moreadBack：
     * handled / wait / exit，其它一律当 legacy（走壳自己的老判断）。
     */
    private static final String BACK_JS =
        "(function(){try{return window.__moreadBack?window.__moreadBack():'legacy';}catch(e){return 'legacy';}})()";

    /** 品牌蓝。与图标底色同源（scripts/make-icons.mjs 的 FALLBACK_BRAND）。 */
    private static final int BRAND = 0xFF2E9BFF;
    /** 提示文字常态色：白 54%。
     *  改成白的是因为底色换成了深墨 —— 原来那版是黑 54%（0x8A000000），
     *  那是为纸色底配的，放到深底上等于隐形。 */
    private static final int HINT = 0x8AFFFFFF;

    private OnBackInvokedCallback backCallback;   // 只在 API 33+ 用得上

    /* ---------------- 开屏覆盖层 ----------------
     * 为什么需要它：系统启动窗口在 WebView「画出第一帧」时就撤，而那一帧往往还是空白
     * （壳加载的是包内页面，要等 HTML/CSS 解析完才画得出第一屏）。撤完之后就是一整片
     * 底色空等，观感是「卡住了」。这一层接住那段空窗，把「空等」换成「图标 + 进度 + 一句在做什么」。
     *
     * 为什么不用 @capacitor/splash-screen：它要装 npm 依赖、改配置，而这里需要的只是
     * 「一个盖在上面的 View + 一个撤掉它的时机」。自己写几十行比引一个插件可控。
     *
     * 边界说清楚：系统启动窗口那一帧是系统画的，应用管不了（只能是底色 + 图标）；
     * 这一层在 Activity 起来之后才存在，所以它替代不了那一帧，只能让交接不闪。
     */
    private static MainActivity current;          // 插件（另一个类）通过它回调
    private View splash;
    private TextView splashHint;
    private boolean splashGone;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable slowHint;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MoreadNativePlugin.class);
        super.onCreate(savedInstanceState);
        current = this;

        /*
         * 为什么直接挂平台的 OnBackInvokedCallback，而不是覆写 onBackPressed()：
         * 本包 targetSdk 36，Android 15 起 enableOnBackInvokedCallback 默认 true，
         * 系统不再调 Activity.onBackPressed()，覆写在 15+ 上等于没写（build-6 真机反馈）。
         *
         * 为什么不用 androidx 的 OnBackPressedDispatcher（build-6 用的就是它，也没拦住）：
         * 它在 API 33+ 内部也是转注册一个 OnBackInvokedCallback，多绕一层、还受 lifecycle
         * 启停影响。这里用 PRIORITY_OVERLAY 自己占住最先收到返回的位置，
         * 排它之后就没有第二个人能把这个返回改成 finish()。
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCallback);
        }

        showSplash();
    }

    @Override
    public void onDestroy() {
        if (current == this) current = null;
        if (slowHint != null) ui.removeCallbacks(slowHint);
        ui.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
        super.onDestroy();
    }

    /* ---------------- 开屏：搭、等、撤 ---------------- */

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showSplash() {
        if (splash != null) return;

        FrameLayout box = new FrameLayout(this);
        /* 与系统启动窗口同色，也与 Web 开屏六版的底色同一套（深墨 #0B1116）。
           三段交接只要有一处不是这个色，那一下就是整屏由浅翻深或由深翻浅 ——
           看起来像「画面不同步」，其实是底色在跳。
           （02/03 两版是品牌蓝，靠本层 200ms 淡出过渡，变亮比变暗好接受。） */
        box.setBackgroundColor(getResources().getColor(R.color.moread_launch_bg, getTheme()));
        box.setClickable(true);            // 挡住穿透：加载中点到后面的 WebView 会误触

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setContentDescription("悦读星球");
        int side = dp(84);
        col.addView(icon, new LinearLayout.LayoutParams(side, side));

        ProgressBar spin = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        try {
            spin.setIndeterminateTintList(ColorStateList.valueOf(BRAND));
        } catch (Throwable ignored) { /* 老 ROM 上设不了就显示默认色，不影响功能 */ }
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(18), dp(18));
        sp.topMargin = dp(26);
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(spin, sp);

        splashHint = new TextView(this);
        splashHint.setText("正在打开书架…");
        splashHint.setTextSize(13);
        splashHint.setTextColor(HINT);
        splashHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(14);
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(splashHint, hp);

        box.addView(col, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        addContentView(box, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        splash = box;

        /* 系统启动窗口那一帧是纯底色（见 build.sh「去掉开屏画面」），这里让内容淡入，
           而不是「唰」地出现 —— 两段衔接才看不出接缝。260ms 足够，再慢就像卡了一下。 */
        col.setAlpha(0f);
        col.animate().alpha(1f).setDuration(260).start();

        armSlowHint();
    }

    /**
     * 8 秒还没画出来就把话说开，并给一个能点的重试。
     * 这一条比省几百毫秒更值：断网时不再是「一直转圈」，而是「慢，可以点」。
     */
    private void armSlowHint() {
        if (slowHint != null) ui.removeCallbacks(slowHint);
        slowHint = () -> {
            if (splashGone || splashHint == null) return;
            splashHint.setText("网络有点慢 · 点这里重试");
            splashHint.setTextColor(BRAND);
            splashHint.setOnClickListener((v) -> retryFromSplash());
        };
        ui.postDelayed(slowHint, 8000);
    }

    private void retryFromSplash() {
        if (splashHint != null) {
            splashHint.setText("正在打开书架…");
            splashHint.setTextColor(HINT);
            splashHint.setOnClickListener(null);
        }
        armSlowHint();
        try {
            Bridge b = getBridge();
            WebView w = b != null ? b.getWebView() : null;
            if (w != null) w.reload();
        } catch (Throwable ignored) { /* 重载失败就继续等，别把开屏也弄崩 */ }
    }

    /** 由插件在 Web 首帧画完后调用（同包静态入口，不经过实例传递）。 */
    static void notifyWebReady() {
        final MainActivity a = current;
        if (a == null) return;
        a.runOnUiThread(a::dismissSplash);
    }

    private void dismissSplash() {
        if (splash == null || splashGone) return;
        splashGone = true;
        if (slowHint != null) ui.removeCallbacks(slowHint);

        final View v = splash;
        splash = null;                     // 先摘引用：重复调用不会叠第二层
        v.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            ViewGroup p = (ViewGroup) v.getParent();
            if (p != null) p.removeView(v);
        }).start();
    }

    /* ---------------- 返回键 ---------------- */

    /**
     * API 32 及以下：手势返回和三键返回都还是 KEYCODE_BACK，直接走到这里。
     * 33+ 开了 enableOnBackInvokedCallback 之后系统不再派发这个键，上面那条路接管。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                return true;                     // 吞掉按下，等抬起再动作，避免长按连发
            }
            if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
                handleBack();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** 唯一的返回处理入口：拿到 WebView，交给页面决定。 */
    private void handleBack() {
        Bridge bridge = getBridge();
        final WebView web = bridge != null ? bridge.getWebView() : null;
        if (web == null) {
            finish();
            return;
        }
        web.evaluateJavascript(BACK_JS, value -> {
            String v = value == null ? "" : value.replace("\"", "").trim();
            if ("exit".equals(v)) {
                finish();
                return;
            }
            if ("handled".equals(v) || "wait".equals(v)) {
                return;                          // 页面已经处理了，壳不插手
            }
            // 页面还没加载完 / 还是旧版 Web：退回壳自己的判断
            if (web.canGoBack()) web.goBack();
            else finish();
        });
    }
}
