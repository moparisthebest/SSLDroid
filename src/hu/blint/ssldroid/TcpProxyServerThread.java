package hu.blint.ssldroid;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import android.net.SSLCertificateSocketFactory;
import android.os.Build;
import android.util.Log;

public class TcpProxyServerThread extends Thread {

    String tunnelName;
    int listenPort;
    String tunnelHost;
    int tunnelPort;
    String keyFile, keyPass;
    Relay inRelay, outRelay;
    ServerSocket ss = null;
    int sessionid = 0;
    private SSLCertificateSocketFactory sslSocketFactory;

    public TcpProxyServerThread(ServerSocket ss,String tunnelName, int listenPort, String tunnelHost, int tunnelPort, String keyFile, String keyPass) {
        this.tunnelName = tunnelName;
        this.listenPort = listenPort;
        this.tunnelHost = tunnelHost;
        this.tunnelPort = tunnelPort;
        this.keyFile = keyFile;
        this.keyPass = keyPass;
        this.ss = ss;
    }

    // Create a trust manager that does not validate certificate chains
    // TODO: handle this somehow properly (popup if cert is untrusted?)
    // TODO: cacert + crl should be configurable
    TrustManager[] trustAllCerts = new TrustManager[] {
    new X509TrustManager() {
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return null;
        }
        public void checkClientTrusted(
        java.security.cert.X509Certificate[] certs, String authType) {
        }
        public void checkServerTrusted(
        java.security.cert.X509Certificate[] certs, String authType) {
        }
    }
    };

    public final SSLCertificateSocketFactory getSocketFactory(String pkcsFile,
            String pwd, int sessionid) {
        if (sslSocketFactory == null) {
            try {
                KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("X509");
                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                keyStore.load(new FileInputStream(pkcsFile), pwd.toCharArray());
                keyManagerFactory.init(keyStore, pwd.toCharArray());
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(keyManagerFactory.getKeyManagers(), trustAllCerts,
                             new SecureRandom());
                sslSocketFactory = (SSLCertificateSocketFactory) context.getSocketFactory();
            } catch (FileNotFoundException e) {
                Log.d("SSLDroid", tunnelName+"/"+sessionid+": Error loading the client certificate file:"
                      + e.toString());
            } catch (KeyManagementException e) {
                Log.d("SSLDroid", tunnelName+"/"+sessionid+": No SSL algorithm support: " + e.toString());
            } catch (NoSuchAlgorithmException e) {
                Log.d("SSLDroid", tunnelName+"/"+sessionid+": No common SSL algorithm found: " + e.toString());
            } catch (KeyStoreException e) {
                Log.d("SSLDroid", tunnelName+"/"+sessionid+": Error setting up keystore:" + e.toString());
            } catch (java.security.cert.CertificateException e) {
                Log.d("SSLDroid", tunnelName+"/"+sessionid+": Error loading the client certificate:" + e.toString());
            } catch (IOException e) {
                Log.d("SSLDroid", tunnelName+"/"+sessionid+": Error loading the client certificate file:" + e.toString());
            } catch (UnrecoverableKeyException e) {
                Log.d("SSLDroid", tunnelName+"/"+sessionid+": Error loading the client certificate:" + e.toString());
            }
        }
        return sslSocketFactory;
    }

    public void run() {
        while (true) {
            try {
                Thread fromBrowserToServer = null;
                Thread fromServerToBrowser = null;

                if (isInterrupted()) {
                    Log.d("SSLDroid", tunnelName+"/"+sessionid+": Interrupted server thread, closing sockets...");
                    ss.close();
                    return;
                }
                // accept the connection from my client
                Socket sc = null;
                try {
                    sc = ss.accept();
                    sessionid++;
                } catch (SocketException e) {
                    Log.d("SSLDroid", "Accept failure: " + e.toString());
                }

                Socket st = null;
                SSLCertificateSocketFactory sf = null;
                try {
                    sf = (SSLCertificateSocketFactory) getSocketFactory(this.keyFile, this.keyPass, this.sessionid);
                    st = (SSLSocket) sf.createSocket(this.tunnelHost, this.tunnelPort);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        sf.setHostname(st, this.tunnelHost);
                    }
                    ((SSLSocket) st).startHandshake();
                } catch (IOException e) {
                    Log.d("SSLDroid", tunnelName+"/"+sessionid+": SSL failure: " + e.toString());
                    return;
                }
                catch (Exception e) {
                    Log.d("SSLDroid", tunnelName+"/"+sessionid+": SSL failure: " + e.toString());
                    if (sc != null)
                      {
                        sc.close();
                      }
                    return;
                }

                if (sc == null || st == null) {
                    Log.d("SSLDroid", tunnelName+"/"+sessionid+": Trying socket operation on a null socket, returning");
                    return;
                }
                Log.d("SSLDroid", tunnelName+"/"+sessionid+": Tunnelling port "
                      + listenPort + " to port "
                      + tunnelPort + " on host "
                      + tunnelHost + " ...");

                // relay the stuff through
                fromBrowserToServer = new Relay(
                    this, sc.getInputStream(), st.getOutputStream(), "client", sessionid);
                fromServerToBrowser = new Relay(
                    this, st.getInputStream(), sc.getOutputStream(), "server", sessionid);

                fromBrowserToServer.start();
                fromServerToBrowser.start();

            } catch (IOException ee) {
                Log.d("SSLDroid", tunnelName+"/"+sessionid+": Ouch: " + ee.toString());
            }
        }
    }
};

