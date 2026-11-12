package com.moread.reader;

import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.WebView;
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

    private OnBackInvokedCallback backCallback;   // 只在 API 33+ 用得上

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MoreadNativePlugin.class);
        super.onCreate(savedInstanceState);

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
    }

    @Override
       public void onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
        super.onDestroy();
    }

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
