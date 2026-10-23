package redis.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collections;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * The server side of TLS: the certificate the server presents, the client certificates it
 * accepts, and the protocol versions it speaks.
 *
 * <p>Connections are accepted as plain TCP and wrapped here, on the connection's own thread,
 * rather than by a TLS server socket. That keeps every handshake off the accept thread, so a
 * client that stalls one holds up only itself; and since each connection is wrapped with
 * whatever settings are current, a renewed certificate takes effect without a restart.
 */
public final class TlsContext {

    private static final Logger LOG = Logger.getLogger(TlsContext.class.getName());

    /** TLS 1.0 and 1.1 are broken. The JDK disables them by default; this keeps them out. */
    private static final String[] PROTOCOLS = { "TLSv1.3", "TLSv1.2" };

    private final Path keystore;
    private final char[] keystorePassword;
    private final Path clientCa; // null unless client certificates are required

    private volatile SSLSocketFactory factory;
    private FileTime keystoreLoaded; // guarded by this
    private FileTime clientCaLoaded; // guarded by this

    /** Loads the files now, so a wrong path or password stops the server from starting. */
    public TlsContext(Path keystore, String keystorePassword, Path clientCa)
            throws IOException, GeneralSecurityException {
        this.keystore = keystore;
        this.keystorePassword = keystorePassword.toCharArray();
        this.clientCa = clientCa;
        this.keystoreLoaded = Files.getLastModifiedTime(keystore);
        this.clientCaLoaded = clientCa == null ? null : Files.getLastModifiedTime(clientCa);
        this.factory = load();
    }

    /**
     * Wraps an accepted connection in server-side TLS and completes the handshake.
     *
     * @throws IOException if the handshake fails: a plaintext client, a client certificate
     *                     that is missing or untrusted, no protocol in common, or the
     *                     connection closing underneath it
     */
    public SSLSocket handshake(Socket accepted) throws IOException {
        SSLSocket socket = (SSLSocket) factory.createSocket(accepted, null, true); // server mode
        socket.setEnabledProtocols(PROTOCOLS);
        socket.setNeedClientAuth(clientCa != null);
        socket.startHandshake();
        return socket;
    }

    /**
     * Loads the files again if either has changed since it was last read. A replacement that
     * fails to load is reported once and otherwise ignored: serving the previous certificate
     * is better than refusing every new connection.
     *
     * @return whether new settings are now in use
     */
    public synchronized boolean reloadIfChanged() {
        FileTime keystoreNow;
        FileTime clientCaNow;
        try {
            keystoreNow = Files.getLastModifiedTime(keystore);
            clientCaNow = clientCa == null ? null : Files.getLastModifiedTime(clientCa);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not check the TLS files for changes", e);
            return false;
        }
        if (keystoreNow.equals(keystoreLoaded) && Objects.equals(clientCaNow, clientCaLoaded)) {
            return false;
        }
        // Recorded before loading, so a broken file is reported once rather than every check.
        // Fixing it changes its time again, which triggers another attempt.
        keystoreLoaded = keystoreNow;
        clientCaLoaded = clientCaNow;
        try {
            factory = load();
        } catch (IOException | GeneralSecurityException e) {
            LOG.log(Level.WARNING, "Could not reload TLS settings; still using the previous ones", e);
            return false;
        }
        LOG.info(() -> "Reloaded TLS settings from " + keystore);
        return true;
    }

    private SSLSocketFactory load() throws IOException, GeneralSecurityException {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            keys.load(in, keystorePassword);
        }
        boolean hasKey = false;
        for (String alias : Collections.list(keys.aliases())) {
            hasKey |= keys.isKeyEntry(alias);
        }
        if (!hasKey) {
            throw new GeneralSecurityException(
                    keystore + " holds no private key, so there is no certificate to present");
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, keystorePassword);

        TrustManager[] trusted = null; // the JDK default; unused unless clients must present a certificate
        if (clientCa != null) {
            KeyStore authorities = KeyStore.getInstance("PKCS12");
            authorities.load(null, null);
            try (InputStream in = Files.newInputStream(clientCa)) {
                int n = 0;
                for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    authorities.setCertificateEntry("client-ca-" + n++, cert);
                }
            }
            if (authorities.size() == 0) {
                throw new GeneralSecurityException(clientCa + " holds no certificates");
            }
            TrustManagerFactory trustManagers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(authorities);
            trusted = trustManagers.getTrustManagers();
        }

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trusted, null);
        return context.getSocketFactory();
    }
}
