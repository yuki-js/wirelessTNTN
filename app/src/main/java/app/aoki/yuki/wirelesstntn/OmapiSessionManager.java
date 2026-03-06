package app.aoki.yuki.wirelesstntn;

import android.content.Context;
import android.se.omapi.Channel;
import android.se.omapi.Reader;
import android.se.omapi.SEService;
import android.se.omapi.Session;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class OmapiSessionManager {

    public interface Callback {
        void onConnected();
        void onError(String message);
    }

    private SEService seService;
    private Session session;
    private Channel channel;
    private String targetReaderName;
    private Callback pendingCallback;

    public void connect(Context ctx, String readerName, Callback cb) {
        // Disconnect any previous session before reconnecting.
        disconnect();
        this.targetReaderName = readerName;
        this.pendingCallback = cb;
        Executor executor = Executors.newSingleThreadExecutor();
        try {
            seService = new SEService(ctx.getApplicationContext(), executor, this::onSeServiceConnected);
        } catch (Exception e) {
            AppLog.e("OmapiSessionManager: failed to create SEService", e);
            cb.onError("Failed to create SEService: " + e.getMessage());
        }
    }

    private void onSeServiceConnected() {
        AppLog.i("OmapiSessionManager: SEService connected");
        Reader[] readers = seService.getReaders();
        Reader target = null;
        for (Reader r : readers) {
            AppLog.d("OmapiSessionManager: found reader: " + r.getName());
            if (r.getName().equalsIgnoreCase(targetReaderName)) {
                target = r;
                break;
            }
        }
        if (target == null) {
            String msg = "Reader not found: " + targetReaderName;
            AppLog.e(msg);
            if (pendingCallback != null) pendingCallback.onError(msg);
            return;
        }
        try {
            session = target.openSession();
            AppLog.i("OmapiSessionManager: session opened on " + targetReaderName);
            if (pendingCallback != null) pendingCallback.onConnected();
        } catch (IOException e) {
            AppLog.e("OmapiSessionManager: failed to open session", e);
            if (pendingCallback != null) pendingCallback.onError("Failed to open session: " + e.getMessage());
        }
    }

    public byte[] openChannel(byte[] aid) throws IOException {
        if (session == null) throw new IOException("No OMAPI session");
        closeChannel();
        channel = session.openLogicalChannel(aid);
        if (channel == null) throw new IOException("openLogicalChannel returned null");
        return channel.getSelectResponse();
    }

    public byte[] transmit(byte[] apdu) throws IOException {
        if (channel == null) throw new IOException("No open channel");
        return channel.transmit(apdu);
    }

    public void closeChannel() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }

    public void disconnect() {
        closeChannel();
        if (session != null) {
            session.close();
            session = null;
        }
        if (seService != null && seService.isConnected()) {
            seService.shutdown();
            seService = null;
        }
    }

    public boolean isChannelOpen() {
        return channel != null && channel.isOpen();
    }
}
