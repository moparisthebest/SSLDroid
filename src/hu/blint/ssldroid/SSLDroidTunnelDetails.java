package hu.blint.ssldroid;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import java.security.cert.Certificate;
import javax.security.cert.CertificateExpiredException;
import javax.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnClickListener;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import hu.blint.ssldroid.db.SSLDroidDbAdapter;

//TODO: cacert + crl should be configurable for the tunnel
//TODO: test connection button

public class SSLDroidTunnelDetails extends Activity {
    private EditText name;
    private EditText localport;
    private EditText remotehost;
    private EditText remoteport;
    private EditText pkcsfile;
    private EditText pkcspass;
    private Long rowId;
    private Boolean doClone = false;
    private SSLDroidDbAdapter dbHelper;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        dbHelper = new SSLDroidDbAdapter(this);
        dbHelper.open();
        setContentView(R.layout.tunnel_details);

        Button confirmButton = (Button) findViewById(R.id.tunnel_apply_button);
        name = (EditText) findViewById(R.id.name);
        localport = (EditText) findViewById(R.id.localport);
        remotehost = (EditText) findViewById(R.id.remotehost);
        remoteport = (EditText) findViewById(R.id.remoteport);
        pkcsfile = (EditText) findViewById(R.id.pkcsfile);
        pkcspass = (EditText) findViewById(R.id.pkcspass);
        Button pickFile = (Button) findViewById(R.id.pickFile);

        pickFile.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                pickFileSimple();
            }
        });

        rowId = null;
        Bundle extras = getIntent().getExtras();
        rowId = (bundle == null) ? null : (Long) bundle
                .getSerializable(SSLDroidDbAdapter.KEY_ROWID);
        if (extras != null) {
            rowId = extras.getLong(SSLDroidDbAdapter.KEY_ROWID);
            doClone = extras.getBoolean("doClone", false);
        }
        populateFields();
        confirmButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                if (name.getText().length() == 0) {
                    Toast.makeText(getBaseContext(), "Required tunnel name parameter not set up, skipping save", Toast.LENGTH_LONG).show();
                    return;
                }
                //local port validation
                if (localport.getText().length() == 0) {
                    Toast.makeText(getBaseContext(), "Required local port parameter not set up, skipping save", Toast.LENGTH_LONG).show();
                    return;
                }
                else {
                    //local port should be between 1025-65535
                    int cPort = 0;
                    try {
                        cPort = Integer.parseInt(localport.getText().toString());
                    } catch (NumberFormatException e) {
                        Toast.makeText(getBaseContext(), "Local port parameter has invalid number format", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (cPort < 1025 || cPort > 65535) {
                        Toast.makeText(getBaseContext(), "Local port parameter not in valid range (1025-65535)", Toast.LENGTH_LONG).show();
                        return;
                    }
                    //check if the requested port is colliding with a port already configured for another tunnel
                    SSLDroidDbAdapter dbHelper = new SSLDroidDbAdapter(getBaseContext());
                    dbHelper.open();
                    Cursor cursor = dbHelper.fetchAllLocalPorts();
                    startManagingCursor(cursor);
                    while (cursor.moveToNext()) {
                        String cDbName = cursor.getString(cursor.getColumnIndexOrThrow(SSLDroidDbAdapter.KEY_NAME));
                        int cDbPort = cursor.getInt(cursor.getColumnIndexOrThrow(SSLDroidDbAdapter.KEY_LOCALPORT));
                        if (cPort == cDbPort && !cDbName.contentEquals(name.getText().toString())) {
                            Toast.makeText(getBaseContext(), "Local port already configured in tunnel '"+cDbName+"', please change...", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                }
                //remote host validation
                if (remotehost.getText().length() == 0) {
                    Toast.makeText(getBaseContext(), "Required remote host parameter not set up, skipping save", Toast.LENGTH_LONG).show();
                    return;
                }
                //remote port validation
                if (remoteport.getText().length() == 0) {
                    Toast.makeText(getBaseContext(), "Required remote port parameter not set up, skipping save", Toast.LENGTH_LONG).show();
                    return;
                }
                else {
                    //remote port should be between 1025-65535
                    int cPort = 0;
                    try {
                        cPort = Integer.parseInt(remoteport.getText().toString());
                    } catch (NumberFormatException e) {
                        Toast.makeText(getBaseContext(), "Remote port parameter has invalid number format", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (cPort < 1 || cPort > 65535) {
                        Toast.makeText(getBaseContext(), "Remote port parameter not in valid range (1-65535)", Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                if (pkcsfile.getText().length() != 0) {
                    // try to open pkcs12 file with password
                    String cPkcsFile = pkcsfile.getText().toString();
                    String cPkcsPass = pkcspass.getText().toString();
                    try {
                        if (checkKeys(cPkcsFile, cPkcsPass) == false) {
                            return;
                        }
                    } catch (Exception e) {
                        Toast.makeText(getBaseContext(), "PKCS12 problem: "+e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                saveState();
                setResult(RESULT_OK);
                finish();
            }

        });
    }

    final List<File> getFileNames(File url, File baseurl)
    {
        final List<File> names = new LinkedList<File>();
        File[] files = url.listFiles();
        if (files != null && files.length > 0) {
            for (File file : url.listFiles()) {
                if (file.getName().startsWith("."))
                    continue;
                names.add(file);
            }
        }
        return names;
    }

    private void showFiles(final List<File> names, final File baseurl) {
        final String[] namesList = new String[names.size()]; // = names.toArray(new String[] {});
        ListIterator<File> filelist = names.listIterator();
        int i = 0;
        while (filelist.hasNext()) {
            File file = filelist.next();
            if (file.isDirectory())
                namesList[i] = file.getAbsolutePath().replaceFirst(baseurl+"/", "")+" (...)";
            else
                namesList[i] = file.getAbsolutePath().replaceFirst(baseurl+"/", "");
            i++;
        }
        //Log.d("SSLDroid", "Gathered file names: "+namesList.toString());

        // prompt user to select any file from the sdcard root
        new AlertDialog.Builder(SSLDroidTunnelDetails.this)
        .setTitle(R.string.pkcsfile_pick)
        .setItems(namesList, new OnClickListener() {
            public void onClick(DialogInterface arg0, int arg1) {
                File name = names.get(arg1);
                if (name.isDirectory()) {
                    List<File> names_ = getFileNames(name, baseurl);
                    Collections.sort(names_);
                    if (names_.size() > 0) {
                        showFiles(names_, baseurl);
                    }
                    else
                        Toast.makeText(getBaseContext(), "Empty directory", Toast.LENGTH_LONG).show();
                }
                if (name.isFile()) {
                    pkcsfile.setText(name.getAbsolutePath());
                    pkcspass.requestFocus();
                }
            }
        })
        //create a Back button (shouldn't go above base URL)
        .setNeutralButton(R.string.back, new OnClickListener() {
            public void onClick(DialogInterface arg0, int arg1) {
                if (names.size() == 0)
                    return;
                File name = names.get(0);
                if (!name.getParentFile().equals(baseurl)) {
                    List<File> names_ = getFileNames(name.getParentFile().getParentFile(), baseurl);
                    Collections.sort(names_);
                    if (names_.size() > 0) {
                        showFiles(names_, baseurl);
                    }
                    else
                        return;
                }
            }
        })
        .setNegativeButton(android.R.string.cancel, null).create().show();
    }

    //pick a file from /sdcard, courtesy of ConnectBot
    private void pickFileSimple() {
        // build list of all files in sdcard root
        final File sdcard = Environment.getExternalStorageDirectory();
        Log.d("SSLDroid", "SD Card location: "+sdcard.toString());

        // Don't show a dialog if the SD card is completely absent.
        final String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)
                && !Environment.MEDIA_MOUNTED.equals(state)) {
            new AlertDialog.Builder(SSLDroidTunnelDetails.this)
            .setMessage(R.string.alert_sdcard_absent)
            .setNegativeButton(android.R.string.cancel, null).create().show();
            return;
        }

        List<File> names = new LinkedList<File>();
        names = getFileNames(sdcard, sdcard);
        Collections.sort(names);
        showFiles(names, sdcard);
    }

    private void populateFields() {
        if (rowId != null) {
            Cursor Tunnel = dbHelper.fetchTunnel(rowId);
            startManagingCursor(Tunnel);

            if(!doClone){
        	name.setText(Tunnel.getString(Tunnel
                                              .getColumnIndexOrThrow(SSLDroidDbAdapter.KEY_NAME)));
        	localport.setText(Tunnel.getString(Tunnel
                                                   .getColumnIndexOrThrow(SSLDroidDbAdapter.KEY_LOCALPORT)));
            }
            remotehost.setText(Tunnel.getString(Tunnel
                                                .getColumnIndexOrThrow(SSLDroidDbAdapter.KEY_REMOTEHOST)));
            remoteport.setText(Tunnel.getString(Tunnel
                                                .getColumnIndexOrThrow(SSLDroidDbAdapter.KEY_REMOTEPORT)));
            pkcsfile.setText(Tunnel.getString(Tunnel
                                              .getColumnIndexOrThrow(SSLDroidDbAdapter.KEY_PKCSFILE)));
            pkcspass.setText(Tunnel.getString(Tunnel
                                              .getColumnIndexOrThrow(SSLDroidDbAdapter.KEY_PKCSPASS)));
        }
    }

    public boolean checkKeys(String inCertPath, String passw) throws Exception {
        try {
            FileInputStream in_cert = new FileInputStream(inCertPath);
            KeyStore myStore = KeyStore.getInstance("PKCS12");
            myStore.load(in_cert, passw.toCharArray());
            Enumeration<String> eAliases = myStore.aliases();
            while (eAliases.hasMoreElements()) {
                String strAlias = (String) eAliases.nextElement();
                if (myStore.isKeyEntry(strAlias)) {
                    // try to retrieve the private key part from PKCS12 certificate
                    myStore.getKey(strAlias, passw.toCharArray());
                    Certificate mycrt = myStore.getCertificate(strAlias);
                    X509Certificate mycert = X509Certificate.getInstance(mycrt.getEncoded());
                    try {
                	mycert.checkValidity();
                    } catch (CertificateExpiredException e) {
                        Toast.makeText(getBaseContext(), "PKCS12 problem: "+e.getMessage(), Toast.LENGTH_LONG).show();
                        return false;                	
                    }
                }
            }

        } catch (KeyStoreException e) {
            Toast.makeText(getBaseContext(), "PKCS12 problem: "+e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        } catch (NoSuchAlgorithmException e) {
            Toast.makeText(getBaseContext(), "PKCS12 problem: "+e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        } catch (CertificateException e) {
            Toast.makeText(getBaseContext(), "PKCS12 problem: "+e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        } catch (IOException e) {
            Toast.makeText(getBaseContext(), "PKCS12 problem: "+e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        } catch (UnrecoverableKeyException e) {
            Toast.makeText(getBaseContext(), "PKCS12 problem: "+e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }


    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        saveState();
        outState.putSerializable(SSLDroidDbAdapter.KEY_ROWID, rowId);
    }

    @Override
    protected void onPause() {
        super.onPause();
        //saveState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        populateFields();
    }

    private void saveState() {
        String sName = name.getText().toString();
        int sLocalport = 0;
        try {
            sLocalport = Integer.parseInt(localport.getText().toString());
        } catch (NumberFormatException e) {
        }
        String sRemotehost = remotehost.getText().toString();
        int sRemoteport = 0;
        try {
            sRemoteport = Integer.parseInt(remoteport.getText().toString());
        } catch (NumberFormatException e) {
        }
        String sPkcsfile = pkcsfile.getText().toString();
        String sPkcspass = pkcspass.getText().toString();

        //make sure that we have all of our values correctly set
        if (sName.length() == 0) {
            return;
        }
        if (sLocalport == 0) {
            return;
        }
        if (sRemotehost.length() == 0) {
            return;
        }
        if (sRemoteport == 0) {
            return;
        }

        if (rowId == null || doClone) {
            long id = dbHelper.createTunnel(sName, sLocalport, sRemotehost,
                                            sRemoteport, sPkcsfile, sPkcspass);
            if (id > 0) {
                rowId = id;
            }
        } else {
            dbHelper.updateTunnel(rowId, sName, sLocalport, sRemotehost, sRemoteport,
                                  sPkcsfile, sPkcspass);
        }
        Log.d("SSLDroid", "Saving settings...");

        //restart the service
        stopService(new Intent(this, SSLDroid.class));
        startService(new Intent(this, SSLDroid.class));
        Log.d("SSLDroid", "Restarting service after settings save...");

    }
}

