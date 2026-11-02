package xyz.vvzvlad.projectorremote;

import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Talks to the Promethean PRM-35 over its TCP control port.
 * One command per connection: "~" + command + "\r", the reply is a value ending in "\r".
 * The projector takes one connection at a time with at least 200 ms between commands,
 * so every exchange goes through one fair lock (fair keeps D-pad presses in order).
 *
 * The projector drops about every other connection: it closes it right after the command
 * arrives, with no reply, and the command is not carried out. The connection after a dropped
 * one goes through, so a dropped exchange is repeated.
 */
final class Projector {
    private static final String TAG = "ProjectorRemote";
    static final String HOST = "192.168.77.2";
    private static final int PORT = 8000;
    private static final int TIMEOUT_MS = 2000;
    private static final long GAP_MS = 200;
    private static final int ATTEMPTS = 3;

    private static final ReentrantLock LOCK = new ReentrantLock(true);
    private static long lastDone;

    private Projector() {}

    /** Sends one raw command (e.g. "~qP") and returns the reply without the trailing "\r". */
    static String send(String command) throws IOException {
        LOCK.lock();
        try {
            for (int attempt = 1; ; attempt++) {
                String reply = exchange(command);
                if (reply != null) return reply;
                if (attempt == ATTEMPTS) {
                    throw new IOException(command + ": the projector dropped the connection "
                            + ATTEMPTS + " times in a row");
                }
                Log.i(TAG, command + ": dropped by the projector, attempt " + attempt + ", retrying");
            }
        } finally {
            LOCK.unlock();
        }
    }

    /** One connection, one command. Returns null if the projector closed it without replying. */
    private static String exchange(String command) throws IOException {
        long wait = lastDone + GAP_MS - SystemClock.elapsedRealtime();
        if (wait > 0) SystemClock.sleep(wait);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            // The reply only comes while the connection stays open, so read until "\r".
            InputStream in = socket.getInputStream();
            StringBuilder reply = new StringBuilder();
            boolean gotAny = false;
            try {
                int b;
                while ((b = in.read()) != -1) {
                    gotAny = true;
                    if (b == '\r') break;
                    reply.append((char) b);
                }
            } catch (SocketTimeoutException e) {
                throw new IOException(command + ": no reply within " + TIMEOUT_MS + " ms", e);
            }
            return gotAny ? reply.toString() : null;
        } finally {
            lastDone = SystemClock.elapsedRealtime();
        }
    }

    /** Power state, current input and lamp hours as the projector reports them. */
    static JSONObject status() throws IOException {
        try {
            return new JSONObject()
                    .put("power", send("~qP"))
                    .put("input", send("~qS"))
                    .put("lamp_hours", send("~qL"));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
