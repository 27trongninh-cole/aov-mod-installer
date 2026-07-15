package com.modinstaller;

import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;

public class WebViewActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String DOWNLOAD_SUBFOLDER = "ModNinstaller";

    private WebView webView;
    private ProgressBar progressBar;

    // JS được inject ngay khi trang load xong, chặn mọi <a download> hoặc
    // window.open trên blob: URL để đọc dữ liệu NGAY LÚC CÒN TỒN TẠI,
    // tránh trường hợp blob bị revoke trước khi Android kịp fetch lại.
    private static final String INTERCEPT_JS =
        "(function() {" +
        "  if (window.__androidBlobHooked) return;" +
        "  window.__androidBlobHooked = true;" +
        "  function handleBlobUrl(blobUrl, fileName) {" +
        "    fetch(blobUrl).then(function(res) { return res.blob(); })" +
        "      .then(function(blob) {" +
        "        var reader = new FileReader();" +
        "        reader.onloadend = function() {" +
        "          var base64 = reader.result.split(',')[1];" +
        "          AndroidBlobDownload.saveBlobFile(base64, fileName || ('mod_' + Date.now() + '.zip'));" +
        "        };" +
        "        reader.readAsDataURL(blob);" +
        "      })" +
        "      .catch(function(err) { AndroidBlobDownload.onError(err.toString()); });" +
        "  }" +
        "  document.addEventListener('click', function(e) {" +
        "    var el = e.target;" +
        "    while (el && el.tagName !== 'A') el = el.parentElement;" +
        "    if (el && el.href && el.href.indexOf('blob:') === 0) {" +
        "      e.preventDefault();" +
        "      var name = el.download || null;" +
        "      handleBlobUrl(el.href, name);" +
        "    }" +
        "  }, true);" +
        "  var originalOpen = window.open;" +
        "  window.open = function(url, name, specs) {" +
        "    if (url && url.indexOf('blob:') === 0) {" +
        "      handleBlobUrl(url, null);" +
        "      return null;" +
        "    }" +
        "    return originalOpen.call(window, url, name, specs);" +
        "  };" +
        "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        TextView tvTitle = findViewById(R.id.tv_webview_title);
        if (tvTitle != null && title != null) tvTitle.setText(title);

        Button btnBack = findViewById(R.id.btn_webview_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        Button btnRefresh = findViewById(R.id.btn_webview_refresh);
        if (btnRefresh != null) btnRefresh.setOnClickListener(v -> {
            if (webView != null) webView.reload();
        });

        progressBar = findViewById(R.id.webview_progress);
        webView = findViewById(R.id.webview);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.addJavascriptInterface(new BlobDownloadInterface(), "AndroidBlobDownload");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String pageUrl) {
                super.onPageFinished(view, pageUrl);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                // Inject ngay khi trang load xong để hook sẵn, tránh miss blob download
                view.evaluateJavascript(INTERCEPT_JS, null);
            }
        });

        // Fallback: vẫn giữ DownloadListener cho các link tải HTTP thường
        webView.setDownloadListener((downloadUrl, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (!downloadUrl.startsWith("blob:")) {
                downloadNormalFile(downloadUrl, userAgent, contentDisposition, mimeType);
            }
            // blob: URL đã được INTERCEPT_JS xử lý trước khi tới đây, bỏ qua
        });

        if (url != null) {
            webView.loadUrl(url);
        }
    }

    private void downloadNormalFile(String downloadUrl, String userAgent, String contentDisposition, String mimeType) {
        try {
            String fileName = android.webkit.URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType);

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
            request.setMimeType(mimeType);
            request.addRequestHeader("User-Agent", userAgent);
            request.setDescription("Đang tải mod từ Mod Ninstaller");
            request.setTitle(fileName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, DOWNLOAD_SUBFOLDER + "/" + fileName);

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(this, "Đang tải: " + fileName, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi tải file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private class BlobDownloadInterface {
        @JavascriptInterface
        public void saveBlobFile(String base64Data, String suggestedName) {
            try {
                byte[] fileBytes = Base64.decode(base64Data, Base64.DEFAULT);

                File downloadDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    DOWNLOAD_SUBFOLDER);
                if (!downloadDir.exists()) downloadDir.mkdirs();

                File outFile = new File(downloadDir, suggestedName);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(fileBytes);
                }

                runOnUiThread(() -> Toast.makeText(WebViewActivity.this,
                    "Đã lưu: Download/" + DOWNLOAD_SUBFOLDER + "/" + suggestedName,
                    Toast.LENGTH_LONG).show());

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(WebViewActivity.this,
                    "Lỗi lưu file: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public void onError(String error) {
            runOnUiThread(() -> Toast.makeText(WebViewActivity.this,
                "Lỗi tải file: " + error, Toast.LENGTH_LONG).show());
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
