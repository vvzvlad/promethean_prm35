package xyz.vvzvlad.projectorremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the server at boot so the web remote and MCP work without opening the app. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startForegroundService(new Intent(context, RemoteService.class));
        }
    }
}
