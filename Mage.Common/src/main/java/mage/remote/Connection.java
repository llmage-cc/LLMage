
package mage.remote;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Locale;
import mage.players.net.UserData;

/**
 *
 * @author BetaSteward_at_googlemail.com
 */
public class Connection {

    private String host;
    private int port;
    private String username;
    private String password;
    private String email;
    private String authToken;
    private String adminPassword;
    private ProxyType proxyType;
    private String proxyHost;
    private int proxyPort;
    private String proxyUsername;
    private String proxyPassword;
    private int clientCardDatabaseVersion;
    private String userIdStr;
    private int socketWriteTimeout;

    private UserData userData;

    private static final String serialization = "?serializationtype=java";
    private static final String transport = "bisocket";
    // two independent fixes for jboss oneway pools on java 9+, see issue #6514:
    // - onewayThreadPool replaces a broken pool of ServerInvoker (incoming oneway invocations);
    // - maxNumThreadsOneway limits a pool of jboss Client inside ServerInvokerCallbackHandler;
    // current usage: in async mode it create one stable thread per user session to send async callbacks from server to client
    // see ASYNC_MESSAGES
    private static final String threadpool = "onewayThreadPool=mage.remote.CustomThreadPool&maxNumThreadsOneway=1";

    private final String parameter;

    public Connection() {
        this("");
    }

    public Connection(String parameter) {
        this.parameter = parameter;
        socketWriteTimeout = 10000;
    }

    @Override
    public int hashCode() {
        return (transport + host + Integer.toString(port) + proxyType.toString()).hashCode();
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Connection)) {
            return false;
        }
        Connection otherConnection = (Connection) object;
        return hashCode() == otherConnection.hashCode();
    }

    @Override
    public String toString() {
        return host + ':' + Integer.toString(port) + '/' + serialization + parameter;
    }

    public String getURI() {
        if (host.equals("localhost")) {
            try {
                InetAddress inet = getLocalAddress();
                if (inet != null) {
                    return transport + "://" + inet.getHostAddress() + ':' + port + '/' + serialization + "&" + threadpool + parameter;
                }
            } catch (SocketException ex) {
                // just use localhost if can't find local ip
            }
        }
        return transport + "://" + host + ':' + port + '/' + serialization + "&" + threadpool + parameter;
    }

    public ProxyType getProxyType() {
        return proxyType;
    }

    public void setProxyType(ProxyType proxyType) {
        this.proxyType = proxyType;
    }

    public enum ProxyType {

        SOCKS("Socks"), HTTP("HTTP"), NONE("None");

        private final String text;

        ProxyType(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }

        public static ProxyType valueByText(String value) {
            for (ProxyType type : values()) {
                if (type.text.equals(value)) {
                    return type;
                }
            }
            return NONE;
        }
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUserIdStr() {
        return userIdStr;
    }

    public void setUserIdStr(String userIdStr) {
        this.userIdStr = userIdStr;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
    }

    public String getProxyUsername() {
        return proxyUsername;
    }

    public void setProxyUsername(String proxyUsername) {
        this.proxyUsername = proxyUsername;
    }

    public String getProxyPassword() {
        return proxyPassword;
    }

    public void setProxyPassword(String proxyPassword) {
        this.proxyPassword = proxyPassword;
    }

    /**
     * The client's own callback IPv4 address for {@code "localhost"} connections
     * (substituted by {@link #getURI()} so the server knows where to reach the
     * client back).
     * <p>
     * Walks the network interfaces and returns the first non-loopback IPv4
     * address whose interface is not a container bridge and whose address is
     * not a docker bridge gateway (see {@link #isBridgeStyleInterface(String)}
     * and {@link #isDockerBridgeGatewayAddress(InetAddress)}).
     * <p>
     * Before the skip heuristic, this returned the first non-loopback IPv4 in
     * enumeration order: on a docker-heavy host a {@code docker network create}
     * could reorder the interfaces so {@code br-*} bridge gateways (172.x.0.1)
     * enumerated before {@code eth0}, and "localhost" silently dialed the
     * bridge instead of the server on 127.0.0.1
     * — measured
     * 172.27.0.1 for br-8ee495ac2339 on 2026-09-23).
     * <p>
     * When every non-loopback candidate is skipped, the loopback IPv4
     * (127.0.0.1) is returned as the sane fallback — for a connection to
     * "localhost" that is the address the server should call the client back
     * on. Only IPv4 is ever returned (the {@code java.net.preferIPv4Stack}
     * behavior the callers rely on). {@code null} is returned only when the
     * host has no loopback IPv4 either; {@code getURI()} then keeps the
     * literal "localhost" host string.
     */
    public static InetAddress getLocalAddress() throws SocketException {
        InetAddress loopbackFallback = null;
        for (Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces(); interfaces.hasMoreElements();) {
            NetworkInterface iface = interfaces.nextElement();
            if (iface.isLoopback()) {
                if (loopbackFallback == null) {
                    loopbackFallback = firstIpv4(iface, null);
                }
                continue;
            }
            if (isBridgeStyleInterface(iface.getName())) {
                continue;
            }
            InetAddress candidate = firstIpv4(iface, null);
            if (candidate != null && !isDockerBridgeGatewayAddress(candidate)) {
                return candidate;
            }
        }
        return loopbackFallback;
    }

    /** First IPv4 address on the interface, else {@code fallback}. */
    private static InetAddress firstIpv4(NetworkInterface iface, InetAddress fallback) {
        for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
            if (addr != null) {
                InetAddress iaddr = addr.getAddress();
                if (iaddr instanceof Inet4Address) {
                    return iaddr;
                }
            }
        }
        return fallback;
    }

    /**
     * Interface-name prefixes of host-internal container/virtual bridges that
     * must never be handed out as the client's callback address: docker's
     * default bridge ({@code docker0}) and per-network bridges
     * ({@code br-<12 hex>}), container veth pairs, libvirt
     * ({@code virbr*}) and VMware/VirtualBox host-only interfaces.
     * <p>
     * A user-configured Linux bridge named {@code br0} is deliberately NOT
     * skipped — it can be a real uplink; only docker's {@code br-<12 hex>} id
     * shape is treated as a container bridge. The name check is order-proof: it
     * does not depend on which interface the OS happens to enumerate first.
     * <p>
     * Package-private for the heuristic unit tests (Mage.Common test tree).
     */
    static boolean isBridgeStyleInterface(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ENGLISH);
        if (lower.startsWith("br-")) {
            // docker names each per-network bridge br-<first 12 hex of the
            // network id>; a hand-made "br-mgmt" is a real bridge, kept.
            return lower.length() == 15 && isHex(lower, 3);
        }
        for (String prefix : BRIDGE_INTERFACE_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** True when every char from {@code from} on is a lowercase hex digit. */
    private static boolean isHex(String value, int from) {
        for (int i = from; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /** Interface-name prefixes skipped by {@link #getLocalAddress()} (docker/br- handled in {@link #isBridgeStyleInterface}). */
    private static final String[] BRIDGE_INTERFACE_PREFIXES = {
        "docker", "veth", "virbr", "vmnet", "vboxnet"
    };

    /**
     * Docker hands its bridges the stock default address pools 172.17.0.0/16
     * through 172.31.0.0/16, with the bridge itself at host address .0.1
     * ({@code docker0} = 172.17.0.1; each {@code docker network create} takes
     * the next /16 — 172.27.0.1 observed for br-8ee495ac2339). An address of
     * that exact shape is a container-bridge gateway, never the address a
     * server should call a client back on, even if it somehow sits on an
     * interface whose name did not match the bridge prefixes. The check is
     * intentionally narrower than "all of 172.16.0.0/12" — a real LAN on
     * 172.16/12 (common in corporate networks) must stay eligible.
     * <p>
     * Package-private for the heuristic unit tests (Mage.Common test tree).
     */
    static boolean isDockerBridgeGatewayAddress(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] octets = address.getAddress();
        int second = octets[1] & 0xFF;
        return second >= 17 && second <= 31
                && octets[2] == 0
                && (octets[3] & 0xFF) == 1;
    }

    public static String getMAC() throws SocketException {
        StringBuilder allMACs = new StringBuilder();
        for (Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces(); interfaces.hasMoreElements();) {
            NetworkInterface iface = interfaces.nextElement();
            byte[] mac = iface.getHardwareAddress();

            if (mac != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < mac.length; i++) {
                    sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? "-" : ""));
                }
                sb.append(';');
                allMACs.append(sb.toString());
            }
        }
        return allMACs.toString();
    }

    public void setUserData(UserData userData) {
        this.userData = userData;
    }

    public UserData getUserData() {
        return userData;
    }

    public int getSocketWriteTimeout() {
        return socketWriteTimeout;
    }
}
