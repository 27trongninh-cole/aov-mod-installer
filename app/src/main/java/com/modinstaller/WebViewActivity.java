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

        // Interface cho JS gọi ngược vào Java để lưu file blob
        webView.addJavascriptInterface(new BlobDownloadInterface(), "AndroidBlobDownload");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
            }
        });

        // Bắt sự kiện download từ trang web
        webView.setDownloadListener((downloadUrl, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (downloadUrl.startsWith("blob:")) {
                // Blob URL: không tải trực tiếp được, cần JS đọc rồi convert base64
                downloadBlobViaJs(downloadUrl);
            } else {
                downloadNormalFile(downloadUrl, userAgent, contentDisposition, mimeType);
            }
        });

        if (url != null) {
            webView.loadUrl(url);
        }
    }

    // Tải file HTTP bình thường qua DownloadManager
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

    // Tải file dạng blob: qua JS injection, đọc bằng FileReader rồi gửi base64 về Java
    private void downloadBlobViaJs(String blobUrl) {
        String js =
            "(function() {" +
            "  fetch('" + blobUrl + "')" +
            "    .then(res => res.blob())" +
            "    .then(blob => {" +
            "      var reader = new FileReader();" +
            "      reader.onloadend = function() {" +
            "        var base64 = reader.result.split(',')[1];" +
            "        var fileName = 'mod_' + Date.now() + '.zip';" +
            "        AndroidBlobDownload.saveBlobFile(base64, fileName);" +
            "      };" +
            "      reader.readAsDataURL(blob);" +
            "    })" +
            "    .catch(err => AndroidBlobDownload.onError(err.toString()));" +
            "})();";

        runOnUiThread(() -> {
            Toast.makeText(this, "Đang xử lý file tải về...", Toast.LENGTH_SHORT).show();
            webView.evaluateJavascript(js, null);
        });
    }

    // Interface nhận dữ liệu base64 từ JS và lưu ra file thật
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
