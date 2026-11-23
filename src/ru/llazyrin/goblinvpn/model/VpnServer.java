package ru.llazyrin.goblinvpn.model;

import java.io.Serializable;

public class VpnServer implements Serializable {
    private String rawUri;
    private String host;
    private int port;
    private String flag;
    private String country;
    private String remark;
    private int pingMs;
    private boolean isAlive;
    private String sni;
    private String security;
    private boolean isCommunityTop;
    private double communityScore;
    private String communityBadge;

    public VpnServer(String rawUri, String host, int port, String flag, String country, String remark, int pingMs) {
        this.rawUri = rawUri;
        this.host = host;
        this.port = port;
        this.flag = flag != null ? flag : "🌐";
        this.country = country != null ? country : "Сервер";
        this.remark = remark != null ? remark : (this.flag + " " + this.country);
        this.pingMs = pingMs;
        this.isAlive = true;
        parseQueryParams();
    }

    public VpnServer(String rawUri, String host, int port, String flag, String country, String remark, int pingMs, String sni, String security) {
        this(rawUri, host, port, flag, country, remark, pingMs);
        if (sni != null && !sni.trim().isEmpty()) this.sni = sni.trim();
        if (security != null && !security.trim().isEmpty()) this.security = security.trim();
    }

    public VpnServer(String rawUri, String host, int port, String flag, String country, String remark, int pingMs, String sni, String security, boolean isCommunityTop, double communityScore) {
        this(rawUri, host, port, flag, country, remark, pingMs, sni, security);
        this.isCommunityTop = isCommunityTop;
        this.communityScore = communityScore;
    }

    private void parseQueryParams() {
        if (rawUri == null) return;
        try {
            int qIdx = rawUri.indexOf('?');
            int hashIdx = rawUri.indexOf('#');
            if (qIdx != -1) {
                String query = (hashIdx != -1 && hashIdx > qIdx) ? rawUri.substring(qIdx + 1, hashIdx) : rawUri.substring(qIdx + 1);
                String[] pairs = query.split("&");
                for (String pair : pairs) {
                    int eq = pair.indexOf('=');
                    if (eq != -1) {
                        String key = pair.substring(0, eq).trim();
                        String val = pair.substring(eq + 1).trim();
                        if ("sni".equalsIgnoreCase(key) && (sni == null || sni.isEmpty())) {
                            sni = val;
                        } else if ("security".equalsIgnoreCase(key) && (security == null || security.isEmpty())) {
                            security = val;
                        } else if ("community".equalsIgnoreCase(key) || "community_top".equalsIgnoreCase(key) || "top".equalsIgnoreCase(key)) {
                            isCommunityTop = "1".equals(val) || "true".equalsIgnoreCase(val);
                        } else if ("score".equalsIgnoreCase(key) || "community_score".equalsIgnoreCase(key) || "rating".equalsIgnoreCase(key)) {
                            try {
                                communityScore = Double.parseDouble(val);
                                isCommunityTop = true;
                            } catch (Exception ignored) {}
                        } else if ("badge".equalsIgnoreCase(key) || "community_badge".equalsIgnoreCase(key)) {
                            communityBadge = val;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public String getRawUri() { return rawUri; }
    public void setRawUri(String rawUri) { this.rawUri = rawUri; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getFlag() { return flag; }
    public void setFlag(String flag) { this.flag = flag; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public int getPingMs() { return pingMs; }
    public void setPingMs(int pingMs) { this.pingMs = pingMs; }

    public boolean isAlive() { return isAlive; }
    public void setAlive(boolean alive) { isAlive = alive; }

    public String getSni() {
        if (sni != null && !sni.trim().isEmpty()) {
            return sni.trim();
        }
        parseQueryParams();
        if (sni != null && !sni.trim().isEmpty()) {
            return sni.trim();
        }
        return host;
    }

    public void setSni(String sni) {
        this.sni = sni;
    }

    public String getSecurity() {
        if (security != null && !security.trim().isEmpty()) {
            return security.trim();
        }
        parseQueryParams();
        return security != null ? security.trim() : "none";
    }

    public void setSecurity(String security) {
        this.security = security;
    }

    public boolean isReality() {
        String sec = getSecurity();
        if ("reality".equalsIgnoreCase(sec)) return true;
        return rawUri != null && rawUri.contains("security=reality");
    }

    public boolean isCommunityTop() {
        if (isCommunityTop) return true;
        if (communityScore > 0) return true;
        if (remark != null) {
            String r = remark.toLowerCase(java.util.Locale.ROOT);
            if (r.contains("⭐") || r.contains("сообществ") || r.contains("community") || r.contains("топ сообщества")) {
                return true;
            }
        }
        return false;
    }

    public void setCommunityTop(boolean communityTop) {
        this.isCommunityTop = communityTop;
    }

    public double getCommunityScore() {
        return communityScore;
    }

    public void setCommunityScore(double communityScore) {
        this.communityScore = communityScore;
        if (communityScore > 0) {
            this.isCommunityTop = true;
        }
    }

    public String getCommunityBadge() {
        if (communityBadge != null && !communityBadge.trim().isEmpty()) {
            return communityBadge.trim();
        }
        if (communityScore > 0) {
            return String.format(java.util.Locale.US, "⭐ Топ сообщества (%.1f)", communityScore);
        }
        return "⭐ Топ сообщества";
    }

    public void setCommunityBadge(String communityBadge) {
        this.communityBadge = communityBadge;
    }

    public String getDisplayName() {
        return flag + " " + country;
    }

    public String getCity() {
        if (country != null) {
            String c = country.toLowerCase(java.util.Locale.ROOT);
            if (c.contains("герман") || c.contains("germany") || c.contains("de")) return "Франкфурт";
            if (c.contains("финлянд") || c.contains("finland") || c.contains("fi")) return "Хельсинки";
            if (c.contains("нидерланд") || c.contains("netherlands") || c.contains("nl")) return "Амстердам";
            if (c.contains("сша") || c.contains("usa") || c.contains("us")) return "Нью-Йорк";
            if (c.contains("польш") || c.contains("poland") || c.contains("pl")) return "Варшава";
            if (c.contains("сингапур") || c.contains("singapore") || c.contains("sg")) return "Сингапур";
            if (c.contains("швеци") || c.contains("sweden") || c.contains("se")) return "Стокгольм";
            if (c.contains("великобрит") || c.contains("uk") || c.contains("gb")) return "Лондон";
            if (c.contains("болгар") || c.contains("bulgaria") || c.contains("bg")) return "София";
            if (c.contains("франци") || c.contains("france") || c.contains("fr")) return "Париж";
            if (c.contains("япони") || c.contains("japan") || c.contains("jp")) return "Токио";
        }
        return country != null ? country : "Европа";
    }

    public boolean isForeign() {
        if (flag != null && flag.contains("🇷🇺")) return false;
        if (country != null) {
            String c = country.toLowerCase(java.util.Locale.ROOT);
            if (c.contains("росси") || c.contains("russia") || c.contains("рф") || c.contains("moscow") || c.contains("spb")) {
                return false;
            }
        }
        if (remark != null) {
            String r = remark.toLowerCase(java.util.Locale.ROOT);
            if (r.contains("росси") || r.contains("russia") || r.contains("рф")) {
                return false;
            }
        }
        return true;
    }

    public boolean isVip() {
        return remark != null && (remark.contains("VIP") || remark.contains("Turbo") || remark.contains("⚡"));
    }

    public String getFormattedPing() {
        if (pingMs <= 0 || pingMs >= 9999) return "n/a";
        return pingMs + " ms";
    }

    public String getServerKey() {
        return (host != null ? host.trim().toLowerCase() : "") + ":" + port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VpnServer vpnServer = (VpnServer) o;
        if (port != vpnServer.port) return false;
        return host != null ? host.equalsIgnoreCase(vpnServer.host) : vpnServer.host == null;
    }

    @Override
    public int hashCode() {
        int result = host != null ? host.toLowerCase().hashCode() : 0;
        result = 31 * result + port;
        return result;
    }

    @Override
    public String toString() {
        return flag + " " + country + " (" + host + ":" + port + ")";
    }
}
