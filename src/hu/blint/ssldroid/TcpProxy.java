package hu.blint.ssldroid;

import java.net.*;
import java.io.*;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import android.util.Log;

/**
 * This is a modified version of the TcpTunnelGui utility
 * borrowed from the xml.apache.org project.
 * @author Balint Kovacs (blint@blint.hu)
 */
public class TcpProxy {
  int listenPort;
  String tunnelHost;
  int tunnelPort;
  String keyFile, keyPass;
  Relay inRelay, outRelay;

  public TcpProxy() {
  }
  
  public TcpProxy(int listenPort, String tunnelHost, int tunnelPort, String keyFile, String keyPass) {
    this.listenPort = listenPort;
    this.tunnelHost = tunnelHost;
    this.tunnelPort = tunnelPort;
    this.keyFile = keyFile;
    this.keyPass = keyPass;
  }

  public int getListenPort() {
    return listenPort;
  }

  public String getTunnelHost() {
    return tunnelHost;
  }

  public int getTunnelPort() {
    return tunnelPort;
  }

  public String getKeyFile() {
	return keyFile;
  }
  public String getKeyPass() {
	return keyPass;
  }
  
  private static SSLSocketFactory sslSocketFactory;
  public static final SSLSocketFactory getSocketFactory(String pkcsFile, String pwd) {
	    if (sslSocketFactory == null) {
	        try {
	        	 KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
	        	 KeyStore keyStore = KeyStore.getInstance("PKCS12");
	        	 keyStore.load(new FileInputStream(pkcsFile), pwd.toCharArray());
   			     keyManagerFactory.init(keyStore, pwd.toCharArray());
	        	 SSLContext context = SSLContext.getInstance("TLS");
	        	 context.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());
	             sslSocketFactory = (SSLSocketFactory) context.getSocketFactory();
	             
	        } catch (FileNotFoundException e) {
	        	Log.d("SSLDroid","Error loading the client certificate file:" + e.getMessage());
	        } catch (KeyManagementException e) {
	            Log.d("SSLDroid","No SSL algorithm support: " + e.getMessage());
	        } catch (NoSuchAlgorithmException e) {
	            Log.d("SSLDroid","Exception when setting up the Naive key management.");
	        } catch (KeyStoreException e) {
	        	Log.d("SSLDroid","Error setting up keystore:" + e.getMessage());
			} catch (java.security.cert.CertificateException e) {
				Log.d("SSLDroid","Error loading the client certificate:" + e.getMessage());
			} catch (IOException e) {
				Log.d("SSLDroid","Error loading the client certificate file:" + e.getMessage());
			} catch (UnrecoverableKeyException e) {
				Log.d("SSLDroid","Error loading the client certificate:" + e.getMessage());
			}
	    }
	    return sslSocketFactory;
	}
  
  public void serve(int listenPort, String tunnelHost, int tunnelPort, String keyFile, String keyPass) throws IOException {
    final TcpProxy ttg = new TcpProxy(listenPort, tunnelHost, tunnelPort, keyFile, keyPass);
    
    // create the server thread
    Thread server = new Thread() {
      public void run() {
        ServerSocket ss = null;
        try {
          ss = new ServerSocket(ttg.getListenPort());
        } catch (Exception e) {
        	Log.d("SSLDroid", e.getMessage());
            //e.printStackTrace();
            System.exit(1);
        }
        while (true) {
          try {
            Log.d("SSLDroid","Listening for connections on port " + ttg.getListenPort() + " ...");
            // accept the connection from my client
            Socket sc = ss.accept();
            Socket st;
            
            try {
	            st = (SSLSocket) getSocketFactory(ttg.getKeyFile(), ttg.getKeyPass()).createSocket(ttg.getTunnelHost(), ttg.getTunnelPort());
	            ((SSLSocket)st).startHandshake();
	        } catch (Exception e) {
	            Log.d("SSLDroid","SSL FAIL!\n" + e.toString());
	            st = new Socket(ttg.getTunnelHost(),ttg.getTunnelPort());
	        }

            Log.d("SSLDroid","Tunnelling port " + ttg.getListenPort() + " to port " + ttg.getTunnelPort() + " on host " + ttg.getTunnelHost() + " ...");

            // relay the stuff thru
            Thread fromBrowserToServer = new Relay(sc.getInputStream(), st.getOutputStream(), "<<< B2S <<<");
            Thread fromServerToBrowser = new Relay(st.getInputStream(), sc.getOutputStream(), ">>> S2B >>>");

            fromBrowserToServer.start();
            fromServerToBrowser.start();

          } catch (Exception ee) {
            Log.d("SSLDroid","Ouch: "+ ee.getMessage());
            //ee.printStackTrace();
          }
        }
      }
    };
    server.start();
  }

  public static class Relay extends Thread {
    private InputStream in;
    private OutputStream out;
    private final static int BUFSIZ = 4096;
    private byte buf[] = new byte[BUFSIZ];

    public Relay(InputStream in, OutputStream out, String prefix) {
      this.in = in;
      this.out = out;
    }

    public void run() {
      int n = 0;

      try {
        //System.err.println(
        //  "\n\n=== START OF A TRANSMISSION : " + dateFormat.format(new Date()) + " =======================================\n");

        while ((n = in.read(buf)) > 0) {
          out.write(buf, 0, n);
          out.flush();

          for (int i = 0; i < n; i++) {
            if (buf[i] == 7)
              buf[i] = '#';
          }

          //String msg = new String(buf, 0, n);
          //System.out.println(prefix + " : " + msg.length());
          //System.err.println(msg);
        }
      } catch (SocketException e) {
      } catch (IOException e) {
    	  Log.d("SSLDroid", e.getMessage());
    	  //e.printStackTrace();
      } finally {
        try {
          in.close();
          out.close();
        } catch (IOException e) {
        	Log.d("SSLDroid", e.getMessage());
        	//e.printStackTrace();
        }
      }

      //System.out.println("Quiting stream proxy " + prefix + "...");
    }
  }

  //private static final Format dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss,SSS");
}