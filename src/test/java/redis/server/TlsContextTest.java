package redis.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Certificates are renewed while the server runs, so the context has to pick up a new one
 * without a restart, and must never let a bad file take TLS down.
 */
class TlsContextTest {

    @TempDir
    Path dir;

    private TlsFixtures certs;
    private Path keystore;

    @BeforeEach
    void setUp() throws Exception {
        certs = TlsFixtures.get();
        keystore = dir.resolve("server.p12");
        Files.copy(certs.serverKeystore, keystore);
    }

    @Test
    @DisplayName("Unchanged files are not reloaded")
    void unchangedFilesAreLeftAlone() throws Exception {
        assertFalse(new TlsContext(keystore, TlsFixtures.PASSWORD, null).reloadIfChanged());
    }

    @Test
    @DisplayName("A renewed certificate is presented from the next handshake on")
    void renewedCertificateIsPickedUp() throws Exception {
        TlsContext context = new TlsContext(keystore, TlsFixtures.PASSWORD, null);
        assertEquals("CN=localhost", presentedSubject(context));

        replaceKeystore(certs.rotatedKeystore);

        assertTrue(context.reloadIfChanged());
        assertEquals("CN=rotated", presentedSubject(context));
    }

    @Test
    @DisplayName("A broken replacement keeps the current certificate in service")
    void brokenReplacementIsIgnored() throws Exception {
        TlsContext context = new TlsContext(keystore, TlsFixtures.PASSWORD, null);

        Files.write(keystore, new byte[] { 1, 2, 3 }); // half-written, or simply wrong
        Files.setLastModifiedTime(keystore, FileTime.fromMillis(System.currentTimeMillis() + 10_000));

        assertFalse(context.reloadIfChanged());
        assertEquals("CN=localhost", presentedSubject(context), "a bad file took the working certificate away");
    }

    @Test
    @DisplayName("A keystore with no private key is refused: there would be nothing to present")
    void keystoreWithoutAKeyIsRefused() throws Exception {
        KeyStore certOnly = KeyStore.getInstance("PKCS12");
        certOnly.load(null, null);
        try (InputStream in = Files.newInputStream(certs.serverCert)) {
            certOnly.setCertificateEntry("server", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        Path noKey = dir.resolve("no-key.p12");
        try (OutputStream out = Files.newOutputStream(noKey)) {
            certOnly.store(out, TlsFixtures.PASSWORD.toCharArray());
        }

        assertThrows(GeneralSecurityException.class, () -> new TlsContext(noKey, TlsFixtures.PASSWORD, null));
    }

    /**
     * Copies a different keystore over the live one and moves its timestamp clearly forward:
     * two writes within one filesystem tick could otherwise share a timestamp.
     */
    private void replaceKeystore(Path replacement) throws Exception {
        Files.copy(replacement, keystore, StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(keystore, FileTime.fromMillis(System.currentTimeMillis() + 10_000));
    }

    /** Runs one handshake through the context and reports whose certificate it presented. */
    private String presentedSubject(TlsContext context) throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> server = CompletableFuture.runAsync(() -> {
                try (Socket accepted = listener.accept()) {
                    accepted.setSoTimeout(5000);
                    try (SSLSocket tls = context.handshake(accepted)) {
                        tls.getInputStream().read(); // hold the connection until the client is done
                    }
                } catch (Exception e) {
                    // The client's side of the handshake reports any failure.
                }
            });
            try (SSLSocket client = TlsFixtures.connect(
                    TlsFixtures.client(null, certs.serverCert, certs.rotatedCert), listener.getLocalPort())) {
                client.startHandshake();
                X509Certificate presented = (X509Certificate) client.getSession().getPeerCertificates()[0];
                return presented.getSubjectX500Principal().getName();
            } finally {
                server.get(5, TimeUnit.SECONDS);
            }
        }
    }
}
