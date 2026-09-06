package dev.cobalt.coat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ProxyHelper {
    private static final String TAG = "YTTV_ProxyHelper";
    private static final String PREFS_NAME = "yttv_proxy_prefs";
    private static final String KEY_PROXY_URL = "proxy_url";
    private static final String KEY_PROXY_ENABLED = "proxy_enabled";
    
    public static final String KEY_SB_ENABLED = "sb_enabled";
    public static final String KEY_SB_CATEGORIES = "sb_categories";
    public static final String KEY_PREFERRED_QUALITY = "preferred_quality";
    public static final String KEY_PLAYBACK_SPEED = "playback_speed";
    public static final String KEY_COLOR_KEYS_ENABLED = "color_keys_enabled";

    public static final String DEFAULT_SB_CATEGORIES = "sponsor,selfpromo,interaction,intro,outro,preview,filler,music_offtopic";
    public static final String[] ALL_SB_CATEGORIES = new String[] {
            "sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "filler", "music_offtopic"
    };
    public static final String[] SB_CATEGORY_NAMES = new String[] {
            "Спонсорские интеграции",
            "Самореклама (мерч, соцсети)",
            "Подписка / Лайк / Колокольчик",
            "Вступительное интро",
            "Титры / Аутро в конце",
            "Анонс / Тизер в начале",
            "Вода / Филлер",
            "Немузыкальная часть в клипах"
    };

    public static Set<String> getSbCategoriesSet(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_SB_CATEGORIES, DEFAULT_SB_CATEGORIES);
        Set<String> set = new HashSet<String>();
        if (raw != null) {
            for (String s : raw.split(",")) {
                s = s.trim();
                if (!s.isEmpty()) set.add(s);
            }
        }
        return set;
    }

    public static JSONArray getSbCategoriesArray(SharedPreferences prefs) {
        String raw = prefs.getString(KEY_SB_CATEGORIES, DEFAULT_SB_CATEGORIES);
        JSONArray arr = new JSONArray();
        if (raw != null) {
            for (String s : raw.split(",")) {
                s = s.trim();
                if (!s.isEmpty()) arr.put(s);
            }
        }
        return arr;
    }

    public static String getSbCategoriesJson(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return getSbCategoriesArray(prefs).toString();
    }

    private static final int WEB_SERVER_PORT = 8888;
    private static final int LOCAL_SOCKS_PORT = 9876;

    private static volatile boolean sWebServerRunning = false;
    private static ServerSocket sWebServerSocket = null;

    private static volatile boolean sLocalSocksRunning = false;
    private static ServerSocket sLocalSocksSocket = null;
    private static volatile boolean sProxyEnabled = false;
    private static volatile ProxyConfig sCurrentConfig = null;
    private static volatile Context sContext = null;
    private static volatile long sNativeWebContents = 0;
    private static volatile long sInjectedWebContentsPtr = 0;

    private static class LruCache<K, V> extends LinkedHashMap<K, V> {
        private final int maxEntries;
        public LruCache(int maxEntries) {
            super(100, 0.75f, true);
            this.maxEntries = maxEntries;
        }
        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxEntries;
        }
    }

    private static final Map<String, String> sSponsorBlockCache = Collections.synchronizedMap(new LruCache<String, String>(200));
    private static final Map<String, Long> sLastFetchTime = Collections.synchronizedMap(new LruCache<String, Long>(100));
    private static volatile boolean sWatchdogRunning = false;

    public static void onTitleChanged(String title) {
        if (title == null || !title.startsWith("YTTV_VID:")) return;
        try {
            String rest = title.substring(9).trim();
            int colon = rest.indexOf(':');
            final String vid = (colon != -1) ? rest.substring(0, colon) : rest;
            if (vid != null && vid.length() == 11) {
                Log.i(TAG, "SponsorBlock detected video ID: " + vid);
                fetchAndInjectSegments(vid);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in onTitleChanged", t);
        }
    }

    public static void fetchAndInjectSegments(final String vid) {
        if (vid == null || vid.length() != 11) return;
        Long last = sLastFetchTime.get(vid);
        long now = System.currentTimeMillis();
        if (last != null && (now - last < 4000)) {
            return;
        }
        sLastFetchTime.put(vid, now);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String json = fetchSponsorBlockSegments(vid);
                    if (json == null || json.trim().isEmpty()) {
                        json = "[]";
                    }
                    Log.i(TAG, "Injecting SponsorBlock segments for " + vid + " (len=" + json.length() + ")...");
                    evaluateJs("if(window.__yttv_set_segments) window.__yttv_set_segments('" + vid + "', " + json + ");");
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to fetch/inject SponsorBlock for " + vid, t);
                }
            }
        }, "YTTV-SBFetch-" + vid).start();
    }

    public static synchronized void startWatchdog(final Context context) {
        if (sWatchdogRunning || context == null) return;
        sWatchdogRunning = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(2000);
                        if (sNativeWebContents != 0) {
                            evaluateJs("if(window.__yttv_check_video){window.__yttv_check_video();}");
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }, "YTTV-SBWatchdog");
        t.setDaemon(true);
        t.start();
    }

    public static void applyProxy(Context context) {
        if (context == null) {
            return;
        }
        sContext = context.getApplicationContext();
        try {
            startWebServer(sContext);
        } catch(Throwable ignored) {}
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String proxyUrl = prefs.getString(KEY_PROXY_URL, "");
            boolean enabled = prefs.getBoolean(KEY_PROXY_ENABLED, true);

            if (proxyUrl == null || proxyUrl.trim().isEmpty()) {
                proxyUrl = readExternalProxyFile();
            }

            if (!enabled || proxyUrl == null || proxyUrl.trim().isEmpty()) {
                Log.i(TAG, "No proxy configured or proxy disabled.");
                sProxyEnabled = false;
                sCurrentConfig = null;
                return;
            }

            ProxyConfig config = parseProxyConfig(proxyUrl);
            if (config == null || config.host == null || config.host.isEmpty()) {
                Log.e(TAG, "Failed to parse proxy URL: " + proxyUrl);
                sProxyEnabled = false;
                sCurrentConfig = null;
                return;
            }

            sCurrentConfig = config;
            startLocalSocksRelay(config);

            System.setProperty("http.proxyHost", "127.0.0.1");
            System.setProperty("http.proxyPort", String.valueOf(LOCAL_SOCKS_PORT));
            System.setProperty("https.proxyHost", "127.0.0.1");
            System.setProperty("https.proxyPort", String.valueOf(LOCAL_SOCKS_PORT));
            System.setProperty("socksProxyHost", "127.0.0.1");
            System.setProperty("socksProxyPort", String.valueOf(LOCAL_SOCKS_PORT));

            if (config.username != null && !config.username.isEmpty()) {
                Authenticator.setDefault(new ProxyAuthenticator(config.username, config.password != null ? config.password : ""));
            }

            try {
                auo cl = auo.a;
                if (cl != null) {
                    cl.c("proxy-server", "socks5://127.0.0.1:" + LOCAL_SOCKS_PORT);
                    cl.c("proxy-bypass-list", "127.0.0.1:8888;localhost:8888");
                    cl.c("host-resolver-rules", "MAP * 127.0.0.1, EXCLUDE 127.0.0.1, EXCLUDE localhost");
                    cl.c("testing-fixed-https-port", String.valueOf(LOCAL_SOCKS_PORT));
                    cl.c("testing-fixed-http-port", String.valueOf(LOCAL_SOCKS_PORT));
                    cl.c("disable-web-security", null);
                    Log.i(TAG, "Configured Chromium flags: host-resolver-rules, testing-fixed-ports=" + LOCAL_SOCKS_PORT);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to set CommandLine proxy flags", t);
            }

            sProxyEnabled = true;
            Log.i(TAG, "SOCKS5 proxy subsystem active -> upstream: " + config.host + ":" + config.port);
        } catch (Throwable t) {
            Log.e(TAG, "Error in applyProxy", t);
        }
    }

    public static void onWebContentsAvailable(long nativePtr) {
        if (nativePtr == 0) return;
        sNativeWebContents = nativePtr;
        if (sInjectedWebContentsPtr == nativePtr) {
            return;
        }
        sInjectedWebContentsPtr = nativePtr;
        Log.i(TAG, "WebContents initialized once: " + nativePtr);
        if (sContext != null) {
            try {
                startWebServer(sContext);
            } catch(Throwable ignored) {}
            try {
                startWatchdog(sContext);
            } catch(Throwable ignored) {}
            String script = buildModScript(sContext);
            evaluateJs(script);
        }
    }

    private static class JsEvaluateRunnable implements Runnable {
        private final long ptr;
        private final String script;
        JsEvaluateRunnable(long ptr, String script) {
            this.ptr = ptr;
            this.script = script;
        }
        @Override
        public void run() {
            try {
                org.jni_zero.GEN_JNI.cobalt_org_chromium_content_browser_webcontents_WebContentsImpl_evaluateJavaScript(
                        ptr, script, null);
            } catch (Throwable t) {
                Log.e(TAG, "evaluateJs error", t);
            }
        }
    }

    public static void evaluateJs(final String script) {
        final long ptr = sNativeWebContents;
        if (ptr == 0 || script == null || script.isEmpty()) return;
        new Handler(Looper.getMainLooper()).post(new JsEvaluateRunnable(ptr, script));
    }

    public static String getCronetOptions(String originalJson) {
        try {
            if (!sProxyEnabled) {
                return originalJson;
            }
            JSONObject root;
            if (originalJson != null && !originalJson.trim().isEmpty()) {
                root = new JSONObject(originalJson);
            } else {
                root = new JSONObject();
            }
            JSONObject proxyConfig = new JSONObject();
            proxyConfig.put("proxy_rules", "127.0.0.1:" + LOCAL_SOCKS_PORT);
            root.put("ProxyConfig", proxyConfig);
            String result = root.toString();
            Log.i(TAG, "Cronet experimental options configured with proxy.");
            return result;
        } catch (Throwable t) {
            Log.e(TAG, "Error adding Cronet proxy config", t);
            return originalJson;
        }
    }

    public static bnj getEffectiveProxyConfig(bnj original) {
        if (!sProxyEnabled) {
            return original;
        }
        Log.i(TAG, "Providing proxy to Chromium ProxyChangeListener: 127.0.0.1:" + LOCAL_SOCKS_PORT);
        return new bnj("127.0.0.1", LOCAL_SOCKS_PORT, null, new String[]{"127.0.0.1:8888", "localhost:8888"});
    }

    public static String getProxyProperty(String name) {
        if (!sProxyEnabled || name == null) {
            return null;
        }
        if ("http.proxyHost".equalsIgnoreCase(name) || "https.proxyHost".equalsIgnoreCase(name) || "socksProxyHost".equalsIgnoreCase(name)) {
            return "127.0.0.1";
        }
        if ("http.proxyPort".equalsIgnoreCase(name) || "https.proxyPort".equalsIgnoreCase(name) || "socksProxyPort".equalsIgnoreCase(name)) {
            return String.valueOf(LOCAL_SOCKS_PORT);
        }
        return null;
    }

    private static synchronized void startLocalSocksRelay(ProxyConfig config) {
        if (sLocalSocksRunning) {
            return;
        }
        sLocalSocksRunning = true;
        Thread serverThread = new Thread(new LocalSocksServerRunnable(config), "YTTV-LocalSocksServer");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private static class LocalSocksServerRunnable implements Runnable {
        private final ProxyConfig config;
        LocalSocksServerRunnable(ProxyConfig config) {
            this.config = config;
        }
        @Override
        public void run() {
            try {
                sLocalSocksSocket = new ServerSocket();
                sLocalSocksSocket.setReuseAddress(true);
                sLocalSocksSocket.bind(new InetSocketAddress(LOCAL_SOCKS_PORT), 100);
                Log.i(TAG, "Local Relay server listening on port " + LOCAL_SOCKS_PORT + " (all interfaces)");
                while (!Thread.currentThread().isInterrupted() && !sLocalSocksSocket.isClosed()) {
                    Socket client = sLocalSocksSocket.accept();
                    client.setTcpNoDelay(true);
                    Log.i(TAG, "Relay accepted connection from " + client.getRemoteSocketAddress());
                    Thread clientHandler = new Thread(new SocksClientRunnable(client, config), "YTTV-RelayClient");
                    clientHandler.setDaemon(true);
                    clientHandler.start();
                }
            } catch (Throwable t) {
                if (sLocalSocksSocket != null && !sLocalSocksSocket.isClosed()) {
                    Log.e(TAG, "Local Relay server error", t);
                }
            } finally {
                sLocalSocksRunning = false;
            }
        }
    }

    private static class SocksClientRunnable implements Runnable {
        private final Socket clientSocket;
        private final ProxyConfig upstreamConfig;
        SocksClientRunnable(Socket clientSocket, ProxyConfig upstreamConfig) {
            this.clientSocket = clientSocket;
            this.upstreamConfig = upstreamConfig;
        }
        @Override
        public void run() {
            handleRelayClient(clientSocket, upstreamConfig);
        }
    }

    private static void handleRelayClient(Socket clientSocket, ProxyConfig upstreamConfig) {
        try {
            clientSocket.setSoTimeout(30000);
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();

            int firstByte = clientIn.read();
            if (firstByte == -1) {
                closeQuietly(clientSocket);
                return;
            }

            if (firstByte == 0x16) {
                handleTlsClientHelloProtocol(clientSocket, clientIn, clientOut, (byte) firstByte, upstreamConfig);
            } else if (firstByte == 0x05) {
                handleSocks5Protocol(clientSocket, clientIn, clientOut, upstreamConfig);
            } else if (firstByte == 'C' || firstByte == 'c') {
                handleHttpConnectProtocol(clientSocket, clientIn, clientOut, (char) firstByte, upstreamConfig);
            } else if (firstByte == 'G' || firstByte == 'P' || firstByte == 'H' || firstByte == 'D' || firstByte == 'O') {
                handlePlainHttpProtocol(clientSocket, clientIn, clientOut, (char) firstByte, upstreamConfig);
            } else {
                Log.w(TAG, "Unknown protocol header byte from client: 0x" + Integer.toHexString(firstByte));
                closeQuietly(clientSocket);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error handling relay client connection", t);
            closeQuietly(clientSocket);
        }
    }

    private static void handleTlsClientHelloProtocol(Socket clientSocket, InputStream clientIn, OutputStream clientOut, byte firstByte, ProxyConfig upstreamConfig) throws IOException {
        byte[] recordHeader = new byte[5];
        recordHeader[0] = firstByte;
        readFully(clientIn, recordHeader, 1, 4);

        int recordLength = ((recordHeader[3] & 0xFF) << 8) | (recordHeader[4] & 0xFF);
        if (recordLength <= 0 || recordLength > 65535) {
            Log.e(TAG, "Invalid TLS record length: " + recordLength);
            closeQuietly(clientSocket);
            return;
        }

        byte[] payload = new byte[recordLength];
        readFully(clientIn, payload, 0, recordLength);

        byte[] fullClientHello = new byte[5 + recordLength];
        System.arraycopy(recordHeader, 0, fullClientHello, 0, 5);
        System.arraycopy(payload, 0, fullClientHello, 5, recordLength);

        String sni = extractSniFromClientHello(fullClientHello);
        Log.i(TAG, "Direct TLS connection with SNI: " + sni);

        String targetHost = (sni != null && !sni.isEmpty()) ? sni : "www.youtube.com";
        int targetPort = 443;

        Socket upstreamSocket = connectToUpstreamSocks5(upstreamConfig, targetHost, targetPort);
        if (upstreamSocket == null) {
            Log.e(TAG, "Failed to connect to upstream SOCKS5 for TLS target: " + targetHost);
            closeQuietly(clientSocket);
            return;
        }

        OutputStream upOut = upstreamSocket.getOutputStream();
        upOut.write(fullClientHello);
        upOut.flush();

        Log.i(TAG, "TLS Tunnel active to " + targetHost + ":" + targetPort);

        clientSocket.setSoTimeout(0);
        upstreamSocket.setSoTimeout(0);

        pipeSockets(clientSocket, upstreamSocket);
    }

    private static String extractSniFromClientHello(byte[] data) {
        try {
            if (data == null || data.length < 5 || (data[0] & 0xFF) != 0x16) {
                return null;
            }
            if (data.length < 6 || (data[5] & 0xFF) != 0x01) {
                return null;
            }
            int pos = 5 + 4;
            pos += 2;
            pos += 32;
            if (pos >= data.length) return null;

            int sessionIdLen = data[pos] & 0xFF;
            pos += 1 + sessionIdLen;
            if (pos + 2 > data.length) return null;

            int cipherSuitesLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2 + cipherSuitesLen;
            if (pos >= data.length) return null;

            int compMethodsLen = data[pos] & 0xFF;
            pos += 1 + compMethodsLen;
            if (pos + 2 > data.length) return null;

            int extTotalLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            int extEnd = Math.min(data.length, pos + extTotalLen);

            while (pos + 4 <= extEnd) {
                int extType = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                int extLen = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                pos += 4;
                if (extType == 0) {
                    if (pos + 2 <= extEnd) {
                        int listLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                        int sniPos = pos + 2;
                        if (sniPos + 3 <= extEnd) {
                            int nameType = data[sniPos] & 0xFF;
                            int nameLen = ((data[sniPos + 1] & 0xFF) << 8) | (data[sniPos + 2] & 0xFF);
                            int nameStart = sniPos + 3;
                            if (nameType == 0 && nameStart + nameLen <= extEnd) {
                                return new String(data, nameStart, nameLen, "UTF-8");
                            }
                        }
                    }
                }
                pos += extLen;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to parse SNI", t);
        }
        return null;
    }

    private static void handleHttpConnectProtocol(Socket clientSocket, InputStream clientIn, OutputStream clientOut, char firstChar, ProxyConfig upstreamConfig) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(firstChar);
        int b;
        while ((b = clientIn.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        String line = sb.toString();
        Log.i(TAG, "HTTP Proxy CONNECT request: " + line);

        while (true) {
            String hLine = readAsciiLine(clientIn);
            if (hLine == null || hLine.isEmpty()) break;
        }

        String[] parts = line.split(" ");
        if (parts.length < 2) {
            clientOut.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes("UTF-8"));
            clientOut.flush();
            closeQuietly(clientSocket);
            return;
        }

        String hostPort = parts[1];
        String targetHost = hostPort;
        int targetPort = 443;
        int colon = hostPort.indexOf(':');
        if (colon != -1) {
            targetHost = hostPort.substring(0, colon);
            try {
                targetPort = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (Exception ignored) {}
        }

        Socket upstreamSocket = connectToUpstreamSocks5(upstreamConfig, targetHost, targetPort);
        if (upstreamSocket == null) {
            clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes("UTF-8"));
            clientOut.flush();
            closeQuietly(clientSocket);
            return;
        }

        clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes("UTF-8"));
        clientOut.flush();

        Log.i(TAG, "HTTP CONNECT Tunnel active to " + targetHost + ":" + targetPort);

        clientSocket.setSoTimeout(0);
        upstreamSocket.setSoTimeout(0);

        pipeSockets(clientSocket, upstreamSocket);
    }

    private static void handlePlainHttpProtocol(Socket clientSocket, InputStream clientIn, OutputStream clientOut, char firstChar, ProxyConfig upstreamConfig) throws IOException {
        StringBuilder headerBuilder = new StringBuilder();
        headerBuilder.append(firstChar);
        int b;
        while ((b = clientIn.read()) != -1) {
            headerBuilder.append((char) b);
            if (b == '\n') break;
        }
        String firstLine = headerBuilder.toString().trim();
        Log.i(TAG, "Plain HTTP request: " + firstLine);

        StringBuilder allHeaders = new StringBuilder();
        allHeaders.append(headerBuilder.toString());
        String hostHeader = null;

        while (true) {
            String line = readAsciiLine(clientIn);
            if (line == null || line.isEmpty()) {
                allHeaders.append("\r\n");
                break;
            }
            allHeaders.append(line).append("\r\n");
            if (line.toLowerCase().startsWith("host:")) {
                hostHeader = line.substring(5).trim();
            }
        }

        String targetHost = null;
        int targetPort = 80;

        String[] parts = firstLine.split(" ");
        if (parts.length >= 2 && parts[1].startsWith("http://")) {
            try {
                Uri uri = Uri.parse(parts[1]);
                targetHost = uri.getHost();
                if (uri.getPort() != -1) {
                    targetPort = uri.getPort();
                }
            } catch (Exception ignored) {}
        }

        if (targetHost == null && hostHeader != null) {
            int colon = hostHeader.indexOf(':');
            if (colon != -1) {
                targetHost = hostHeader.substring(0, colon);
                try {
                    targetPort = Integer.parseInt(hostHeader.substring(colon + 1));
                } catch (Exception ignored) {}
            } else {
                targetHost = hostHeader;
            }
        }

        if (targetHost == null) {
            clientOut.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes("UTF-8"));
            clientOut.flush();
            closeQuietly(clientSocket);
            return;
        }

        Socket upstreamSocket = connectToUpstreamSocks5(upstreamConfig, targetHost, targetPort);
        if (upstreamSocket == null) {
            clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes("UTF-8"));
            clientOut.flush();
            closeQuietly(clientSocket);
            return;
        }

        OutputStream upOut = upstreamSocket.getOutputStream();
        upOut.write(allHeaders.toString().getBytes("UTF-8"));
        upOut.flush();

        clientSocket.setSoTimeout(0);
        upstreamSocket.setSoTimeout(0);

        pipeSockets(clientSocket, upstreamSocket);
    }

    private static void handleSocks5Protocol(Socket clientSocket, InputStream clientIn, OutputStream clientOut, ProxyConfig upstreamConfig) throws IOException {
        int nmethods = clientIn.read();
        if (nmethods <= 0) {
            closeQuietly(clientSocket);
            return;
        }
        byte[] methods = new byte[nmethods];
        readFully(clientIn, methods, 0, nmethods);

        clientOut.write(new byte[]{0x05, 0x00});
        clientOut.flush();

        int reqVer = clientIn.read();
        int cmd = clientIn.read();
        int rsv = clientIn.read();
        int atyp = clientIn.read();

        if (reqVer != 5 || cmd != 1) {
            clientOut.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            clientOut.flush();
            closeQuietly(clientSocket);
            return;
        }

        String targetName = "";
        byte[] targetAddrBytes;
        if (atyp == 1) {
            targetAddrBytes = new byte[4];
            readFully(clientIn, targetAddrBytes, 0, 4);
            targetName = InetAddress.getByAddress(targetAddrBytes).getHostAddress();
        } else if (atyp == 3) {
            int domainLen = clientIn.read();
            targetAddrBytes = new byte[1 + domainLen];
            targetAddrBytes[0] = (byte) domainLen;
            readFully(clientIn, targetAddrBytes, 1, domainLen);
            targetName = new String(targetAddrBytes, 1, domainLen, "UTF-8");
        } else if (atyp == 4) {
            targetAddrBytes = new byte[16];
            readFully(clientIn, targetAddrBytes, 0, 16);
            targetName = InetAddress.getByAddress(targetAddrBytes).getHostAddress();
        } else {
            closeQuietly(clientSocket);
            return;
        }

        byte[] portBytes = new byte[2];
        readFully(clientIn, portBytes, 0, 2);
        int targetPort = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);

        Log.i(TAG, "SOCKS5 client requested: " + targetName + ":" + targetPort);

        Socket upstreamSocket = connectToUpstreamSocks5(upstreamConfig, targetName, targetPort);
        if (upstreamSocket == null) {
            clientOut.write(new byte[]{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            clientOut.flush();
            closeQuietly(clientSocket);
            return;
        }

        byte[] successResp = new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
        clientOut.write(successResp);
        clientOut.flush();

        Log.i(TAG, "SOCKS5 Tunnel active to " + targetName + ":" + targetPort);

        clientSocket.setSoTimeout(0);
        upstreamSocket.setSoTimeout(0);

        pipeSockets(clientSocket, upstreamSocket);
    }

    private static Socket connectToUpstreamSocks5(ProxyConfig upstreamConfig, String targetHost, int targetPort) {
        Socket upstreamSocket = null;
        try {
            Log.i(TAG, "Connecting to upstream SOCKS5 " + upstreamConfig.host + ":" + upstreamConfig.port + " for target " + targetHost + ":" + targetPort);
            upstreamSocket = new Socket();
            upstreamSocket.setTcpNoDelay(true);
            upstreamSocket.setSoTimeout(15000);
            upstreamSocket.connect(new InetSocketAddress(upstreamConfig.host, upstreamConfig.port), 10000);

            InputStream upIn = upstreamSocket.getInputStream();
            OutputStream upOut = upstreamSocket.getOutputStream();

            boolean hasAuth = (upstreamConfig.username != null && !upstreamConfig.username.isEmpty());
            if (hasAuth) {
                upOut.write(new byte[]{0x05, 0x02, 0x00, 0x02});
            } else {
                upOut.write(new byte[]{0x05, 0x01, 0x00});
            }
            upOut.flush();

            int upVer = upIn.read();
            int upMethod = upIn.read();
            if (upVer != 5 || upMethod == 0xFF) {
                Log.e(TAG, "Upstream SOCKS5 rejected methods. ver=" + upVer + " method=" + upMethod);
                closeQuietly(upstreamSocket);
                return null;
            }

            if (upMethod == 2 && hasAuth) {
                byte[] userBytes = upstreamConfig.username.getBytes("UTF-8");
                byte[] passBytes = (upstreamConfig.password != null ? upstreamConfig.password : "").getBytes("UTF-8");
                byte[] authPacket = new byte[3 + userBytes.length + passBytes.length];
                authPacket[0] = 0x01;
                authPacket[1] = (byte) userBytes.length;
                System.arraycopy(userBytes, 0, authPacket, 2, userBytes.length);
                int passOffset = 2 + userBytes.length;
                authPacket[passOffset] = (byte) passBytes.length;
                System.arraycopy(passBytes, 0, authPacket, passOffset + 1, passBytes.length);

                upOut.write(authPacket);
                upOut.flush();

                int authVer = upIn.read();
                int authStatus = upIn.read();
                if (authStatus != 0) {
                    Log.e(TAG, "Upstream SOCKS5 auth failed, status=" + authStatus);
                    closeQuietly(upstreamSocket);
                    return null;
                }
            }

            byte[] targetBytes = targetHost.getBytes("UTF-8");
            byte[] req = new byte[4 + 1 + targetBytes.length + 2];
            req[0] = 0x05;
            req[1] = 0x01;
            req[2] = 0x00;
            req[3] = 0x03;
            req[4] = (byte) targetBytes.length;
            System.arraycopy(targetBytes, 0, req, 5, targetBytes.length);
            int portOffset = 5 + targetBytes.length;
            req[portOffset] = (byte) ((targetPort >> 8) & 0xFF);
            req[portOffset + 1] = (byte) (targetPort & 0xFF);

            upOut.write(req);
            upOut.flush();

            int respVer = upIn.read();
            int rep = upIn.read();
            int respRsv = upIn.read();
            int respAtyp = upIn.read();

            if (respVer != 5 || rep != 0) {
                Log.e(TAG, "Upstream SOCKS5 CONNECT rejected with rep=" + rep);
                closeQuietly(upstreamSocket);
                return null;
            }

            if (respAtyp == 1) {
                byte[] b = new byte[4];
                readFully(upIn, b, 0, 4);
            } else if (respAtyp == 3) {
                int dLen = upIn.read();
                byte[] b = new byte[dLen];
                readFully(upIn, b, 0, dLen);
            } else if (respAtyp == 4) {
                byte[] b = new byte[16];
                readFully(upIn, b, 0, 16);
            }
            byte[] pBuf = new byte[2];
            readFully(upIn, pBuf, 0, 2);

            return upstreamSocket;
        } catch (Throwable t) {
            Log.e(TAG, "Failed connecting to upstream SOCKS5 for " + targetHost + ":" + targetPort, t);
            closeQuietly(upstreamSocket);
            return null;
        }
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        return sb.toString();
    }

    private static void pipeSockets(Socket s1, Socket s2) {
        Thread t1 = new Thread(new PipeRunnable(s1, s2), "YTTV-Pipe1");
        t1.setDaemon(true);
        t1.start();

        Thread t2 = new Thread(new PipeRunnable(s2, s1), "YTTV-Pipe2");
        t2.setDaemon(true);
        t2.start();
    }

    private static class PipeRunnable implements Runnable {
        private final Socket fromSocket;
        private final Socket toSocket;
        PipeRunnable(Socket fromSocket, Socket toSocket) {
            this.fromSocket = fromSocket;
            this.toSocket = toSocket;
        }
        @Override
        public void run() {
            try {
                InputStream in = fromSocket.getInputStream();
                OutputStream out = toSocket.getOutputStream();
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
                try {
                    toSocket.shutdownOutput();
                } catch (Exception ignored) {}
            } catch (Exception ignored) {
                closeQuietly(fromSocket);
                closeQuietly(toSocket);
            }
        }
    }

    private static void readFully(InputStream in, byte[] buf, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int r = in.read(buf, offset + total, length - total);
            if (r == -1) {
                throw new EOFException("Premature EOF");
            }
            total += r;
        }
    }

    private static class WebServerNotifyRunnable implements Runnable {
        private final Context context;
        WebServerNotifyRunnable(Context context) {
            this.context = context;
        }
        @Override
        public void run() {
            try {
                String ip = getLocalIpAddress();
                if (!"127.0.0.1".equals(ip) && !"0.0.0.0".equals(ip)) {
                    Toast.makeText(context.getApplicationContext(), "⚡ YTTV Mod: Web-настройки http://" + ip + ":" + WEB_SERVER_PORT, Toast.LENGTH_LONG).show();
                }
            } catch (Throwable ignored) {}
        }
    }

    public static synchronized void startWebServer(final Context context) {
        if (sWebServerRunning || context == null) {
            return;
        }
        sWebServerRunning = true;
        Thread serverThread = new Thread(new WebServerRunnable(context), "YTTV-ProxyWebServer");
        serverThread.setDaemon(true);
        serverThread.start();

        new Handler(Looper.getMainLooper()).postDelayed(new WebServerNotifyRunnable(context), 3000);
    }

    private static class WebServerRunnable implements Runnable {
        private final Context context;
        WebServerRunnable(Context context) {
            this.context = context;
        }
        @Override
        public void run() {
            try {
                sWebServerSocket = new ServerSocket(WEB_SERVER_PORT, 50, InetAddress.getByName("0.0.0.0"));
                Log.i(TAG, "Proxy web configuration server running on 0.0.0.0:" + WEB_SERVER_PORT);
                while (!Thread.currentThread().isInterrupted() && !sWebServerSocket.isClosed()) {
                    Socket socket = sWebServerSocket.accept();
                    Thread clientThread = new Thread(new HttpClientRunnable(context, socket), "YTTV-HttpClient");
                    clientThread.setDaemon(true);
                    clientThread.start();
                }
            } catch (Throwable t) {
                if (sWebServerSocket != null && !sWebServerSocket.isClosed()) {
                    Log.e(TAG, "Web server error", t);
                }
            } finally {
                sWebServerRunning = false;
            }
        }
    }

    private static class HttpClientRunnable implements Runnable {
        private final Context context;
        private final Socket socket;
        HttpClientRunnable(Context context, Socket socket) {
            this.context = context;
            this.socket = socket;
        }
        @Override
        public void run() {
            handleHttpClient(context, socket);
        }
    }

    private static void handleHttpClient(final Context context, Socket socket) {
        try {
            socket.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String firstLine = in.readLine();
            if (firstLine == null) {
                socket.close();
                return;
            }

            String[] parts = firstLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";

            int contentLength = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (Exception ignored) {}
                }
            }

            String body = "";
            if ("POST".equalsIgnoreCase(method) && contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int r = in.read(buf, read, contentLength - read);
                    if (r == -1) break;
                    read += r;
                }
                body = new String(buf, 0, read);
            }

            // Handle CORS preflight
            if ("OPTIONS".equalsIgnoreCase(method)) {
                String headers = "HTTP/1.1 200 OK\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: *\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n";
                OutputStream out = socket.getOutputStream();
                out.write(headers.getBytes("UTF-8"));
                out.flush();
                socket.close();
                return;
            }

            // Handle SponsorBlock API proxy
            if (path.startsWith("/api/sponsorblock")) {
                String vid = extractParam(path, "v");
                String json = fetchSponsorBlockSegments(vid);
                byte[] jsonBytes = json.getBytes("UTF-8");
                String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=UTF-8\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Length: " + jsonBytes.length + "\r\n" +
                        "Connection: close\r\n\r\n";
                OutputStream out = socket.getOutputStream();
                out.write(headers.getBytes("UTF-8"));
                out.write(jsonBytes);
                out.flush();
                socket.close();
                return;
            }

            // Handle Config API
            if (path.startsWith("/api/config")) {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                JSONObject cfg = new JSONObject();
                cfg.put("proxy_url", prefs.getString(KEY_PROXY_URL, ""));
                cfg.put("proxy_enabled", prefs.getBoolean(KEY_PROXY_ENABLED, true));
                cfg.put("sb_enabled", prefs.getBoolean(KEY_SB_ENABLED, true));
                cfg.put("sb_categories", getSbCategoriesArray(prefs));
                cfg.put("quality", prefs.getString(KEY_PREFERRED_QUALITY, "auto"));
                cfg.put("speed", (double) prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f));
                cfg.put("color_keys", prefs.getBoolean(KEY_COLOR_KEYS_ENABLED, true));
                byte[] b = cfg.toString().getBytes("UTF-8");
                String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=UTF-8\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Length: " + b.length + "\r\n" +
                        "Connection: close\r\n\r\n";
                OutputStream out = socket.getOutputStream();
                out.write(headers.getBytes("UTF-8"));
                out.write(b);
                out.flush();
                socket.close();
                return;
            }

            if (path.startsWith("/api/eval")) {
                String js = extractParam(path, "js");
                if ((js == null || js.isEmpty()) && !body.isEmpty()) {
                    js = body;
                }
                if (js != null && !js.isEmpty()) {
                    try {
                        if (js.startsWith("js=")) js = js.substring(3);
                        js = URLDecoder.decode(js, "UTF-8");
                    } catch(Exception ignored) {}
                    evaluateJs(js);
                }
                byte[] b = "{\"status\":\"ok\"}".getBytes("UTF-8");
                String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=UTF-8\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Length: " + b.length + "\r\n" +
                        "Connection: close\r\n\r\n";
                OutputStream out = socket.getOutputStream();
                out.write(headers.getBytes("UTF-8"));
                out.write(b);
                out.flush();
                socket.close();
                return;
            }

            if (path.startsWith("/api/log")) {
                String msg = body.isEmpty() ? path : body;
                try { msg = URLDecoder.decode(msg, "UTF-8"); } catch(Exception ignored) {}
                Log.i("YTTV_DOM", msg);
                byte[] b = "{\"status\":\"ok\"}".getBytes("UTF-8");
                String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=UTF-8\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Length: " + b.length + "\r\n" +
                        "Connection: close\r\n\r\n";
                OutputStream out = socket.getOutputStream();
                out.write(headers.getBytes("UTF-8"));
                out.write(b);
                out.flush();
                socket.close();
                return;
            }

            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean restartNeeded = false;
            String responseHtml;

            if (path.startsWith("/save_mods") || ("POST".equalsIgnoreCase(method) && path.startsWith("/save_mods"))) {
                String payload = body.isEmpty() ? path : body;
                boolean sb = "1".equals(extractParam(payload, "sb_enabled"));
                String q = extractParam(payload, "preferred_quality");
                if (q == null) q = "auto";
                String spStr = extractParam(payload, "playback_speed");
                float sp = 1.0f;
                try { if (spStr != null) sp = Float.parseFloat(spStr); } catch(Exception ignored) {}
                boolean ck = "1".equals(extractParam(payload, "color_keys_enabled"));

                StringBuilder catBuilder = new StringBuilder();
                for (String cat : ALL_SB_CATEGORIES) {
                    if ("1".equals(extractParam(payload, "cat_" + cat))) {
                        if (catBuilder.length() > 0) catBuilder.append(",");
                        catBuilder.append(cat);
                    }
                }
                String newCats = catBuilder.toString();
                String rawCats = extractParam(payload, "sb_categories");
                if (rawCats != null && !rawCats.isEmpty()) {
                    newCats = rawCats;
                }

                SharedPreferences.Editor editor = prefs.edit()
                        .putBoolean(KEY_SB_ENABLED, sb)
                        .putString(KEY_PREFERRED_QUALITY, q)
                        .putFloat(KEY_PLAYBACK_SPEED, sp)
                        .putBoolean(KEY_COLOR_KEYS_ENABLED, ck);
                if (payload.contains("cat_") || (rawCats != null && !rawCats.isEmpty())) {
                    editor.putString(KEY_SB_CATEGORIES, newCats);
                }
                editor.apply();

                updateWebClientConfig(prefs);
                responseHtml = getHtmlPage(context, "&#9989; &#1053;&#1072;&#1089;&#1090;&#1088;&#1086;&#1081;&#1082;&#1080; &#1087;&#1088;&#1080;&#1084;&#1077;&#1085;&#1077;&#1085;&#1099; &#1085;&#1072; &#1058;&#1042; &#1073;&#1077;&#1079; &#1087;&#1077;&#1088;&#1077;&#1079;&#1072;&#1087;&#1091;&#1089;&#1082;&#1072;!");
            } else if (path.startsWith("/save") || ("POST".equalsIgnoreCase(method) && path.equals("/"))) {
                String newProxy = extractParam(body.isEmpty() ? path : body, "proxy_url");
                String enabledStr = extractParam(body.isEmpty() ? path : body, "enabled");
                boolean enabled = !"0".equals(enabledStr) && !"false".equalsIgnoreCase(enabledStr);

                if (newProxy != null) {
                    newProxy = URLDecoder.decode(newProxy, "UTF-8").trim();
                    prefs.edit()
                            .putString(KEY_PROXY_URL, newProxy)
                            .putBoolean(KEY_PROXY_ENABLED, enabled)
                            .apply();
                    writeExternalProxyFile(newProxy);
                    restartNeeded = true;
                }
                responseHtml = getHtmlPage(context, "&#9989; &#1055;&#1088;&#1086;&#1082;&#1089;&#1080; &#1089;&#1086;&#1093;&#1088;&#1072;&#1085;&#1077;&#1085;! &#1055;&#1077;&#1088;&#1077;&#1079;&#1072;&#1087;&#1091;&#1089;&#1082; YouTube TV...");
            } else if (path.startsWith("/disable")) {
                prefs.edit().putBoolean(KEY_PROXY_ENABLED, false).apply();
                writeExternalProxyFile("");
                restartNeeded = true;
                responseHtml = getHtmlPage(context, "&#10060; &#1055;&#1088;&#1086;&#1082;&#1089;&#1080; &#1086;&#1090;&#1082;&#1083;&#1102;&#1095;&#1077;&#1085;! &#1055;&#1077;&#1088;&#1077;&#1079;&#1072;&#1087;&#1091;&#1089;&#1082; YouTube TV...");
            } else {
                responseHtml = getHtmlPage(context, null);
            }

            OutputStream out = socket.getOutputStream();
            byte[] bytes = responseHtml.getBytes("UTF-8");
            String headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Content-Length: " + bytes.length + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(headers.getBytes("UTF-8"));
            out.write(bytes);
            out.flush();
            socket.close();

            if (restartNeeded) {
                new Handler(Looper.getMainLooper()).postDelayed(new RestartRunnable(context), 600);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error handling HTTP client", t);
        }
    }

    private static class RestartRunnable implements Runnable {
        private final Context context;
        RestartRunnable(Context context) {
            this.context = context;
        }
        @Override
        public void run() {
            restartApp(context);
        }
    }

    private static class KillProcessRunnable implements Runnable {
        @Override
        public void run() {
            Process.killProcess(Process.myPid());
            System.exit(0);
        }
    }

    private static String extractParam(String queryOrBody, String paramName) {
        if (queryOrBody == null) return null;
        int qIdx = queryOrBody.indexOf('?');
        if (qIdx != -1) {
            queryOrBody = queryOrBody.substring(qIdx + 1);
        }
        String[] pairs = queryOrBody.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].trim().equals(paramName)) {
                return kv[1];
            }
        }
        return null;
    }

    public static String buildModScript(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean sbEnabled = prefs.getBoolean(KEY_SB_ENABLED, true);
        String sbCatsJson = getSbCategoriesJson(context);
        String quality = prefs.getString(KEY_PREFERRED_QUALITY, "auto");
        float speed = prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f);

        return "(function() {\n" +
                "    function getPlayer() {\n" +
                "        return document.getElementById('ytlr-player__player-container-player') ||\n" +
                "               document.querySelector('.html5-video-player') ||\n" +
                "               document.getElementById('movie_player');\n" +
                "    }\n" +
                "\n" +
                "    if (window.__yttv_mod_installed) {\n" +
                "        if (window.__yttv_update_config) {\n" +
                "            window.__yttv_update_config({\n" +
                "                sbEnabled: " + sbEnabled + ",\n" +
                "                sbCategories: " + sbCatsJson + ",\n" +
                "                quality: \"" + quality + "\",\n" +
                "                speed: " + speed + "\n" +
                "            });\n" +
                "        }\n" +
                "        return;\n" +
                "    }\n" +
                "    window.__yttv_mod_installed = true;\n" +
                "    window.__yttv_config = {\n" +
                "        sbEnabled: " + sbEnabled + ",\n" +
                "        sbCategories: " + sbCatsJson + ",\n" +
                "        quality: \"" + quality + "\",\n" +
                "        speed: " + speed + "\n" +
                "    };\n" +
                "\n" +
                "    /* 1. OSD BANNER */\n" +
                "    var osd = document.createElement('div');\n" +
                "    osd.id = '__yttv_osd';\n" +
                "    osd.style.cssText = 'position:fixed;top:32px;right:40px;z-index:9999999;background:rgba(20,20,20,0.92);color:#ffffff;border-left:5px solid #00e5ff;padding:12px 24px;border-radius:8px;font-size:20px;font-weight:600;font-family:-apple-system,BlinkMacSystemFont,sans-serif;box-shadow:0 8px 24px rgba(0,0,0,0.7);display:none;pointer-events:none;transition:opacity 0.25s ease;';\n" +
                "    document.documentElement.appendChild(osd);\n" +
                "    var osdTimer = null;\n" +
                "    function showOsd(msg, color, duration) {\n" +
                "        try {\n" +
                "            osd.innerHTML = msg;\n" +
                "            osd.style.borderLeftColor = color || '#00e5ff';\n" +
                "            osd.style.display = 'block';\n" +
                "            osd.style.opacity = '1';\n" +
                "            if (osdTimer) clearTimeout(osdTimer);\n" +
                "            osdTimer = setTimeout(function() {\n" +
                "                osd.style.opacity = '0';\n" +
                "                setTimeout(function() { osd.style.display = 'none'; }, 300);\n" +
                "            }, duration || 2500);\n" +
                "        } catch(e) {}\n" +
                "    }\n" +
                "    window.__yttv_show_osd = showOsd;\n" +
                "\n" +
                "    /* 2. AD BLOCKER */\n" +
                "    function stripAds(o) {\n" +
                "        if (!o || typeof o !== 'object') return o;\n" +
                "        try {\n" +
                "            delete o.adPlacements; delete o.adSlots; delete o.playerAds;\n" +
                "            delete o.adBreakHeartbeatParams; delete o.adLayoutLoggingData; delete o.adPlacementConfig;\n" +
                "        } catch(e) {}\n" +
                "        return o;\n" +
                "    }\n" +
                "    try {\n" +
                "        var origParse = JSON.parse;\n" +
                "        JSON.parse = function() {\n" +
                "            var r = origParse.apply(this, arguments);\n" +
                "            if (r && typeof r === 'object') {\n" +
                "                stripAds(r);\n" +
                "            }\n" +
                "            return r;\n" +
                "        };\n" +
                "    } catch(e) {}\n" +
                "\n" +
                "    /* 3. STYLES */\n" +
                "    var styleEl = document.createElement('style');\n" +
                "    styleEl.id = '__yttv_styles';\n" +
                "    styleEl.textContent = '.ytp-ad-overlay-container, .ytp-ad-message-container { display: none !important; }';\n" +
                "    document.documentElement.appendChild(styleEl);\n" +
                "\n" +
                "    /* 4. QUALITY CONTROL */\n" +
                "    function applyQuality() {\n" +
                "        try {\n" +
                "            var p = getPlayer();\n" +
                "            if (!p) return;\n" +
                "            var pref = window.__yttv_config.quality;\n" +
                "            if (pref === 'auto') {\n" +
                "                if (p.setPlaybackQualityRange) p.setPlaybackQualityRange('auto', 'auto');\n" +
                "                return;\n" +
                "            }\n" +
                "            var target = 'hd' + pref;\n" +
                "            var avail = (p.getAvailableQualityLevels && p.getAvailableQualityLevels()) || [];\n" +
                "            var chosen = target;\n" +
                "            if (avail.length > 0 && avail.indexOf(target) === -1) {\n" +
                "                var order = ['hd2160', 'hd1440', 'hd1080', 'hd720', 'large', 'medium', 'small', 'tiny'];\n" +
                "                var idx = order.indexOf(target);\n" +
                "                if (idx !== -1) {\n" +
                "                    for (var k = idx; k < order.length; k++) {\n" +
                "                        if (avail.indexOf(order[k]) !== -1) { chosen = order[k]; break; }\n" +
                "                    }\n" +
                "                }\n" +
                "            }\n" +
                "            if (p.setPlaybackQualityRange) p.setPlaybackQualityRange(chosen, chosen);\n" +
                "            if (p.setPlaybackQuality) p.setPlaybackQuality(chosen);\n" +
                "        } catch(e) {}\n" +
                "    }\n" +
                "\n" +
                "    /* 5. SPEED CONTROL */\n" +
                "    function applySpeed() {\n" +
                "        try {\n" +
                "            var speed = parseFloat(window.__yttv_config.speed) || 1.0;\n" +
                "            var ad = document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay, [class*=\"ad-showing\"]');\n" +
                "            if (ad) return;\n" +
                "            var vids = document.getElementsByTagName('video');\n" +
                "            for (var i = 0; i < vids.length; i++) {\n" +
                "                if (vids[i].playbackRate !== speed) vids[i].playbackRate = speed;\n" +
                "            }\n" +
                "            var p = getPlayer();\n" +
                "            if (p && p.getPlaybackRate && p.setPlaybackRate) {\n" +
                "                if (p.getPlaybackRate() !== speed) p.setPlaybackRate(speed);\n" +
                "            }\n" +
                "        } catch(e) {}\n" +
                "    }\n" +
                "\n" +
                "    /* 6. SPONSORBLOCK (Java Native Bridge) */\n" +
                "    var currentVid = null;\n" +
                "    var segments = [];\n" +
                "    var rawSegments = [];\n" +
                "    var skippedUuids = {};\n" +
                "    var segmentsCache = {};\n" +
                "    var pendingFetches = {};\n" +
                "\n" +
                "    function checkVideo() {\n" +
                "        var p = getPlayer();\n" +
                "        var vid = null;\n" +
                "        if (p && typeof p.getVideoData === 'function') {\n" +
                "            try {\n" +
                "                var vd = p.getVideoData();\n" +
                "                if (vd && vd.video_id && typeof vd.video_id === 'string' && vd.video_id.length === 11) {\n" +
                "                    vid = vd.video_id;\n" +
                "                }\n" +
                "            } catch(e) {}\n" +
                "        }\n" +
                "        if (!vid && p && typeof p.getVideoUrl === 'function') {\n" +
                "            try {\n" +
                "                var u = p.getVideoUrl();\n" +
                "                var m = u && u.match(/[?&]v=([a-zA-Z0-9_-]{11})/);\n" +
                "                if (m) vid = m[1];\n" +
                "            } catch(e) {}\n" +
                "        }\n" +
                "        if (!vid) {\n" +
                "            var m2 = (location.hash || location.href).match(/[?&]v=([a-zA-Z0-9_-]{11})/);\n" +
                "            if (m2) vid = m2[1];\n" +
                "        }\n" +
                "\n" +
                "        var isWatch = (location.hash || location.href).indexOf('watch') !== -1;\n" +
                "        if (!isWatch && !vid) {\n" +
                "            if (currentVid !== null) {\n" +
                "                currentVid = null;\n" +
                "                segments = [];\n" +
                "                rawSegments = [];\n" +
                "                skippedUuids = {};\n" +
                "            }\n" +
                "            return;\n" +
                "        }\n" +
                "\n" +
                "        if (vid) {\n" +
                "            if (vid !== currentVid) {\n" +
                "                currentVid = vid;\n" +
                "                skippedUuids = {};\n" +
                "                applyQuality();\n" +
                "                if (segmentsCache[vid]) {\n" +
                "                    rawSegments = segmentsCache[vid];\n" +
                "                    filterSegments();\n" +
                "                    if (segments.length > 0) {\n" +
                "                        console.log('[YTTV Mod] Loaded ' + segments.length + ' SponsorBlock segments for ' + vid);\n" +
                "                        showOsd('🛡️ SponsorBlock: ' + segments.length + ' сегм.', '#00e676', 2500);\n" +
                "                    }\n" +
                "                } else if (!pendingFetches[vid]) {\n" +
                "                    pendingFetches[vid] = Date.now();\n" +
                "                    segments = [];\n" +
                "                    rawSegments = [];\n" +
                "                    document.title = 'YTTV_VID:' + vid + ':' + Date.now();\n" +
                "                } else if (Date.now() - pendingFetches[vid] > 5000) {\n" +
                "                    pendingFetches[vid] = Date.now();\n" +
                "                    document.title = 'YTTV_VID:' + vid + ':' + Date.now();\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "    window.__yttv_check_video = checkVideo;\n" +
                "\n" +
                "    function filterSegments() {\n" +
                "        segments = [];\n" +
                "        if (Array.isArray(rawSegments) && rawSegments.length > 0) {\n" +
                "            var cats = window.__yttv_config.sbCategories || [\"sponsor\",\"selfpromo\",\"interaction\",\"intro\",\"outro\",\"preview\",\"filler\",\"music_offtopic\"];\n" +
                "            for (var i = 0; i < rawSegments.length; i++) {\n" +
                "                var item = rawSegments[i];\n" +
                "                if (!item || !item.segment) continue;\n" +
                "                if (cats.length > 0 && cats.indexOf(item.category) === -1) continue;\n" +
                "                segments.push({\n" +
                "                    start: item.segment[0],\n" +
                "                    end: item.segment[1],\n" +
                "                    category: item.category,\n" +
                "                    uuid: item.UUID || (item.segment[0] + '_' + item.segment[1])\n" +
                "                });\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "\n" +
                "    window.__yttv_set_segments = function(vid, data) {\n" +
                "        if (!vid) return;\n" +
                "        segmentsCache[vid] = Array.isArray(data) ? data : [];\n" +
                "        if (vid === currentVid) {\n" +
                "            rawSegments = segmentsCache[vid];\n" +
                "            skippedUuids = {};\n" +
                "            filterSegments();\n" +
                "            if (segments.length > 0) {\n" +
                "                console.log('[YTTV Mod] Loaded ' + segments.length + ' SponsorBlock segments for ' + vid);\n" +
                "                showOsd('🛡️ SponsorBlock: ' + segments.length + ' сегм.', '#00e676', 2500);\n" +
                "            }\n" +
                "        }\n" +
                "    };\n" +
                "\n" +
                "    function checkSponsorBlock(curTime) {\n" +
                "        if (!window.__yttv_config.sbEnabled || segments.length === 0 || curTime < 0) return;\n" +
                "        var p = getPlayer();\n" +
                "        for (var i = 0; i < segments.length; i++) {\n" +
                "            var s = segments[i];\n" +
                "            if (skippedUuids[s.uuid]) continue;\n" +
                "            if (curTime >= (s.start - 0.15) && curTime < (s.end - 0.05)) {\n" +
                "                skippedUuids[s.uuid] = true;\n" +
                "                var skipTo = s.end;\n" +
                "                try {\n" +
                "                    if (p && typeof p.seekTo === 'function') p.seekTo(skipTo, true);\n" +
                "                } catch(e) {}\n" +
                "                try {\n" +
                "                    var vids = document.getElementsByTagName('video');\n" +
                "                    for (var vi = 0; vi < vids.length; vi++) {\n" +
                "                        if (isFinite(skipTo)) vids[vi].currentTime = skipTo;\n" +
                "                    }\n" +
                "                } catch(e) {}\n" +
                "\n" +
                "                var catLabel = 'Спонсор';\n" +
                "                if (s.category === 'selfpromo') catLabel = 'Самореклама';\n" +
                "                else if (s.category === 'interaction') catLabel = 'Подписка/Лайк';\n" +
                "                else if (s.category === 'intro') catLabel = 'Интро';\n" +
                "                else if (s.category === 'outro') catLabel = 'Титры';\n" +
                "                else if (s.category === 'preview') catLabel = 'Анонс';\n" +
                "                else if (s.category === 'filler') catLabel = 'Вода/Филлер';\n" +
                "                else if (s.category === 'music_offtopic') catLabel = 'Немузыкальная часть';\n" +
                "                var diff = Math.max(1, Math.round(s.end - s.start));\n" +
                "                console.log('[YTTV Mod] Skipped ' + s.category + ' (' + diff + 's) to ' + skipTo);\n" +
                "                showOsd('⏩ Пропущено: ' + catLabel + ' (' + diff + ' сек)', '#00e676', 3000);\n" +
                "                break;\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "\n" +
                "    /* 7. EXPOSED CONTROLS */\n" +
                "    window.__yttv_update_config = function(cfg) {\n" +
                "        if (!cfg) return;\n" +
                "        for (var k in cfg) window.__yttv_config[k] = cfg[k];\n" +
                "        applySpeed();\n" +
                "        applyQuality();\n" +
                "        filterSegments();\n" +
                "    };\n" +
                "    window.__yttv_set_speed = function(val) {\n" +
                "        window.__yttv_config.speed = val;\n" +
                "        applySpeed();\n" +
                "        showOsd('⚡ Скорость: ' + val + 'x', '#00e676', 2000);\n" +
                "    };\n" +
                "    window.__yttv_set_quality = function(val) {\n" +
                "        window.__yttv_config.quality = val;\n" +
                "        applyQuality();\n" +
                "        var ql = val === 'auto' ? 'Авто' : (val + 'p' + (val === '2160' ? ' 4K' : ''));\n" +
                "        showOsd('📺 Качество: ' + ql, '#00b0ff', 2000);\n" +
                "    };\n" +
                "    window.__yttv_toggle_sb = function(val) {\n" +
                "        window.__yttv_config.sbEnabled = val;\n" +
                "        showOsd('⏩ SponsorBlock: ' + (val ? 'ВКЛ' : 'ВЫКЛ'), '#ffd600', 2000);\n" +
                "    };\n" +
                "\n" +
                "    /* 8. MAIN TICKER (every 250ms) */\n" +
                "    setInterval(function() {\n" +
                "        try {\n" +
                "            var p = getPlayer();\n" +
                "            var vids = document.getElementsByTagName('video');\n" +
                "            var ad = document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay, [class*=\"ad-showing\"]');\n" +
                "            if (ad) {\n" +
                "                for (var i = 0; i < vids.length; i++) {\n" +
                "                    var v = vids[i];\n" +
                "                    v.playbackRate = 16.0;\n" +
                "                    v.muted = true;\n" +
                "                    if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration;\n" +
                "                }\n" +
                "                var btns = document.querySelectorAll('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .videoAdUiSkipButton, .ytp-skip-ad-button, [class*=\"skip-button\"], button[class*=\"skip\"]');\n" +
                "                for (var j = 0; j < btns.length; j++) btns[j].click();\n" +
                "            } else {\n" +
                "                applySpeed();\n" +
                "            }\n" +
                "\n" +
                "            checkVideo();\n" +
                "\n" +
                "            if (segments.length > 0) {\n" +
                "                var curTime = -1;\n" +
                "                if (p && typeof p.getCurrentTime === 'function') {\n" +
                "                    try { curTime = p.getCurrentTime(); } catch(e) {}\n" +
                "                }\n" +
                "                if ((curTime < 0 || isNaN(curTime)) && vids.length > 0) {\n" +
                "                    curTime = vids[0].currentTime;\n" +
                "                }\n" +
                "                if (curTime >= 0) {\n" +
                "                    checkSponsorBlock(curTime);\n" +
                "                }\n" +
                "            }\n" +
                "        } catch(e) {}\n" +
                "    }, 250);\n" +
                "    console.log('[YTTV Mod] Injected successfully');\n" +
                "})();";
    }

    public static synchronized String fetchSponsorBlockSegments(String videoId) {
        if (videoId == null || videoId.isEmpty()) {
            return "[]";
        }
        if (sSponsorBlockCache.containsKey(videoId)) {
            return sSponsorBlockCache.get(videoId);
        }
        String result = "[]";
        String[] endpoints = new String[]{
                "https://sponsor.ajay.app/api/skipSegments?videoID=" + videoId +
                        "&categories=%5B%22sponsor%22,%22selfpromo%22,%22interaction%22,%22intro%22,%22outro%22,%22preview%22,%22filler%22,%22music_offtopic%22%5D",
                "https://api.sponsor.ajay.app/api/skipSegments?videoID=" + videoId +
                        "&categories=%5B%22sponsor%22,%22selfpromo%22,%22interaction%22,%22intro%22,%22outro%22,%22preview%22,%22filler%22,%22music_offtopic%22%5D"
        };

        Proxy[] proxies;
        if (sProxyEnabled) {
            proxies = new Proxy[]{
                    new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT)),
                    Proxy.NO_PROXY
            };
        } else {
            proxies = new Proxy[]{Proxy.NO_PROXY};
        }

        boolean success = false;
        for (Proxy proxy : proxies) {
            if (success) break;
            for (String urlStr : endpoints) {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(urlStr);
                    conn = (HttpURLConnection) url.openConnection(proxy);
                    conn.setConnectTimeout(3500);
                    conn.setReadTimeout(3500);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (SmartHub; SMART-TV; U; Linux/SmartTV) AppleWebKit/538.1");
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                        br.close();
                        result = sb.toString();
                        success = true;
                        break;
                    } else if (code == 404) {
                        result = "[]";
                        success = true;
                        break;
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Throwable ignored) {}
                    }
                }
            }
        }

        sSponsorBlockCache.put(videoId, result);
        return result;
    }

    public static boolean handleDispatchKeyEvent(Activity activity, KeyEvent event) {
        if (event == null || activity == null) return false;
        if (sContext == null) sContext = activity.getApplicationContext();
        try {
            startWebServer(activity);
        } catch(Throwable ignored) {}
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        return handleKeyDown(activity, event.getKeyCode(), event);
    }

    public static boolean handleKeyDown(Activity activity, int keyCode, KeyEvent event) {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean colorKeysEnabled = prefs.getBoolean(KEY_COLOR_KEYS_ENABLED, true);

            // Remote Menu / Settings / Guide / Info buttons always open Settings Dialog
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == 176 /* KEYCODE_SETTINGS */ ||
                    keyCode == 172 /* KEYCODE_GUIDE */ || keyCode == 165 /* KEYCODE_INFO */) {
                showSettingsDialog(activity);
                return true;
            }

            // Color keys:
            if (colorKeysEnabled) {
                switch (keyCode) {
                    case 183: // KEYCODE_PROG_RED: Open Settings Dialog
                        showSettingsDialog(activity);
                        return true;

                    case 184: // KEYCODE_PROG_GREEN: Cycle Playback Speed
                        cyclePlaybackSpeed(activity);
                        return true;

                    case 185: // KEYCODE_PROG_YELLOW: Toggle SponsorBlock
                        toggleSponsorBlock(activity);
                        return true;

                    case 186: // KEYCODE_PROG_BLUE: Cycle Preferred Quality
                        cycleQuality(activity);
                        return true;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in handleKeyDown", t);
        }
        return false;
    }

    public static void cyclePlaybackSpeed(final Activity activity) {
        if (activity == null) return;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        float cur = prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f);
        float next;
        if (Math.abs(cur - 1.0f) < 0.05f) next = 1.25f;
        else if (Math.abs(cur - 1.25f) < 0.05f) next = 1.5f;
        else if (Math.abs(cur - 1.5f) < 0.05f) next = 1.75f;
        else if (Math.abs(cur - 1.75f) < 0.05f) next = 2.0f;
        else next = 1.0f;

        prefs.edit().putFloat(KEY_PLAYBACK_SPEED, next).apply();
        evaluateJs("if(window.__yttv_set_speed) window.__yttv_set_speed(" + next + ");");
        Toast.makeText(activity, "⚡ Скорость: " + next + "x", Toast.LENGTH_SHORT).show();
    }

    public static void toggleSponsorBlock(final Activity activity) {
        if (activity == null) return;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean cur = prefs.getBoolean(KEY_SB_ENABLED, true);
        boolean next = !cur;

        prefs.edit().putBoolean(KEY_SB_ENABLED, next).apply();
        evaluateJs("if(window.__yttv_toggle_sb) window.__yttv_toggle_sb(" + next + ");");
        Toast.makeText(activity, "⏩ SponsorBlock: " + (next ? "ВКЛ" : "ВЫКЛ"), Toast.LENGTH_SHORT).show();
    }

    public static void cycleQuality(final Activity activity) {
        if (activity == null) return;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cur = prefs.getString(KEY_PREFERRED_QUALITY, "auto");
        String next;
        String label;
        if ("auto".equals(cur)) {
            next = "1080";
            label = "1080p FHD";
        } else if ("1080".equals(cur)) {
            next = "1440";
            label = "1440p 2K";
        } else if ("1440".equals(cur)) {
            next = "2160";
            label = "2160p 4K";
        } else if ("2160".equals(cur)) {
            next = "720";
            label = "720p HD";
        } else {
            next = "auto";
            label = "Авто";
        }

        prefs.edit().putString(KEY_PREFERRED_QUALITY, next).apply();
        evaluateJs("if(window.__yttv_set_quality) window.__yttv_set_quality('" + next + "');");
        Toast.makeText(activity, "📺 Качество: " + label, Toast.LENGTH_SHORT).show();
    }

    private static class SettingsDialogRunnable implements Runnable, DialogInterface.OnClickListener {
        private final Activity activity;
        SettingsDialogRunnable(Activity activity) {
            this.activity = activity;
        }
        @Override
        public void run() {
            try {
                final SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String currentProxy = prefs.getString(KEY_PROXY_URL, "");
                boolean proxyEn = prefs.getBoolean(KEY_PROXY_ENABLED, true);
                boolean sbEn = prefs.getBoolean(KEY_SB_ENABLED, true);
                String quality = prefs.getString(KEY_PREFERRED_QUALITY, "auto");
                float speed = prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f);
                boolean colorKeys = prefs.getBoolean(KEY_COLOR_KEYS_ENABLED, true);
                String tvIp = getLocalIpAddress();
                int catCount = getSbCategoriesSet(activity).size();

                AlertDialog.Builder builder = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
                builder.setTitle("⚙ Настройки YouTube TV Mod");

                String proxyLabel = (sProxyEnabled && proxyEn && !currentProxy.isEmpty()) ? "ВКЛ" : "ВЫКЛ";
                String qLabel = "auto".equals(quality) ? "Авто" : (quality + "p" + ("2160".equals(quality) ? " 4K" : ""));

                final String[] items = new String[] {
                        "⚡ SOCKS5 Прокси: [" + proxyLabel + "]",
                        "⏩ SponsorBlock: [" + (sbEn ? "ВКЛ" : "ВЫКЛ") + "]",
                        "🎯 Категории SponsorBlock: [" + catCount + " из " + ALL_SB_CATEGORIES.length + "]",
                        "📺 Качество видео: [" + qLabel + "]",
                        "⚡ Скорость воспроизведения: [" + speed + "x]",
                        "🎮 Цветные кнопки пульта: [" + (colorKeys ? "ВКЛ" : "ВЫКЛ") + "]",
                        "🌐 Веб-интерфейс: http://" + tvIp + ":" + WEB_SERVER_PORT
                };

                builder.setItems(items, this);
                builder.setNegativeButton("Закрыть", null);
                builder.show();
            } catch (Throwable t) {
                Log.e(TAG, "Error showing settings dialog", t);
            }
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            switch (which) {
                case 0:
                    showProxyInputDialog(activity);
                    break;
                case 1:
                    toggleSponsorBlock(activity);
                    showSettingsDialog(activity);
                    break;
                case 2:
                    showSponsorBlockCategoriesDialog(activity);
                    break;
                case 3:
                    cycleQuality(activity);
                    showSettingsDialog(activity);
                    break;
                case 4:
                    cyclePlaybackSpeed(activity);
                    showSettingsDialog(activity);
                    break;
                case 5:
                    boolean nk = !prefs.getBoolean(KEY_COLOR_KEYS_ENABLED, true);
                    prefs.edit().putBoolean(KEY_COLOR_KEYS_ENABLED, nk).apply();
                    Toast.makeText(activity, "Цветные кнопки: " + (nk ? "ВКЛ" : "ВЫКЛ"), Toast.LENGTH_SHORT).show();
                    showSettingsDialog(activity);
                    break;
                case 6:
                    showWebHintDialog(activity);
                    break;
            }
        }
    }

    private static class SbCategoriesDialogRunnable implements Runnable, DialogInterface.OnMultiChoiceClickListener, DialogInterface.OnClickListener {
        private final Activity activity;
        private boolean[] checked;

        SbCategoriesDialogRunnable(Activity activity) {
            this.activity = activity;
        }

        @Override
        public void run() {
            try {
                final Set<String> currentCats = getSbCategoriesSet(activity);
                checked = new boolean[ALL_SB_CATEGORIES.length];
                for (int i = 0; i < ALL_SB_CATEGORIES.length; i++) {
                    checked[i] = currentCats.contains(ALL_SB_CATEGORIES[i]);
                }

                AlertDialog.Builder builder = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
                builder.setTitle("🎯 Категории SponsorBlock");
                builder.setMultiChoiceItems(SB_CATEGORY_NAMES, checked, this);
                builder.setPositiveButton("Сохранить", this);
                builder.setNegativeButton("Назад", this);
                builder.show();
            } catch (Throwable t) {
                Log.e(TAG, "Error showing categories dialog", t);
            }
        }

        @Override
        public void onClick(DialogInterface dialog, int which, boolean isChecked) {
            if (checked != null && which >= 0 && which < checked.length) {
                checked[which] = isChecked;
            }
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which == DialogInterface.BUTTON_POSITIVE) {
                if (checked != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < ALL_SB_CATEGORIES.length; i++) {
                        if (checked[i]) {
                            if (sb.length() > 0) sb.append(",");
                            sb.append(ALL_SB_CATEGORIES[i]);
                        }
                    }
                    SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    prefs.edit().putString(KEY_SB_CATEGORIES, sb.toString()).apply();
                    updateWebClientConfig(prefs);
                    Toast.makeText(activity, "Категории сохранены", Toast.LENGTH_SHORT).show();
                }
            }
            showSettingsDialog(activity);
        }
    }

    public static void showSponsorBlockCategoriesDialog(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(new SbCategoriesDialogRunnable(activity));
    }

    public static void showSettingsDialog(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(new SettingsDialogRunnable(activity));
    }

    private static class ProxySaveClickListener implements DialogInterface.OnClickListener {
        private final Activity activity;
        private final SharedPreferences prefs;
        private final EditText input;
        ProxySaveClickListener(Activity activity, SharedPreferences prefs, EditText input) {
            this.activity = activity;
            this.prefs = prefs;
            this.input = input;
        }
        @Override
        public void onClick(DialogInterface dialog, int which) {
            String newProxy = input.getText().toString().trim();
            prefs.edit()
                    .putString(KEY_PROXY_URL, newProxy)
                    .putBoolean(KEY_PROXY_ENABLED, !newProxy.isEmpty())
                    .apply();
            writeExternalProxyFile(newProxy);
            Toast.makeText(activity, "Сохранено! Перезапуск...", Toast.LENGTH_SHORT).show();
            new Handler(Looper.getMainLooper()).postDelayed(new RestartRunnable(activity), 500);
        }
    }

    private static class ProxyDisableClickListener implements DialogInterface.OnClickListener {
        private final Activity activity;
        private final SharedPreferences prefs;
        ProxyDisableClickListener(Activity activity, SharedPreferences prefs) {
            this.activity = activity;
            this.prefs = prefs;
        }
        @Override
        public void onClick(DialogInterface dialog, int which) {
            prefs.edit().putBoolean(KEY_PROXY_ENABLED, false).apply();
            writeExternalProxyFile("");
            Toast.makeText(activity, "Прокси отключен! Перезапуск...", Toast.LENGTH_SHORT).show();
            new Handler(Looper.getMainLooper()).postDelayed(new RestartRunnable(activity), 500);
        }
    }

    private static class ProxyInputDialogRunnable implements Runnable {
        private final Activity activity;
        ProxyInputDialogRunnable(Activity activity) {
            this.activity = activity;
        }
        @Override
        public void run() {
            try {
                final SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String currentProxy = prefs.getString(KEY_PROXY_URL, "");
                String tvIp = getLocalIpAddress();
                String urlHint = ("127.0.0.1".equals(tvIp) || "0.0.0.0".equals(tvIp)) ?
                        "http://<IP_ТВ>:" + WEB_SERVER_PORT : "http://" + tvIp + ":" + WEB_SERVER_PORT;

                AlertDialog.Builder builder = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
                builder.setTitle("⚙ Настройка SOCKS5 Прокси");

                LinearLayout layout = new LinearLayout(activity);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(40, 20, 40, 10);

                TextView hintTv = new TextView(activity);
                hintTv.setText("Введите адрес SOCKS5 прокси (socks5://user:pass@host:port)\n\nИли откройте в браузере на телефоне/ПК:\n👉 " + urlHint);
                hintTv.setTextSize(15f);
                hintTv.setTextColor(0xFFCCCCCC);
                layout.addView(hintTv);

                final EditText input = new EditText(activity);
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
                input.setText(currentProxy);
                input.setHint("socks5://user:pass@host:port");
                input.setTextSize(16f);
                input.setTextColor(0xFFFFFFFF);
                input.setPadding(20, 20, 20, 20);
                input.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                layout.addView(input);

                builder.setView(layout);

                builder.setPositiveButton("Сохранить и перезапустить", new ProxySaveClickListener(activity, prefs, input));
                builder.setNeutralButton("Отключить", new ProxyDisableClickListener(activity, prefs));
                builder.setNegativeButton("Отмена", null);
                builder.show();
            } catch (Throwable t) {
                Log.e(TAG, "Error showing proxy dialog", t);
            }
        }
    }

    public static void showProxyInputDialog(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(new ProxyInputDialogRunnable(activity));
    }

    private static class WebHintDialogRunnable implements Runnable {
        private final Activity activity;
        WebHintDialogRunnable(Activity activity) {
            this.activity = activity;
        }
        @Override
        public void run() {
            try {
                String tvIp = getLocalIpAddress();
                AlertDialog.Builder builder = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
                builder.setTitle("🌐 Управление через Веб");
                builder.setMessage("Откройте браузер на смартфоне или компьютере, подключенном к той же сети Wi-Fi:\n\n👉 http://" + tvIp + ":" + WEB_SERVER_PORT + "\n\nТам можно удобно ввести адрес прокси и настроить все параметры мода.");
                builder.setPositiveButton("Понятно", null);
                builder.show();
            } catch (Throwable t) {
                Log.e(TAG, "Error showing web hint", t);
            }
        }
    }

    public static void showWebHintDialog(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(new WebHintDialogRunnable(activity));
    }

    public static void updateWebClientConfig(SharedPreferences prefs) {
        if (prefs == null) return;
        try {
            boolean sbEnabled = prefs.getBoolean(KEY_SB_ENABLED, true);
            String quality = prefs.getString(KEY_PREFERRED_QUALITY, "auto");
            float speed = prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f);
            String sbCatsJson = getSbCategoriesArray(prefs).toString();

            String js = "if(window.__yttv_update_config) { window.__yttv_update_config({" +
                    "sbEnabled:" + sbEnabled + "," +
                    "sbCategories:" + sbCatsJson + "," +
                    "quality:\"" + quality + "\"," +
                    "speed:" + speed +
                    "}); }";
            evaluateJs(js);
        } catch (Throwable t) {
            Log.e(TAG, "updateWebClientConfig error", t);
        }
    }

    private static String getHtmlPage(Context context, String alertMessage) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String currentProxy = prefs.getString(KEY_PROXY_URL, "");
        boolean proxyEn = prefs.getBoolean(KEY_PROXY_ENABLED, true);
        boolean sbEn = prefs.getBoolean(KEY_SB_ENABLED, true);
        String quality = prefs.getString(KEY_PREFERRED_QUALITY, "auto");
        float speed = prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f);
        boolean colorKeys = prefs.getBoolean(KEY_COLOR_KEYS_ENABLED, true);
        String tvIp = getLocalIpAddress();

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
        sb.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        sb.append("<title>YouTube TV Mod - Управление</title>");
        sb.append("<style>");
        sb.append("body { background: #0f0f0f; color: #f1f1f1; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 20px; display: flex; justify-content: center; }");
        sb.append(".container { max-width: 560px; width: 100%; display: flex; flex-direction: column; gap: 18px; }");
        sb.append(".card { background: #1a1a1a; border-radius: 14px; padding: 22px; box-shadow: 0 10px 30px rgba(0,0,0,0.6); border: 1px solid #282828; }");
        sb.append("h2 { color: #00e5ff; margin: 0 0 8px 0; font-size: 22px; display: flex; align-items: center; gap: 8px; }");
        sb.append("h3 { color: #fff; margin: 0 0 14px 0; font-size: 17px; border-bottom: 1px solid #333; padding-bottom: 8px; }");
        sb.append("p { color: #aaa; line-height: 1.5; font-size: 14px; margin: 0 0 12px 0; }");
        sb.append(".alert { background: #1b3d22; border-left: 4px solid #00e676; padding: 12px; border-radius: 6px; font-weight: bold; color: #b9f6ca; font-size: 15px; }");
        sb.append(".badges { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 14px; }");
        sb.append(".badge { display: inline-block; padding: 5px 12px; border-radius: 12px; font-size: 13px; font-weight: bold; }");
        sb.append(".badge-on { background: #1b3d22; color: #00e676; border: 1px solid #00e676; }");
        sb.append(".badge-off { background: #3d1b1b; color: #ff5252; border: 1px solid #ff5252; }");
        sb.append(".badge-ip { background: #192a3e; color: #00b0ff; border: 1px solid #00b0ff; }");
        sb.append("label { display: block; margin: 12px 0 6px; font-weight: bold; font-size: 14px; color: #ddd; }");
        sb.append("input[type=text], select { width: 100%; padding: 11px; border-radius: 7px; border: 1px solid #383838; background: #121212; color: #fff; font-size: 15px; box-sizing: border-box; }");
        sb.append("input[type=checkbox] { transform: scale(1.3); margin-right: 10px; }");
        sb.append(".chk-row { display: flex; align-items: center; margin: 12px 0; cursor: pointer; }");
        sb.append(".chk-row label { margin: 0; cursor: pointer; font-weight: normal; font-size: 15px; }");
        sb.append(".btn { width: 100%; padding: 13px; border-radius: 8px; border: none; font-size: 15px; font-weight: bold; cursor: pointer; margin-top: 10px; transition: 0.2s; }");
        sb.append(".btn-save { background: #00b4d8; color: #fff; }");
        sb.append(".btn-save:hover { background: #0096c7; }");
        sb.append(".btn-apply { background: #00c853; color: #fff; }");
        sb.append(".btn-apply:hover { background: #00b248; }");
        sb.append(".btn-disable { background: #2a2a2a; color: #ff5252; }");
        sb.append(".btn-disable:hover { background: #333; }");
        sb.append(".color-keys { background: #141414; padding: 12px; border-radius: 8px; margin-top: 10px; font-size: 13px; line-height: 1.8; border: 1px solid #2a2a2a; }");
        sb.append("code { background: #262626; padding: 2px 6px; border-radius: 4px; color: #00e5ff; }");
        sb.append("</style></head><body>");
        sb.append("<div class='container'>");

        sb.append("<div class='card'>");
        sb.append("<h2>&#128250; YouTube TV Mod (com.chatty.yttvaf)</h2>");
        sb.append("<div class='badges'>");
        if (sProxyEnabled && proxyEn && !currentProxy.isEmpty()) {
            sb.append("<div class='badge badge-on'>&#10004; Прокси активен</div>");
        } else {
            sb.append("<div class='badge badge-off'>&#10006; Прокси выключен</div>");
        }
        sb.append("<div class='badge badge-ip'>IP ТВ: ").append(tvIp).append("</div>");
        sb.append("</div>");
        if (alertMessage != null) {
            sb.append("<div class='alert'>").append(alertMessage).append("</div>");
        }
        sb.append("</div>");

        // Card 1: Mod Features (Live updates without restart)
        sb.append("<div class='card'>");
        sb.append("<h3>&#9889; Параметры видео и интерфейса (без перезапуска)</h3>");
        sb.append("<form method='POST' action='/save_mods'>");

        // SponsorBlock
        sb.append("<div class='chk-row'>");
        sb.append("<input type='checkbox' id='chk_sb' name='sb_enabled' value='1' ").append(sbEn ? "checked" : "").append(">");
        sb.append("<label for='chk_sb'><b>SponsorBlock:</b> включить автопропуск сегментов</label>");
        sb.append("</div>");

        // SponsorBlock Categories
        Set<String> curCats = getSbCategoriesSet(context);
        sb.append("<label style='margin-top: 14px; font-size: 13px; color: #00e5ff;'>🎯 Пропускаемые категории SponsorBlock:</label>");
        sb.append("<div style='display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin: 6px 0 14px 0; background: #141414; padding: 12px; border-radius: 8px; border: 1px solid #2a2a2a;'>");
        for (int i = 0; i < ALL_SB_CATEGORIES.length; i++) {
            String cId = ALL_SB_CATEGORIES[i];
            boolean cChecked = curCats.contains(cId);
            sb.append("<div class='chk-row' style='margin: 3px 0;'>");
            sb.append("<input type='checkbox' id='cat_").append(cId).append("' name='cat_").append(cId).append("' value='1' ").append(cChecked ? "checked" : "").append(">");
            sb.append("<label for='cat_").append(cId).append("' style='font-size: 13px;'>").append(SB_CATEGORY_NAMES[i]).append("</label>");
            sb.append("</div>");
        }
        sb.append("</div>");

        // Quality
        sb.append("<label>📺 Предпочитаемое качество видео:</label>");
        sb.append("<select name='preferred_quality'>");
        String[] qVals = new String[]{"auto", "1080", "1440", "2160", "720"};
        String[] qNames = new String[]{"Авто (по умолчанию)", "1080p Full HD", "1440p 2K", "2160p 4K Ultra HD", "720p HD"};
        for (int i = 0; i < qVals.length; i++) {
            sb.append("<option value='").append(qVals[i]).append("' ").append(qVals[i].equals(quality) ? "selected" : "").append(">");
            sb.append(qNames[i]).append("</option>");
        }
        sb.append("</select>");

        // Speed
        sb.append("<label>⚡ Скорость воспроизведения:</label>");
        sb.append("<select name='playback_speed'>");
        float[] speeds = new float[]{1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
        for (float sp : speeds) {
            boolean sel = Math.abs(sp - speed) < 0.05f;
            sb.append("<option value='").append(sp).append("' ").append(sel ? "selected" : "").append(">");
            sb.append(sp).append("x").append(sp == 1.0f ? " (Нормальная)" : "").append("</option>");
        }
        sb.append("</select>");

        // Color Keys
        sb.append("<label style='margin-top: 16px;'>🎮 Горячие кнопки пульта ТВ:</label>");
        sb.append("<div class='chk-row'>");
        sb.append("<input type='checkbox' id='chk_keys' name='color_keys_enabled' value='1' ").append(colorKeys ? "checked" : "").append(">");
        sb.append("<label for='chk_keys'>Включить быстрые цветные кнопки пульта</label>");
        sb.append("</div>");

        sb.append("<div class='color-keys'>");
        sb.append("🔴 <b>Красная:</b> Меню настроек на экране ТВ<br>");
        sb.append("🟢 <b>Зеленая:</b> Скорость (+0.25x)<br>");
        sb.append("🟡 <b>Желтая:</b> SponsorBlock (Вкл/Выкл)<br>");
        sb.append("🔵 <b>Синяя:</b> Качество (Auto / 1080p / 4K)");
        sb.append("</div>");

        sb.append("<button type='submit' class='btn btn-apply'>⚡ Применить на ТВ (мгновенно)</button>");
        sb.append("</form>");
        sb.append("</div>");

        // Card 2: SOCKS5 Proxy Configuration (Requires app restart)
        sb.append("<div class='card'>");
        sb.append("<h3>&#128279; Настройка SOCKS5 Прокси (с перезапуском)</h3>");
        sb.append("<p>Туннелирование трафика через локальный SNI Relay для обхода блокировок РКН.</p>");

        sb.append("<form method='POST' action='/save'>");
        sb.append("<label>Адрес SOCKS5 прокси:</label>");
        sb.append("<input type='text' name='proxy_url' placeholder='socks5://user:pass@host:port' value='").append(escapeHtml(currentProxy)).append("' required>");

        sb.append("<div class='chk-row'>");
        sb.append("<input type='checkbox' id='chk_prx_en' name='enabled' value='1' ").append(proxyEn ? "checked" : "").append(">");
        sb.append("<label for='chk_prx_en'>Использовать прокси при запуске приложения</label>");
        sb.append("</div>");

        sb.append("<button type='submit' class='btn btn-save'>💾 Сохранить и перезапустить TV</button>");
        sb.append("</form>");

        sb.append("<form method='POST' action='/disable'>");
        sb.append("<button type='submit' class='btn btn-disable'>🚫 Отключить прокси</button>");
        sb.append("</form>");
        sb.append("</div>");

        sb.append("</div></body></html>");
        return sb.toString();
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    public static void restartApp(Context context) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.startActivity(intent);
            }
            if (context instanceof Activity) {
                ((Activity) context).finishAffinity();
            }
            new Handler(Looper.getMainLooper()).postDelayed(new KillProcessRunnable(), 400);
        } catch (Throwable t) {
            Log.e(TAG, "Error restarting app", t);
        }
    }

    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            // Prioritize Wi-Fi and Ethernet interfaces
            for (NetworkInterface intf : interfaces) {
                if (!intf.isUp() || intf.isLoopback() || intf.isVirtual()) continue;
                String name = intf.getName().toLowerCase();
                if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en")) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            String host = addr.getHostAddress();
                            if (host != null && !host.startsWith("127.") && !"0.0.0.0".equals(host)) {
                                return host;
                            }
                        }
                    }
                }
            }
            // Fallback: any active non-loopback IPv4
            for (NetworkInterface intf : interfaces) {
                if (!intf.isUp() || intf.isLoopback()) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String host = addr.getHostAddress();
                        if (host != null && !host.startsWith("127.") && !"0.0.0.0".equals(host)) {
                            return host;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private static String readExternalProxyFile() {
        try {
            File f = new File("/sdcard/yttv_proxy.txt");
            if (f.exists() && f.canRead()) {
                BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
                String line = br.readLine();
                br.close();
                if (line != null) return line.trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static void writeExternalProxyFile(String proxyUrl) {
        try {
            File f = new File("/sdcard/yttv_proxy.txt");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write((proxyUrl == null ? "" : proxyUrl).getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {}
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {}
        }
    }

    private static ProxyConfig parseProxyConfig(String urlStr) {
        if (urlStr == null) return null;
        String s = urlStr.trim();
        if (s.isEmpty()) return null;

        String scheme = "socks5";
        if (s.contains("://")) {
            int idx = s.indexOf("://");
            scheme = s.substring(0, idx).toLowerCase();
            s = s.substring(idx + 3);
        }

        String username = null;
        String password = null;
        if (s.contains("@")) {
            int atIdx = s.lastIndexOf("@");
            String authPart = s.substring(0, atIdx);
            s = s.substring(atIdx + 1);

            int colonIdx = authPart.indexOf(":");
            if (colonIdx != -1) {
                username = authPart.substring(0, colonIdx);
                password = authPart.substring(colonIdx + 1);
            } else {
                username = authPart;
                password = "";
            }
            try {
                username = URLDecoder.decode(username, "UTF-8");
                password = URLDecoder.decode(password, "UTF-8");
            } catch (Exception ignored) {}
        }

        String host;
        int port = 1080;
        int colonIdx = s.lastIndexOf(":");
        if (colonIdx != -1) {
            host = s.substring(0, colonIdx);
            try {
                port = Integer.parseInt(s.substring(colonIdx + 1));
            } catch (Exception ignored) {}
        } else {
            host = s;
        }

        if (host.isEmpty()) return null;
        return new ProxyConfig(scheme, host, port, username, password);
    }

    private static class ProxyConfig {
        final String scheme;
        final String host;
        final int port;
        final String username;
        final String password;

        ProxyConfig(String scheme, String host, int port, String username, String password) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
        }
    }

    private static class ProxyAuthenticator extends Authenticator {
        private final String user;
        private final String pass;
        ProxyAuthenticator(String user, String pass) {
            this.user = user;
            this.pass = pass;
        }
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(user, pass.toCharArray());
        }
    }
}
