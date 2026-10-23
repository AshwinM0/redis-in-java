package redis.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Certificates for the TLS tests, made with the JDK's own keytool the first time they are
 * needed. Making them at test time keeps private keys out of the repository and means they
 * never expire under the build.
 *
 * <p>Every certificate is self-signed. A client that trusts one treats it as its own
 * authority, which is all a test needs.
 */
final class TlsFixtures {

    static final String PASSWORD = "changeit";

    /** The server's identity: CN=localhost, valid for localhost and 127.0.0.1. */
    final Path serverKeystore;
    final Path serverCert;
    /** A second server identity, CN=rotated, standing in for a renewed certificate. */
    final Path rotatedKeystore;
    final Path rotatedCert;
    /** A client's identity, CN=chat-server, for mutual TLS. */
    final Path clientKeystore;
    final Path clientCert;

    private static TlsFixtures instance;

    static synchronized TlsFixtures get() throws Exception {
        if (instance == null) {
            instance = new TlsFixtures(Files.createTempDirectory("redis-tls-"));
        }
        return instance;
    }

    private TlsFixtures(Path dir) throws Exception {
        dir.toFile().deleteOnExit();
        serverKeystore = keypair(dir, "server", "localhost", "dns:localhost,ip:127.0.0.1");
        serverCert = dir.resolve("server.pem");
        rotatedKeystore = keypair(dir, "rotated", "rotated", "dns:localhost,ip:127.0.0.1");
        rotatedCert = dir.resolve("rotated.pem");
        clientKeystore = keypair(dir, "client", "chat-server", null);
        clientCert = dir.resolve("client.pem");
    }

    /**
     * A client that trusts exactly the given certificates and, given a keystore, presents the
     * key in it when the server asks for one.
     */
    static SSLSocketFactory client(Path keystore, Path... trusted) throws Exception {
        KeyStore anchors = KeyStore.getInstance("PKCS12");
        anchors.load(null, null);
        CertificateFactory x509 = CertificateFactory.getInstance("X.509");
        for (int i = 0; i < trusted.length; i++) {
            try (InputStream in = Files.newInputStream(trusted[i])) {
                anchors.setCertificateEntry("trusted-" + i, x509.generateCertificate(in));
            }
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(anchors);

        KeyManager[] keys = null;
        if (keystore != null) {
            KeyStore own = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                own.load(in, PASSWORD.toCharArray());
            }
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(own, PASSWORD.toCharArray());
            keys = keyManagers.getKeyManagers();
        }

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys, trust.getTrustManagers(), null);
        return context.getSocketFactory();
    }

    /**
     * Connects to 127.0.0.1 and checks that the server's certificate names that address, as
     * node-redis and every other careful client do. Without the check, any certificate the
     * client trusts would do, whichever server presented it.
     */
    static SSLSocket connect(SSLSocketFactory factory, int port) throws IOException {
        SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", port);
        SSLParameters params = socket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(params);
        socket.setSoTimeout(5000);
        return socket;
    }

    /** A PKCS12 keystore holding one self-signed key pair, plus its certificate as PEM. */
    private static Path keypair(Path dir, String name, String commonName, String subjectAltNames)
            throws Exception {
        Path keystore = dir.resolve(name + ".p12");
        Path cert = dir.resolve(name + ".pem");
        keystore.toFile().deleteOnExit();
        cert.toFile().deleteOnExit();

        List<String> generate = new ArrayList<>(List.of("-genkeypair", "-alias", name,
                "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + commonName,
                "-validity", "3650", "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass", PASSWORD, "-keypass", PASSWORD));
        if (subjectAltNames != null) {
            generate.addAll(List.of("-ext", "san=" + subjectAltNames));
        }
        keytool(generate);
        keytool(List.of("-exportcert", "-alias", name, "-keystore", keystore.toString(),
                "-storepass", PASSWORD, "-rfc", "-file", cert.toString()));
        return keystore;
    }

    private static void keytool(List<String> args) throws Exception {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path exe = Files.exists(bin.resolve("keytool.exe")) ? bin.resolve("keytool.exe") : bin.resolve("keytool");
        List<String> command = new ArrayList<>();
        command.add(exe.toString());
        command.addAll(args);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + command + "\n" + output);
        }
    }
}
