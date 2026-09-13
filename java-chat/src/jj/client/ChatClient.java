package jj.client;

import jj.common.Json;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 客户端网络层：TCP 长连接 + JSON 行协议。
 * 事件在后台线程回调，UI 层需自行切换到 EDT（SwingUtilities.invokeLater）。
 */
public class ChatClient {

    public interface Listener {
        void onEvent(Map<String, Object> evt);
        void onDown();
    }

    private Socket sock;
    private BufferedWriter out;
    private volatile Listener listener;
    private volatile boolean closed = false;

    public ChatClient(String host, int port, Listener listener) throws IOException {
        this.listener = listener;
        sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), 5000);
        out = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
        Thread t = new Thread(this::readLoop, "chat-reader");
        t.setDaemon(true);
        t.start();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    private void readLoop() {
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (!closed && (line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    Map<String, Object> evt = Json.obj(Json.parse(line));
                    if (evt != null && listener != null) listener.onEvent(evt);
                } catch (Exception ignored) {
                    // 单条消息解析失败不影响连接
                }
            }
        } catch (IOException ignored) {
        } finally {
            boolean notify = !closed;
            closed = true;
            if (notify && listener != null) listener.onDown();
        }
    }

    public synchronized void send(Map<String, Object> msg) {
        if (closed || out == null) return;
        try {
            out.write(Json.write(msg));
            out.write("\n");
            out.flush();
        } catch (IOException e) {
            close();
        }
    }

    public void close() {
        closed = true;
        try { sock.close(); } catch (IOException ignored) {}
    }
}
