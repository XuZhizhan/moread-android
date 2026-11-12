package com.moread.reader;

import android.os.Bundle;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MoreadNativePlugin.class);
        super.onCreate(savedInstanceState);

        // 系统返回键 / 右滑返回：先退 WebView 的历史，退无可退才退出 App。
        //
        // 为什么必须接管：Capacitor 8 的 BridgeActivity 对返回键一个字都没写，
        // 默认一路走到 Activity.onBackPressed() → finish()。读者在阅读器里从屏幕边缘
        // 右滑一下，App 直接没了（2026-11-13 真机反馈）。
        //
        // 为什么不用 onBackPressed() 覆写：本包 targetSdk 36，Android 15 起
        // enableOnBackInvokedCallback 默认 true，系统不再回调 onBackPressed()，
        // 直接走注册的 OnBackInvokedCallback。覆写 onBackPressed 在新机上等于没写，
        // 所以统一挂 androidx 的 OnBackPressedDispatcher —— 新旧两条路径都会汇到它。
        //
        // Web 那边每一层（抽屉 / 阅读器 / 词卡 / 划词卡）都是 history.pushState 压进来的
        // （见 app/01a-back.js 的 backPush），所以 webView.goBack() 正好等于「退一层」，
        // popstate 会照常触发；书架那一层退完 canGoBack() 变 false，这时才真的退出。
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView web = getBridge() != null ? getBridge().getWebView() : null;
                if (web != null && web.canGoBack()) {
                    web.goBack();
                    return;
                }
                // 退到底了：先把自己关掉，再把返回交还给系统，否则会自己调自己转圈
                setEnabled(false);
                MainActivity.this.getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }
}
