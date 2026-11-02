package xyz.vvzvlad.projectorremote;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Fullscreen WebView showing the same remote page the server gives to phones. */
public class MainActivity extends Activity {
    private static final String URL = "http://127.0.0.1:" + HttpServer.PORT + "/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startForegroundService(new Intent(this, RemoteService.class));

        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(final WebView view, WebResourceRequest request, WebResourceError error) {
                // The service may not be listening yet on first start: retry until it is.
                // No lambdas: android.jar 28 has no LambdaMetafactory for javac to target.
                if (request.isForMainFrame()) {
                    view.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            view.loadUrl(URL);
                        }
                    }, 1000);
                }
            }
        });
        setContentView(web);
        web.requestFocus();
        web.loadUrl(URL);
    }
}
