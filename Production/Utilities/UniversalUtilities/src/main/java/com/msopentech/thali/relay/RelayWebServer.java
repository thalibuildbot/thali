/*
Copyright (c) Microsoft Open Technologies, Inc.
All Rights Reserved
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0

THIS CODE IS PROVIDED ON AN *AS IS* BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED,
INCLUDING WITHOUT LIMITATION ANY IMPLIED WARRANTIES OR CONDITIONS OF TITLE, FITNESS FOR A PARTICULAR PURPOSE,
MERCHANTABLITY OR NON-INFRINGEMENT.

See the Apache 2 License for the specific language governing permissions and limitations under the License.
*/

package com.msopentech.thali.relay;

import com.msopentech.thali.CouchDBListener.ThaliListener;
import com.msopentech.thali.utilities.universal.*;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.security.*;
import java.util.HashMap;
import java.util.Map;
import java.io.*;
import java.util.Scanner;

import com.msopentech.thali.nanohttp.NanoHTTPD;

public class RelayWebServer extends NanoHTTPD {

    // Host and port for the relay
    public static final String relayHost = "localhost";
    public static final int relayPort = 58000;

    // Host and port for the TDH
    private final String thaliDeviceHubHost = "localhost";
    private int thaliDeviceHubPort;

    private final KeyStore keyStore;
    private final CreateClientBuilder createClientBuilder;
    private final PublicKey serverPublicKey;

    private final Logger Log = LoggerFactory.getLogger(RelayWebServer.class);

    public RelayWebServer(CreateClientBuilder clientBuilder, File keystoreDirectory) throws UnrecoverableEntryException, KeyManagementException, NoSuchAlgorithmException, KeyStoreException, IOException {
         this(clientBuilder, keystoreDirectory, ThaliListener.DefaultThaliDeviceHubPort);
    }

    public RelayWebServer(CreateClientBuilder clientBuilder, File keystoreDirectory, int thaliDeviceHubPort) throws UnrecoverableEntryException, KeyManagementException, NoSuchAlgorithmException, KeyStoreException, IOException {
        super(relayHost, relayPort);

        this.thaliDeviceHubPort = thaliDeviceHubPort;

        keyStore = ThaliCryptoUtilities.getThaliKeyStoreByAnyMeansNecessary(keystoreDirectory);
        createClientBuilder = clientBuilder;

        // Retrieve server public key
        serverPublicKey = RetrieveServerPublicKey();

        // Prepare client public key
        PrepareClientPublicKey();

        Log.info("RelayWebServer initialized");
    }


    // Simplifies unit testing
    public HTTPSession createSession(TempFileManager tempFileManager, InputStream inputStream, OutputStream outputStream) {
        return new HTTPSession(tempFileManager, inputStream, outputStream);
    }

    @Override
    public Response serve(IHTTPSession session) {
        Method method = session.getMethod();
        String queryString = session.getQueryParameterString();
        String uri = session.getUri();
        Map<String, String> headers = session.getHeaders();
        String requestBody = null;

        // If there is a query string, append it to URI
        if (queryString != null && !queryString.isEmpty()) {
            uri = uri.concat("?" + queryString);
        }

        Log.info("URI: " + uri);
        Log.info("METHOD: " + method.toString());
        Log.info("ORIGIN: " + headers.get("origin"));

        // Handle an OPTIONS request without relaying anything
        if (method.name().equalsIgnoreCase("OPTIONS")) {
            Response optionsResponse = new Response("OK");
            AppendCorsHeaders(optionsResponse, headers);
            optionsResponse.setStatus(Response.Status.OK);
            return optionsResponse;
        }

        // Handle request for local HTTP Key URL
        if (uri.equalsIgnoreCase("/relayutility/localhttpkey"))
        {
            Response httpKeyResponse = new Response("{'httpkey':'427172846852162286227732782294920302420713842275481985193987416465727827594332841946536424113226184082100799979846263322298149064624948841718223595871002487468854371825902763487876571562308540746622769324666936426716328322661006174187432292824234387672928185522171868214215265962193686663919735268176833103576891577777488691009982184273100527780539366654312983859430294532482669543564769536996694547788895124139427128553090154213261621141595978827486497762585373289857851966036673745423578288467224472884115824176989596378133819214820984895929617664984282716722195955274042499434493624'}");
            AppendCorsHeaders(httpKeyResponse, headers);
            httpKeyResponse.setMimeType("application/json");
            httpKeyResponse.setStatus(Response.Status.OK);
            return httpKeyResponse;
        }

        // Get the body of the request as a string
        // and return error responses if we can't parse it
        try {
            requestBody = parseRequestBody(session);
        } catch (IOException ioe) {
            return new Response(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
        } catch (ResponseException re) {
            return new Response(re.getStatus(), MIME_PLAINTEXT, re.getMessage());
        }

        // Make a new request which we will prepare for relaying to TDH
        BasicHttpEntityEnclosingRequest basicHttpRequest = buildRelayRequest(method, uri, headers, requestBody);

        // Define an http connection to send the new relay request to the TDH
        HttpHost httpHost = new HttpHost(thaliDeviceHubHost, thaliDeviceHubPort, "https");

        HttpClient httpClient = null;
        HttpClient httpClientNoServerKey = null;
        try {
            Log.info("Prepping secure HttpClient");

            // Prep an HTTPClient to make the call
            httpClient = createClientBuilder.CreateApacheClient(thaliDeviceHubHost, thaliDeviceHubPort, serverPublicKey, keyStore,
                    ThaliCryptoUtilities.DefaultPassPhrase, null);

        } catch (UnrecoverableKeyException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (KeyStoreException e) {
            e.printStackTrace();
        } catch (KeyManagementException e) {
            e.printStackTrace();
        }

        // Actually make the relayed call
        HttpResponse httpResponse = null;

        if (httpClient != null) {
            try {
                Log.info("Relaying call to TDH: " + httpHost.toURI());
                httpResponse = httpClient.execute(httpHost, basicHttpRequest);
            } catch (IOException e) {
                // return some error
                Log.info("Relay to TDH failed! \n" + ExceptionUtils.getStackTrace(e));
                e.printStackTrace();
                return null;
            }
        }

        // Create response and copy bits
        Response response = null;
        try {
            if (httpResponse != null) {
                response = new Response(IOUtils.toString(httpResponse.getEntity().getContent()));
            }
        } catch (IOException e) {
            Log.info("Preparing response to client failed! \n" + ExceptionUtils.getStackTrace(e));
        }

        // Add appropriate CORS headers
        AppendCorsHeaders(response, headers);

        // Translate status
        // TODO: Default not be OK, make this more robust
        switch (httpResponse.getStatusLine().getStatusCode()) {
            case 201:
                response.setStatus(Response.Status.CREATED);
                break;
            case 400:
                response.setStatus(Response.Status.BAD_REQUEST);
                break;
            case 404:
                response.setStatus(Response.Status.NOT_FOUND);
                break;
            case 412:
                response.setStatus(Response.Status.PRECONDITION_FAILED);
                break;
            case 500:
                response.setStatus(Response.Status.INTERNAL_ERROR);
                break;
            default:
                response.setStatus(Response.Status.OK);
                break;
        }

        // Copy response headers to relayed response
        // Enable chunked transfer where appropriate and ignore date header
        // as NanoHTTPD adds this for us
        for(Header responseHeader : httpResponse.getAllHeaders()) {
            if (!responseHeader.getName().equals("date")) {
                if (responseHeader.getValue().equals("chunked")) {
                    response.setChunkedTransfer(true);
                }
                else if (responseHeader.getName().equals("content-type")) {
                    response.setMimeType(responseHeader.getValue());
                } else {
                    response.addHeader(responseHeader.getName(), responseHeader.getValue());
                }
            }
        }

        return response;
    }

    // Prepares a request which will be forwarded to the TDH by copying headers, body, etc
    private BasicHttpEntityEnclosingRequest buildRelayRequest(Method method, String uri, Map<String, String> headers, String body) {
        BasicHttpEntityEnclosingRequest basicHttpRequest =
                new BasicHttpEntityEnclosingRequest(method.name(), "https://" + thaliDeviceHubHost + ":" + thaliDeviceHubPort + uri);

        // Copy headers from incoming request to new relay request
        for(Map.Entry<String, String> entry : headers.entrySet()) {

            // Skip content-length, the library does this automatically
            if (!entry.getKey().equals("content-length")) {
                basicHttpRequest.setHeader(entry.getKey(), entry.getValue());
            }
        }

        // Copy data from source request to new relay request
        if (body != null && !body.isEmpty()) {
            try {
                StringEntity stringEntity = new StringEntity(body);
                basicHttpRequest.setEntity(stringEntity);
            } catch (UnsupportedEncodingException e) {
                // TODO: Do something here!  Throw and let caller send error response?
                e.printStackTrace();
            }
        }

        return basicHttpRequest;
    }

    // This is a very ugly method of getting the request body out of PUT's and POST's
    // but needs to be replaced with overloads of appropriate NanoHTTPD methods rather than
    // leveraging the temporary file handler that is assigned to PUT by default
    private String parseRequestBody(IHTTPSession session) throws IOException, ResponseException {
        Map<String, String> files = new HashMap<String, String>();
        Method method = session.getMethod();

        if (Method.PUT.equals(method)) {
                session.parseBody(files);
                if (files.size() > 0) {
                    String fileName = files.entrySet().iterator().next().getValue();
                    if (!fileName.isEmpty()) {
                        return new Scanner(new File(fileName)).useDelimiter("\\Z").next();
                    }
                }
        }

        if (Method.POST.equals(method)) {
                session.parseBody(files);
                if (files.size() > 0) {
                    return files.entrySet().iterator().next().getValue();
                }
        }

        return "";
    }

    private void AppendCorsHeaders(Response response, Map<String,String> headers)
    {
        response.addHeader("Access-Control-Allow-Origin", headers.containsKey("origin")?headers.get("origin"):"*");
        response.addHeader("Access-Control-Allow-Credentials", "true");
        response.addHeader("Access-Control-Allow-Headers", "accept, content-type, authorization, origin");
        response.addHeader("Access-Control-Allow-Methods", "GET, PUT, POST, DELETE, HEAD");
    }

    private void PrepareClientPublicKey() throws UnrecoverableEntryException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        org.ektorp.http.HttpClient httpClientWithServerValidation =
                createClientBuilder.CreateEktorpClient(
                        thaliDeviceHubHost,
                        thaliDeviceHubPort,
                        serverPublicKey,
                        keyStore,
                        ThaliCryptoUtilities.DefaultPassPhrase,
                        null);

        ThaliCouchDbInstance thaliCouchDbInstance = new ThaliCouchDbInstance(httpClientWithServerValidation);

        // Set up client key in permission database
        KeyStore.PrivateKeyEntry clientPrivateKeyEntry =
                (KeyStore.PrivateKeyEntry) keyStore.getEntry(ThaliCryptoUtilities.ThaliKeyAlias,
                        new KeyStore.PasswordProtection(ThaliCryptoUtilities.DefaultPassPhrase));

        PublicKey clientPublicKey = clientPrivateKeyEntry.getCertificate().getPublicKey();

        ThaliClientToDeviceHubUtilities.configureKeyInServersKeyDatabase(clientPublicKey, thaliCouchDbInstance);
    }

    private PublicKey RetrieveServerPublicKey() throws UnrecoverableKeyException, KeyManagementException, NoSuchAlgorithmException, KeyStoreException, IOException {
        HttpClient httpClientNoServerValidation =
                createClientBuilder.CreateApacheClient(
                        thaliDeviceHubHost,
                        thaliDeviceHubPort,
                        null,
                        keyStore,
                        ThaliCryptoUtilities.DefaultPassPhrase,
                        null);

        return ThaliClientToDeviceHubUtilities.getServersRootPublicKey(httpClientNoServerValidation);
    }
}

