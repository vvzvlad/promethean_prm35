package xyz.vvzvlad.projectorremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;

/** Foreground service that keeps the HTTP server (remote page, API, MCP) running on port 8070. */
public class RemoteService extends Service {
    private static final String CHANNEL = "server";

    private HttpServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Сервер пульта", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Пульт проектора")
                .setContentText("Веб-пульт и MCP на порту " + HttpServer.PORT)
                .build();
        startForeground(1, notification);

        try {
            server = new HttpServer(getAssets());
            new Thread(server, "http-accept").start();
        } catch (IOException e) {
            Log.e("ProjectorRemote", "cannot listen on port " + HttpServer.PORT, e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
