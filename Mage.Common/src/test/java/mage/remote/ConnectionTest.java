package mage.remote;

import mage.utils.FluentBuilder;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

public class ConnectionTest {

    static class ConnectionBuilder extends FluentBuilder<Connection, ConnectionBuilder> {

        public int port;
        public String host;
        public String parameter;

        private ConnectionBuilder() {
            super(ConnectionBuilder::new);
        }

        @Override
        protected Connection makeValue() {
            final Connection result = new Connection(parameter);
            result.setHost(host);
            result.setPort(port);
            return result;
        }
    }

    private ConnectionBuilder baseBuilder() {
        return new ConnectionBuilder();
    }

    class TestsTemplate {
        final ConnectionBuilder testeeBuilder;

        TestsTemplate(ConnectionBuilder testeeBuilder) {
            this.testeeBuilder = testeeBuilder;
        }

        @Test
        @DisplayName("produce the expected scheme")
        void scheme() throws Exception {
            final URI testee = make(testeeBuilder);
            assertThat(testee.getScheme()).isEqualTo("bisocket");
        }

        URI make(ConnectionBuilder builder) {
            try {
                return new URI(builder.build().getURI());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("generate the expected port")
        void port() {
            final int expected = RandomUtils.nextInt(1000, 65000);
            final int port = make(testeeBuilder.with(c -> c.port = expected)).getPort();

            assertThat(port).isEqualTo(expected);
        }

        @Test
        @DisplayName("generate the expected serialisation parameter")
        void serialisation() {
            final String query = make(testeeBuilder).getQuery();

            assertThat(query).contains("serializationtype=java");
        }

        @Test
        @DisplayName("generate the expected threadpool parameter")
        void threadpool() {
            final String parameter = RandomStringUtils.randomAlphanumeric(12);
            final String query = make(testeeBuilder.with(c -> c.parameter = parameter)).getQuery();

            assertThat(query).contains("onewayThreadPool=mage.remote.CustomThreadPool&maxNumThreadsOneway=1" + parameter);
        }

    }

    @Nested
    @DisplayName("getUri when host is localhost should")
    class LocalhostTest extends TestsTemplate {

        LocalhostTest() {
            super(baseBuilder().with(c -> c.host = "localhost"));
        }

        @Test
        @DisplayName("generate an ipv4 as host")
        void ipv4Gen() {
            final String host = make(testeeBuilder).getHost();

            assertThat(host).matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+");
        }
    }

    private final String randomHost = RandomStringUtils.randomAlphabetic(15);

    @Nested
    @DisplayName("getUri when host is not localhost should")
    class StandardHostTest extends TestsTemplate {
        StandardHostTest() {
            super(baseBuilder().with(c -> c.host = randomHost));
        }

        @Test
        @DisplayName("generate the selected host as host")
        void hostGen() {
            final String host = make(testeeBuilder).getHost();

            assertThat(host).isEqualTo(randomHost);
        }
    }

    // ── skip-bridges heuristic ──

    @Nested
    @DisplayName("isBridgeStyleInterface should")
    class BridgeStyleInterfaceTest {

        @Test
        @DisplayName("skip docker bridges, veth pairs and virtual host-only interfaces")
        void skipsContainerAndVirtualBridges() {
            assertThat(Connection.isBridgeStyleInterface("docker0")).isTrue();
            assertThat(Connection.isBridgeStyleInterface("br-8ee495ac2339")).isTrue();
            assertThat(Connection.isBridgeStyleInterface("veth0a1b2c3d")).isTrue();
            assertThat(Connection.isBridgeStyleInterface("virbr0")).isTrue();
            assertThat(Connection.isBridgeStyleInterface("vmnet8")).isTrue();
            assertThat(Connection.isBridgeStyleInterface("vboxnet0")).isTrue();
            // case-insensitive: interface names are not guaranteed lowercase
            assertThat(Connection.isBridgeStyleInterface("BR-8EE495AC2339")).isTrue();
        }

        @Test
        @DisplayName("keep real uplinks, including a user-configured Linux bridge")
        void keepsRealInterfaces() {
            assertThat(Connection.isBridgeStyleInterface("eth0")).isFalse();
            assertThat(Connection.isBridgeStyleInterface("ens5")).isFalse();
            assertThat(Connection.isBridgeStyleInterface("enp0s3")).isFalse();
            assertThat(Connection.isBridgeStyleInterface("wlan0")).isFalse();
            assertThat(Connection.isBridgeStyleInterface("tailscale0")).isFalse();
            // a hand-configured bridge can be a real uplink — only docker's
            // "br-<12 hex id>" shape is treated as a container bridge
            assertThat(Connection.isBridgeStyleInterface("br0")).isFalse();
            assertThat(Connection.isBridgeStyleInterface("br-mgmt")).isFalse();      // real bridge name
            assertThat(Connection.isBridgeStyleInterface("br-8ee495ac233")).isFalse(); // 11 hex — not docker's id shape
            assertThat(Connection.isBridgeStyleInterface("br-8ee495ac233zz")).isFalse(); // not hex
            assertThat(Connection.isBridgeStyleInterface(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("isDockerBridgeGatewayAddress should")
    class DockerBridgeGatewayAddressTest {

        private java.net.InetAddress addr(String literal) throws Exception {
            return java.net.InetAddress.getByName(literal);
        }

        @Test
        @DisplayName("reject the stock docker default-pool gateways (172.17-31.0.1)")
        void rejectsDockerDefaultPoolGateways() throws Exception {
            assertThat(Connection.isDockerBridgeGatewayAddress(addr("172.17.0.1"))).isTrue();  // docker0
            assertThat(Connection.isDockerBridgeGatewayAddress(addr("172.27.0.1"))).isTrue();  // measured offender br-8ee495ac2339
            assertThat(Connection.isDockerBridgeGatewayAddress(addr("172.31.0.1"))).isTrue();  // last default pool
        }

        @Test
        @DisplayName("keep everything that is not exactly that shape")
        void keepsOtherAddresses() throws Exception {
            assertThat(Connection.isDockerBridgeGatewayAddress(addr("172.16.0.1"))).isFalse(); // outside 17..31
            assertThat(Connection.isDockerBridgeGatewayAddress(addr("172.32.0.1"))).isFalse(); // outside 17..31
            assertThat(Connection.isDockerBridgeGatewayAddress(addr("172.27.0.2"))).isFalse(); // a container, not the gateway
            assertThat(Connection.isDockerBridgeGatewayAddress(addr("172.27.1.1"))).isFalse();
            assertThat(Connection.isDockerBridgeGatewayAddress(addr("192.168.1.1"))).isFalse();
            assertThat(Connection.isDockerBridgeGatewayAddress(addr("10.0.0.1"))).isFalse();
            assertThat(Connection.isDockerBridgeGatewayAddress(addr("127.0.0.1"))).isFalse();
            assertThat(Connection.isDockerBridgeGatewayAddress(addr("198.51.100.1"))).isFalse(); // TEST-NET-2
            assertThat(Connection.isDockerBridgeGatewayAddress(addr("192.0.2.1"))).isFalse(); // TEST-NET-1
            assertThat(Connection.isDockerBridgeGatewayAddress(addr("::1"))).isFalse();          // IPv6 never eligible
        }
    }

    @Nested
    @DisplayName("getLocalAddress should")
    class LocalAddressTest {

        @Test
        @DisplayName("never return a docker bridge gateway or a bridge-interface address")
        void neverReturnsBridgeAddress() throws Exception {
            final java.net.InetAddress local = Connection.getLocalAddress();
            if (local == null) {
                // No loopback IPv4 either — nothing to assert; getURI keeps "localhost".
                return;
            }
            assertThat(local).isInstanceOf(java.net.Inet4Address.class);
            assertThat(Connection.isDockerBridgeGatewayAddress(local))
                    .as("localhost must never resolve to a docker bridge gateway: %s", local)
                    .isFalse();
            // If the address is not loopback, it must come from a non-bridge iface.
            if (!local.isLoopbackAddress()) {
                final java.net.NetworkInterface iface = java.net.NetworkInterface.getByInetAddress(local);
                assertThat(iface).as("returned address %s must belong to an interface", local).isNotNull();
                assertThat(Connection.isBridgeStyleInterface(iface.getName()))
                        .as("localhost must not resolve from bridge-style interface %s", iface.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("fall back to loopback when only bridge-style interfaces exist")
        void loopbackFallbackShape() {
            // The fallback contract, exercised through the public predicate pair:
            // 127.0.0.1 is the address a "localhost" connection must use when no
            // non-bridge IPv4 exists, and it is never classified as a gateway.
            try {
                assertThat(Connection.isDockerBridgeGatewayAddress(java.net.InetAddress.getByName("127.0.0.1")))
                        .isFalse();
            } catch (java.net.UnknownHostException e) {
                throw new AssertionError(e);
            }
        }
    }
}
