package com.modinstaller;

import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;

public class WebViewActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String DOWNLOAD_SUBFOLDER = "ModNinstaller";

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> fileChooserCallback;

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (fileChooserCallback == null) return;

            Uri[] resultUris = null;
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Intent data = result.getData();
                if (data.getClipData() != null) {
                    // Nhiều file được chọn cùng lúc
                    int count = data.getClipData().getItemCount();
                    resultUris = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        resultUris[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    // 1 file duy nhất
                    resultUris = new Uri[]{data.getData()};
                }
            }
            fileChooserCallback.onReceiveValue(resultUris);
            fileChooserCallback = null;
        });

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

        // Bridge <input type="file"> của web sang file picker thật của Android
        // Dùng ACTION_OPEN_DOCUMENT thay vì createIntent() mặc định để tránh
        // Android tự động mở "Photo Picker" (giao diện Truy cập riêng tư gây phiền)
        // — ép luôn mở app Files thật sự cho cả chọn file lẫn nhiều file.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                              FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = filePathCallback;

                boolean allowMultiple = fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);

                // Web chỉ nhận ảnh (PNG) và ZIP — lọc đúng MIME type thay vì */*,
                // giúp Files app chỉ hiện đúng loại file cần thiết.
                String[] acceptTypes = fileChooserParams.getAcceptTypes();
                java.util.List<String> mimeTypes = new java.util.ArrayList<>();
                if (acceptTypes != null) {
                    for (String t : acceptTypes) {
                        if (t == null || t.isEmpty()) continue;
                        if (t.equals(".png") || t.equals("image/png")) mimeTypes.add("image/png");
                        else if (t.equals(".zip") || t.contains("zip")) {
                            mimeTypes.add("application/zip");
                            mimeTypes.add("application/x-zip-compressed");
                        } else if (t.startsWith("image/")) mimeTypes.add(t);
                        else if (t.startsWith(".")) {
                            // Đuôi file lạ không map được — fallback */* để không chặn nhầm
                        }
                    }
                }

                if (mimeTypes.isEmpty()) {
                    // Không xác định được loại cụ thể → cho chọn mọi file
                    intent.setType("*/*");
                } else if (mimeTypes.size() == 1) {
                    intent.setType(mimeTypes.get(0));
                } else {
                    // Nhiều loại (vd ảnh + zip) → setType chung, lọc chi tiết qua EXTRA_MIME_TYPES
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toArray(new String[0]));
                }

                try {
                    fileChooserLauncher.launch(intent);
                } catch (Exception e) {
                    fileChooserCallback = null;
                    Toast.makeText(WebViewActivity.this,
                        "Không mở được trình chọn file: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
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
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
