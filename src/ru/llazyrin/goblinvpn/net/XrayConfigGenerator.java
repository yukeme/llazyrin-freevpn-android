package ru.llazyrin.goblinvpn.net;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.llazyrin.goblinvpn.model.VpnServer;

/**
 * XrayConfigGenerator — Генератор полной конфигурации Xray-core для VLESS Reality туннелирования.
 *
 * Создает JSON конфигурацию, идентичную эталонной архитектуре v2rayNG и Happ:
 * 1. TUN Inbound (интерфейс xray0, MTU 1500, перехват всего L3 трафика).
 * 2. SOCKS Inbound (локальный fallback прокси на 127.0.0.1:10808).
 * 3. VLESS Reality Outbound с поддержкой Vision, SNI, Public Key, ShortId, Fingerprint.
 * 4. Прямой маршрутизатор (Freedom) для локальной сети и опционального обхода зоны RU.
 * 5. DNS резолвер с DoH и Cloudflare/Google DNS.
 */
public class XrayConfigGenerator {
    private static final String TAG = "XrayConfigGenerator";
    private static final Pattern VLESS_URI_PATTERN = Pattern.compile("^vless://([^@]+)@(\\[[a-fA-F0-9:]+\\]|[^:?#/\\s]+):(\\d+)");

    public static String generateXrayConfig(VpnServer server, boolean bypassRu, int mtu) {
        if (server == null) {
            throw new IllegalArgumentException("VpnServer cannot be null");
        }

        String rawUri = server.getRawUri();
        String uuid = "dc620cd6-a9d8-4a0f-8f18-006b895db77e";
        String host = server.getHost();
        int port = server.getPort();

        String security = "reality";
        String sni = server.getSni();
        String pbk = "";
        String sid = "";
        String spx = "";
        String fp = "chrome";
        String flow = "";
        String network = "tcp";
        String wsPath = "/";
        String grpcService = "";
        String alpn = "";
        String headerType = "";

        if (rawUri != null && !rawUri.trim().isEmpty()) {
            try {
                String clean = rawUri.trim();
                int hashIdx = clean.indexOf('#');
                if (hashIdx != -1) {
                    clean = clean.substring(0, hashIdx);
                }

                Matcher m = VLESS_URI_PATTERN.matcher(clean);
                if (m.find()) {
                    uuid = m.group(1).trim();
                    host = m.group(2).trim();
                    try {
                        port = Integer.parseInt(m.group(3).trim());
                    } catch (Exception ignored) {}
                }

                int qIdx = clean.indexOf('?');
                if (qIdx != -1) {
                    String query = clean.substring(qIdx + 1);
                    Map<String, String> queryParams = parseQueryParams(query);

                    if (queryParams.containsKey("security")) security = queryParams.get("security");
                    if (queryParams.containsKey("sni")) sni = queryParams.get("sni");
                    if (queryParams.containsKey("pbk")) pbk = queryParams.get("pbk");
                    if (queryParams.containsKey("sid")) sid = queryParams.get("sid");
                    if (queryParams.containsKey("spx")) spx = queryParams.get("spx");
                    if (queryParams.containsKey("fp")) fp = queryParams.get("fp");
                    if (queryParams.containsKey("flow")) flow = queryParams.get("flow");
                    if (queryParams.containsKey("type")) network = queryParams.get("type");
                    if (queryParams.containsKey("path")) wsPath = queryParams.get("path");
                    if (queryParams.containsKey("serviceName")) grpcService = queryParams.get("serviceName");
                    if (queryParams.containsKey("alpn")) alpn = queryParams.get("alpn");
                    if (queryParams.containsKey("headerType")) headerType = queryParams.get("headerType");
                }
            } catch (Exception e) {
                Log.w(TAG, "Error parsing rawUri params, using server defaults", e);
            }
        }

        if (sni == null || sni.trim().isEmpty()) sni = host;
        if ("random".equalsIgnoreCase(fp) || fp == null || fp.trim().isEmpty()) fp = "chrome";
        if ("raw".equalsIgnoreCase(network)) network = "tcp";
        if (mtu <= 0) mtu = 1500;

        try {
            JSONObject root = new JSONObject();

            // 1. Log configuration
            JSONObject log = new JSONObject();
            log.put("loglevel", "warning");
            root.put("log", log);

            // 2. DNS servers
            JSONObject dns = new JSONObject();
            JSONArray dnsServers = new JSONArray();
            dnsServers.put("1.1.1.1");
            dnsServers.put("8.8.8.8");
            dnsServers.put("https://1.1.1.1/dns-query");
            dns.put("servers", dnsServers);
            root.put("dns", dns);

            // 3. Inbounds: TUN + SOCKS
            JSONArray inbounds = new JSONArray();

            // 3a. TUN Inbound
            JSONObject tunInbound = new JSONObject();
            tunInbound.put("tag", "tun");
            tunInbound.put("port", 0);
            tunInbound.put("protocol", "tun");
            JSONObject tunSettings = new JSONObject();
            tunSettings.put("mtu", mtu);
            tunSettings.put("name", "xray0");
            tunSettings.put("userLevel", 8);
            tunSettings.put("keepAlive", 10);
            tunInbound.put("settings", tunSettings);

            JSONObject tunSniffing = new JSONObject();
            tunSniffing.put("enabled", true);
            JSONArray tunDest = new JSONArray();
            tunDest.put("http");
            tunDest.put("tls");
            tunSniffing.put("destOverride", tunDest);
            tunInbound.put("sniffing", tunSniffing);
            inbounds.put(tunInbound);

            // 3b. Local SOCKS Inbound
            JSONObject socksInbound = new JSONObject();
            socksInbound.put("tag", "socks");
            socksInbound.put("listen", "127.0.0.1");
            socksInbound.put("port", 10808);
            socksInbound.put("protocol", "socks");
            JSONObject socksSettings = new JSONObject();
            socksSettings.put("auth", "noauth");
            socksSettings.put("udp", true);
            socksSettings.put("userLevel", 8);
            socksInbound.put("settings", socksSettings);
            inbounds.put(socksInbound);

            root.put("inbounds", inbounds);

            // 4. Outbounds: Proxy (VLESS) + Direct + Block
            JSONArray outbounds = new JSONArray();

            // 4a. Proxy Outbound
            JSONObject proxyOutbound = new JSONObject();
            proxyOutbound.put("tag", "proxy");
            proxyOutbound.put("protocol", "vless");

            JSONObject proxySettings = new JSONObject();
            JSONArray vnext = new JSONArray();
            JSONObject serverObj = new JSONObject();
            serverObj.put("address", host);
            serverObj.put("port", port);

            JSONArray users = new JSONArray();
            JSONObject user = new JSONObject();
            user.put("id", uuid);
            user.put("flow", flow != null ? flow : "");
            user.put("encryption", "none");
            user.put("level", 8);
            users.put(user);

            serverObj.put("users", users);
            vnext.put(serverObj);
            proxySettings.put("vnext", vnext);
            proxyOutbound.put("settings", proxySettings);

            JSONObject streamSettings = new JSONObject();
            streamSettings.put("network", network);
            if ("reality".equalsIgnoreCase(security)) {
                streamSettings.put("security", "reality");
                JSONObject realitySettings = new JSONObject();
                realitySettings.put("show", false);
                realitySettings.put("fingerprint", fp);
                realitySettings.put("serverName", sni);
                realitySettings.put("publicKey", pbk);
                realitySettings.put("shortId", sid != null ? sid : "");
                realitySettings.put("spiderX", spx != null ? spx : "");
                if (alpn != null && !alpn.isEmpty()) {
                    JSONArray alpnArr = new JSONArray();
                    for (String a : alpn.split(",")) {
                        if (!a.trim().isEmpty()) alpnArr.put(a.trim());
                    }
                    realitySettings.put("alpn", alpnArr);
                }
                streamSettings.put("realitySettings", realitySettings);
            } else if ("tls".equalsIgnoreCase(security)) {
                streamSettings.put("security", "tls");
                JSONObject tlsSettings = new JSONObject();
                tlsSettings.put("serverName", sni);
                tlsSettings.put("fingerprint", fp);
                if (alpn != null && !alpn.isEmpty()) {
                    JSONArray alpnArr = new JSONArray();
                    for (String a : alpn.split(",")) {
                        if (!a.trim().isEmpty()) alpnArr.put(a.trim());
                    }
                    tlsSettings.put("alpn", alpnArr);
                }
                streamSettings.put("tlsSettings", tlsSettings);
            }

            if ("ws".equalsIgnoreCase(network)) {
                JSONObject wsSettings = new JSONObject();
                wsSettings.put("path", wsPath != null ? wsPath : "/");
                JSONObject headers = new JSONObject();
                headers.put("Host", sni);
                wsSettings.put("headers", headers);
                streamSettings.put("wsSettings", wsSettings);
            } else if ("grpc".equalsIgnoreCase(network)) {
                JSONObject grpcSettings = new JSONObject();
                if (grpcService != null && !grpcService.isEmpty()) {
                    grpcSettings.put("serviceName", grpcService);
                }
                grpcSettings.put("multiMode", true);
                streamSettings.put("grpcSettings", grpcSettings);
            } else if ("tcp".equalsIgnoreCase(network) && headerType != null && "http".equalsIgnoreCase(headerType)) {
                JSONObject tcpSettings = new JSONObject();
                JSONObject header = new JSONObject();
                header.put("type", "http");
                tcpSettings.put("header", header);
                streamSettings.put("tcpSettings", tcpSettings);
            }

            // TCP Keepalive (10s) to prevent tunnel drop on cellular carrier NAT/GGSN idle timeouts
            JSONObject sockopt = new JSONObject();
            sockopt.put("tcpKeepAliveInterval", 10);
            sockopt.put("tcpKeepAliveIdle", 10);
            sockopt.put("keepAlive", 10);
            sockopt.put("tcpNoDelay", true);
            streamSettings.put("sockopt", sockopt);

            proxyOutbound.put("streamSettings", streamSettings);
            outbounds.put(proxyOutbound);

            // 4b. Direct Outbound
            JSONObject directOutbound = new JSONObject();
            directOutbound.put("tag", "direct");
            directOutbound.put("protocol", "freedom");
            JSONObject directSettings = new JSONObject();
            directSettings.put("domainStrategy", "AsIs");
            directOutbound.put("settings", directSettings);
            outbounds.put(directOutbound);

            // 4c. Block Outbound
            JSONObject blockOutbound = new JSONObject();
            blockOutbound.put("tag", "block");
            blockOutbound.put("protocol", "blackhole");
            JSONObject blockSettings = new JSONObject();
            JSONObject responseObj = new JSONObject();
            responseObj.put("type", "none");
            blockSettings.put("response", responseObj);
            blockOutbound.put("settings", blockSettings);
            outbounds.put(blockOutbound);

            root.put("outbounds", outbounds);

            // 5. Routing Rules
            JSONObject routing = new JSONObject();
            routing.put("domainStrategy", "IPIfNonMatch");
            JSONArray rules = new JSONArray();

            // Bypass private / LAN
            JSONObject privateRule = new JSONObject();
            privateRule.put("type", "field");
            privateRule.put("outboundTag", "direct");
            JSONArray privateIps = new JSONArray();
            privateIps.put("geoip:private");
            privateRule.put("ip", privateIps);
            rules.put(privateRule);

            // Bypass RU if requested
            if (bypassRu) {
                JSONObject ruIpRule = new JSONObject();
                ruIpRule.put("type", "field");
                ruIpRule.put("outboundTag", "direct");
                JSONArray ruIps = new JSONArray();
                ruIps.put("geoip:ru");
                ruIpRule.put("ip", ruIps);
                rules.put(ruIpRule);

                JSONObject ruDomainRule = new JSONObject();
                ruDomainRule.put("type", "field");
                ruDomainRule.put("outboundTag", "direct");
                JSONArray ruDomains = new JSONArray();
                ruDomains.put("geosite:category-ru");
                ruDomainRule.put("domain", ruDomains);
                rules.put(ruDomainRule);
            }

            // Route all tun and socks traffic through proxy
            JSONObject proxyRule = new JSONObject();
            proxyRule.put("type", "field");
            JSONArray inboundTags = new JSONArray();
            inboundTags.put("tun");
            inboundTags.put("socks");
            proxyRule.put("inboundTag", inboundTags);
            proxyRule.put("outboundTag", "proxy");
            rules.put(proxyRule);

            routing.put("rules", rules);
            root.put("routing", routing);

            return root.toString(2);
        } catch (Exception e) {
            Log.e(TAG, "Failed to build Xray JSON config: " + e.getMessage(), e);
            throw new RuntimeException("Xray JSON config creation failed", e);
        }
    }

    /**
     * Benchmark Configuration specifically for Libv2ray.measureOutboundDelay.
     * Contains NO TUN inbound (avoids permission errors), only standard proxy outbound and DNS.
     */
    public static String generateBenchmarkConfig(VpnServer server) {
        if (server == null) {
            return null;
        }

        String rawUri = server.getRawUri();
        String uuid = "dc620cd6-a9d8-4a0f-8f18-006b895db77e";
        String host = server.getHost();
        int port = server.getPort();

        String security = "reality";
        String sni = server.getSni();
        String pbk = "";
        String sid = "";
        String spx = "";
        String fp = "chrome";
        String flow = "";
        String network = "tcp";
        String wsPath = "/";
        String grpcService = "";

        if (rawUri != null && !rawUri.trim().isEmpty()) {
            try {
                String clean = rawUri.trim();
                int hashIdx = clean.indexOf('#');
                if (hashIdx != -1) clean = clean.substring(0, hashIdx);

                Matcher m = VLESS_URI_PATTERN.matcher(clean);
                if (m.find()) {
                    uuid = m.group(1).trim();
                    host = m.group(2).trim();
                    try {
                        port = Integer.parseInt(m.group(3).trim());
                    } catch (Exception ignored) {}
                }

                int qIdx = clean.indexOf('?');
                if (qIdx != -1) {
                    String query = clean.substring(qIdx + 1);
                    Map<String, String> queryParams = parseQueryParams(query);

                    if (queryParams.containsKey("security")) security = queryParams.get("security");
                    if (queryParams.containsKey("sni")) sni = queryParams.get("sni");
                    if (queryParams.containsKey("pbk")) pbk = queryParams.get("pbk");
                    if (queryParams.containsKey("sid")) sid = queryParams.get("sid");
                    if (queryParams.containsKey("spx")) spx = queryParams.get("spx");
                    if (queryParams.containsKey("fp")) fp = queryParams.get("fp");
                    if (queryParams.containsKey("flow")) flow = queryParams.get("flow");
                    if (queryParams.containsKey("type")) network = queryParams.get("type");
                    if (queryParams.containsKey("path")) wsPath = queryParams.get("path");
                    if (queryParams.containsKey("serviceName")) grpcService = queryParams.get("serviceName");
                }
            } catch (Exception e) {
                Log.w(TAG, "Error parsing rawUri params for benchmark", e);
            }
        }

        if (sni == null || sni.trim().isEmpty()) sni = host;
        if ("random".equalsIgnoreCase(fp) || fp == null || fp.trim().isEmpty()) fp = "chrome";
        if ("raw".equalsIgnoreCase(network)) network = "tcp";

        try {
            JSONObject root = new JSONObject();

            JSONObject log = new JSONObject();
            log.put("loglevel", "none");
            root.put("log", log);

            JSONObject dns = new JSONObject();
            JSONArray dnsServers = new JSONArray();
            dnsServers.put("1.1.1.1");
            dnsServers.put("8.8.8.8");
            dns.put("servers", dnsServers);
            root.put("dns", dns);

            // Inbounds: empty array for outbound delay probe
            root.put("inbounds", new JSONArray());

            JSONArray outbounds = new JSONArray();
            JSONObject proxyOutbound = new JSONObject();
            proxyOutbound.put("tag", "proxy");
            proxyOutbound.put("protocol", "vless");

            JSONObject proxySettings = new JSONObject();
            JSONArray vnext = new JSONArray();
            JSONObject serverObj = new JSONObject();
            serverObj.put("address", host);
            serverObj.put("port", port);

            JSONArray users = new JSONArray();
            JSONObject user = new JSONObject();
            user.put("id", uuid);
            user.put("flow", flow != null ? flow : "");
            user.put("encryption", "none");
            user.put("level", 8);
            users.put(user);

            serverObj.put("users", users);
            vnext.put(serverObj);
            proxySettings.put("vnext", vnext);
            proxyOutbound.put("settings", proxySettings);

            JSONObject streamSettings = new JSONObject();
            streamSettings.put("network", network);
            if ("reality".equalsIgnoreCase(security)) {
                streamSettings.put("security", "reality");
                JSONObject realitySettings = new JSONObject();
                realitySettings.put("show", false);
                realitySettings.put("fingerprint", fp);
                realitySettings.put("serverName", sni);
                realitySettings.put("publicKey", pbk);
                realitySettings.put("shortId", sid != null ? sid : "");
                realitySettings.put("spiderX", spx != null ? spx : "");
                streamSettings.put("realitySettings", realitySettings);
            } else if ("tls".equalsIgnoreCase(security)) {
                streamSettings.put("security", "tls");
                JSONObject tlsSettings = new JSONObject();
                tlsSettings.put("serverName", sni);
                tlsSettings.put("fingerprint", fp);
                streamSettings.put("tlsSettings", tlsSettings);
            }

            if ("ws".equalsIgnoreCase(network)) {
                JSONObject wsSettings = new JSONObject();
                wsSettings.put("path", wsPath);
                JSONObject headers = new JSONObject();
                headers.put("Host", sni);
                wsSettings.put("headers", headers);
                streamSettings.put("wsSettings", wsSettings);
            } else if ("grpc".equalsIgnoreCase(network)) {
                JSONObject grpcSettings = new JSONObject();
                grpcSettings.put("serviceName", grpcService);
                streamSettings.put("grpcSettings", grpcSettings);
            }

            JSONObject sockopt = new JSONObject();
            sockopt.put("tcpKeepAliveInterval", 10);
            sockopt.put("tcpKeepAliveIdle", 10);
            sockopt.put("keepAlive", 10);
            sockopt.put("tcpNoDelay", true);
            streamSettings.put("sockopt", sockopt);

            proxyOutbound.put("streamSettings", streamSettings);
            outbounds.put(proxyOutbound);

            root.put("outbounds", outbounds);

            return root.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to build Xray benchmark config: " + e.getMessage(), e);
            return null;
        }
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;

        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq != -1) {
                String k = pair.substring(0, eq).trim();
                String v = pair.substring(eq + 1).trim();
                try {
                    v = URLDecoder.decode(v, "UTF-8");
                } catch (Exception ignored) {}
                map.put(k, v);
            }
        }
        return map;
    }
}
