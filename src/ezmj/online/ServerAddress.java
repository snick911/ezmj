package ezmj.online;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** 服務器位址。webSocket 是 null 時走 TCP，不然就連到這個網址。 */
public record ServerAddress(String host, int port, URI webSocket) {

    public static final int DEFAULT_PORT = 7788;

    public ServerAddress(String host, int port) {
        this(host, port, null);
    }

    /**
     * 看得懂 host、host:port、[IPv6]、[IPv6]:port，沒寫 port 就用預設值，這些都走 TCP；
     * 以及 ws:// 與 wss:// 的網址，走 WebSocket。寫錯丟出 IllegalArgumentException。
     */
    public static ServerAddress parse(String text) {
        String value = text.strip();
        if (value.contains("://")) {
            return parseUrl(value);
        }
        String host = value;
        String port = null;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0) {
                throw new IllegalArgumentException("位址少了 ]");
            }
            host = value.substring(1, end);
            if (end + 1 < value.length()) {
                if (value.charAt(end + 1) != ':') {
                    throw new IllegalArgumentException("位址的格式不對");
                }
                port = value.substring(end + 2);
            }
        } else if (value.indexOf(':') >= 0) {
            if (value.indexOf(':') != value.lastIndexOf(':')) {
                throw new IllegalArgumentException("IPv6 位址要用 [ ] 包起來");
            }
            host = value.substring(0, value.indexOf(':'));
            port = value.substring(value.indexOf(':') + 1);
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("請輸入服務器位址");
        }
        return new ServerAddress(host, port == null ? DEFAULT_PORT : parsePort(port));
    }

    private static ServerAddress parseUrl(String value) {
        URI url;
        try {
            url = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("網址的格式不對");
        }
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("ws") && !scheme.equals("wss")) {
            throw new IllegalArgumentException("網址要是 ws:// 或 wss:// 開頭");
        }
        // 主機名稱不合規定時 getHost 是 null，port 不是數字也一樣
        if (url.getHost() == null || url.getRawUserInfo() != null || url.getRawFragment() != null
                || url.getRawAuthority().endsWith(":")) {
            throw new IllegalArgumentException("網址的格式不對");
        }
        int port = url.getPort() >= 0 ? parsePort(String.valueOf(url.getPort())) : scheme.equals("wss") ? 443 : 80;
        return new ServerAddress(url.getHost().replace("[", "").replace("]", ""), port, url);
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException e) {
            // 底下一起處理
        }
        throw new IllegalArgumentException("port 要是 1 到 65535 的數字");
    }

    @Override
    public String toString() {
        if (webSocket != null) {
            return webSocket.toString();
        }
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
}
