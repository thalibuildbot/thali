package com.codeplex.peerly.couchdbtest;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.codeplex.peerly.common.KeyStoreManagement;
import com.codeplex.peerly.couchdbserverandroid.R;
import com.couchbase.cblite.CBLServer;
import com.couchbase.cblite.listener.CBLListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Properties;

import Acme.Serve.SSLAcceptor;
import Acme.Serve.Serve;

public class MainActivity extends Activity {
    private CBLListener cblListener = null;
    private final int defaultCouchPort = 9898;
    private final String tjwsSslAcceptor = "com.couchbase.cblite.listener.CBLSSLAcceptor";
    private final String deviceKeyAlias = "com.codeplex.peerly.names.devicealias";
    private final String keystoreFileName = "com.codeplex.peerly.names.keystore";
    private final Logger Log = LoggerFactory.getLogger(MainActivity.class);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .add(R.id.container, new PlaceholderFragment())
                    .commit();
        }

        MakeSureDeviceKeyStoreExists();

        String filesDir = getFilesDir().getAbsolutePath();
        try {
            // Start the CouchDB Lite server
            CBLServer server = new CBLServer (filesDir);

            Properties tjwsProperties = new Properties();
            tjwsProperties.setProperty(Serve.ARG_ACCEPTOR_CLASS, tjwsSslAcceptor);
            tjwsProperties.setProperty(SSLAcceptor.ARG_KEYSTORETYPE, KeyStoreManagement.PrivateKeyHolderFormat);
            tjwsProperties.setProperty(SSLAcceptor.ARG_KEYSTOREFILE, GetKeyStoreAbsolutePath());
            tjwsProperties.setProperty(SSLAcceptor.ARG_KEYSTOREPASS, new String(KeyStoreManagement.DefaultPassPhrase));

            cblListener = new CBLListener(server, defaultCouchPort, tjwsProperties);

            cblListener.start();

            // Lets see if the server is running!
            int port = cblListener.getListenPort();
            //new TestAsynchCouchClient().execute(port);

        } catch (IOException e) {
            Log.error("Error starting TDServer", e);
        }

        Log.debug("Got this far, woohoo!");
    }

    /**
     * Return the absolute path of the Peerly device key store.
     *
     * I had wanted this to just be a property of the class but getFilesDir() doesn't seem to be
     * initialized until onCreate is called so setting the value either as a property or via
     * the constructor won't work. Hence this method.
     * @return
     */
    private String GetKeyStoreAbsolutePath() {
       return new File(getFilesDir(), keystoreFileName).getAbsolutePath();
    }

    /**
     * If no key store exists to hold the device's keying information than this method
     * will create on.
     */
    private void MakeSureDeviceKeyStoreExists() {
        File keyStoreFile = new File(GetKeyStoreAbsolutePath());

        if (keyStoreFile.exists()) {
            // TODO: We need to check if the device's cert is expired and renew it, but this will probably require getting a new root chain, so let's wait until that's figured out
            return;
        }

        KeyStore keyStore =
                KeyStoreManagement.CreatePKCS12KeyStoreWithNewPublicPrivateKeyPair(
                        KeyStoreManagement.GeneratePeerlyAcceptablePublicPrivateKeyPair(), deviceKeyAlias, KeyStoreManagement.DefaultPassPhrase);

        // TODO: I really need to figure out if I can safely use Java 7 features like try with resources and Android, the fact that Android Studio defaults to not support Java 7 makes me very nervous
        FileOutputStream fileOutputStream = null;
        try {
            // Yes this can swallow exceptions (if you got an exception inside this try and then the finally has an exception, but given what I'm doing here I don't care.
            try {
                fileOutputStream =  new FileOutputStream(keyStoreFile);
                keyStore.store(new FileOutputStream(keyStoreFile), KeyStoreManagement.DefaultPassPhrase);
            } finally {
                if (fileOutputStream != null) {
                    fileOutputStream.close();
                }
            }
        } catch (Exception e) {
            Log.error(e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    protected void onDestroy() {
        if (cblListener != null) {
            cblListener.stop();
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        switch (item.getItemId()) {
            case R.id.action_settings:
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * A placeholder fragment containing a simple view.
     */
    public static class PlaceholderFragment extends Fragment {

        public PlaceholderFragment() {
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.fragment_main, container, false);
            return rootView;
        }
    }

}
