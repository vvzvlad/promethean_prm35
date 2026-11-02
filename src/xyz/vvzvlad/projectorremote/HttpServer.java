package xyz.vvzvlad.projectorremote;

import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal HTTP/1.1 server, one thread per connection, every response closes the connection.
 * Routes: GET / (remote page), GET /qrcode.js, POST /api/cmd, GET /api/status,
 * GET /api/info (the remote's URL for phones), POST /mcp.
 */
final class HttpServer implements Runnable {
    private static final String TAG = "ProjectorRemote";
    static final int PORT = 8070;

    private final AssetManager assets;
    private final ServerSocket serverSocket;

    HttpServer(AssetManager assets) throws IOException {
        this.assets = assets;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(PORT));
    }

    @Override
    public void run() {
        while (!serverSocket.isClosed()) {
            try {
                final Socket socket = serverSocket.accept();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                }, "http-conn").start();
            } catch (IOException e) {
                if (!serverSocket.isClosed()) Log.e(TAG, "accept failed", e);
            }
        }
    }

    void close() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    private static final class Response {
        final int status;
        final String contentType;
        final byte[] body;
        final String extraHeaders;

        Response(int status, String contentType, byte[] body, String extraHeaders) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.extraHeaders = extraHeaders;
        }

        static Response json(int status, JSONObject obj) {
            return new Response(status, "application/json",
                    obj.toString().getBytes(StandardCharsets.UTF_8), "");
        }

        static Response error(int status, String message) {
            try {
                return json(status, new JSONObject().put("error", message));
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];

            int contentLength = 0;
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            byte[] body = readBytes(in, contentLength);

            int q = target.indexOf('?');
            String path = q < 0 ? target : target.substring(0, q);
            String query = q < 0 ? "" : target.substring(q + 1);

            Response response;
            try {
                response = route(method, path, query, new String(body, StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.e(TAG, method + " " + path + " failed", e);
                response = Response.error(500, String.valueOf(e));
            }
            write(s.getOutputStream(), response);
        } catch (IOException e) {
            Log.w(TAG, "connection failed: " + e);
        }
    }

    private Response route(String method, String path, String query, String body) throws IOException {
        switch (path) {
            case "/":
            case "/index.html":
                if (!method.equals("GET")) return methodNotAllowed("GET");
                return new Response(200, "text/html; charset=utf-8", readAsset("index.html"), "");

            case "/qrcode.js":
                if (!method.equals("GET")) return methodNotAllowed("GET");
                return new Response(200, "application/javascript; charset=utf-8", readAsset("qrcode.js"), "");

            case "/api/info": {
                if (!method.equals("GET")) return methodNotAllowed("GET");
                String address = boxAddress();
                try {
                    return Response.json(200, new JSONObject().put("url",
                            address == null ? JSONObject.NULL : "http://" + address + ":" + PORT + "/"));
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
            }

            case "/api/cmd": {
                if (!method.equals("POST")) return methodNotAllowed("POST");
                Map<String, String> params = parseForm(query);
                params.putAll(parseForm(body));
                String command = params.get("c");
                if (command == null || command.isEmpty()) return Response.error(400, "missing c");
                try {
                    return Response.json(200, new JSONObject().put("reply", Projector.send(command)));
                } catch (IOException e) {
                    return Response.error(502, e.getMessage());
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
            }

            case "/api/status":
                if (!method.equals("GET")) return methodNotAllowed("GET");
                try {
                    return Response.json(200, Projector.status());
                } catch (IOException e) {
                    return Response.error(502, e.getMessage());
                }

            case "/mcp": {
                if (!method.equals("POST")) return methodNotAllowed("POST");
                JSONObject reply;
                try {
                    reply = Mcp.handle(body);
                } catch (JSONException e) {
                    return Response.json(400, Mcp.parseError(e));
                }
                if (reply == null) return new Response(202, null, new byte[0], "");
                return Response.json(200, reply);
            }

            default:
                return Response.error(404, "not found");
        }
    }

    /**
     * The box's address for phones: wlan0's IPv4, else the first site-local IPv4 that is not
     * on the projector's link. Looked up per request, since Wi-Fi may come up after the server.
     */
    private static String boxAddress() throws SocketException {
        String fallback = null;
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nif.isUp() || nif.isLoopback()) continue;
            for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                InetAddress a = ia.getAddress();
                if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                if (nif.getName().equals("wlan0")) return a.getHostAddress();
                if (fallback == null && !sameSubnet(ia, Projector.HOST)) fallback = a.getHostAddress();
            }
        }
        return fallback;
    }

    private static boolean sameSubnet(InterfaceAddress ia, String host) {
        try {
            byte[] a = ia.getAddress().getAddress();
            byte[] b = InetAddress.getByName(host).getAddress();
            int bits = ia.getNetworkPrefixLength();
            for (int i = 0; i < 4; i++) {
                int keep = Math.max(0, Math.min(8, bits - 8 * i));
                int mask = (0xff << (8 - keep)) & 0xff;
                if ((a[i] & mask) != (b[i] & mask)) return false;
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static Response methodNotAllowed(String allow) {
        Response r = Response.error(405, "method not allowed");
        return new Response(r.status, r.contentType, r.body, "Allow: " + allow + "\r\n");
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = assets.open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static Map<String, String> parseForm(String s) {
        Map<String, String> map = new HashMap<>();
        if (s == null || s.isEmpty()) return map;
        for (String pair : s.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                map.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (IllegalArgumentException | java.io.UnsupportedEncodingException ignored) {
                // Not form data (e.g. a JSON body): ignore the pair.
            }
        }
        return map;
    }

    /** Reads one header line, stripping the CR LF. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != '\n') {
            if (b == -1) throw new EOFException("connection closed mid-headers");
            if (b != '\r') sb.append((char) b);
        }
        return sb.toString();
    }

    private static byte[] readBytes(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(buf, off, length - off);
            if (n == -1) throw new EOFException("connection closed mid-body");
            off += n;
        }
        return buf;
    }

    private static void write(OutputStream out, Response r) throws IOException {
        StringBuilder head = new StringBuilder()
                .append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n");
        if (r.contentType != null) head.append("Content-Type: ").append(r.contentType).append("\r\n");
        head.append("Content-Length: ").append(r.body.length).append("\r\n")
                .append("Cache-Control: no-store\r\n")
                .append(r.extraHeaders)
                .append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(r.body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            default: return String.format(Locale.ROOT, "Status %d", status);
        }
    }
}
