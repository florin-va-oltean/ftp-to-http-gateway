/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.qsrlab.sts.ftp.http;

import org.apache.ftpserver.command.AbstractCommand;
import org.apache.ftpserver.ftplet.*;
import org.apache.ftpserver.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;

/**
 * <strong>Custom upload ftp command </strong>
 * 
 * <code>STOR &lt;SP&gt; &lt;pathname&gt; &lt;CRLF&gt;</code><br>
 * 
 * This command does not store actually the file, but it sends it via http.
 * <br/>
 * Copied from STOR command, internal implementation of Apache Mina Ftp
 *
 * @author Florin Oltean
 */
public class STOR_STS extends AbstractCommand {

    private final Logger logger = LoggerFactory.getLogger(STOR_STS.class);

    private String httpUrl;

    private String httpAuthentication;

    private int httpReadTimeout;

    private int httpConnectTimeout;

    /**
     * Execute command.
     */
    public void execute(final FtpIoSession session,
            final FtpServerContext context, final FtpRequest request)
            throws IOException, FtpException {

        try {

            // get state variable
            long skipLen = session.getFileOffset();

            // argument check
            String fileName = request.getArgument();
            if (fileName == null) {
                session.write(new DefaultFtpReply(
                        FtpReply.REPLY_501_SYNTAX_ERROR_IN_PARAMETERS_OR_ARGUMENTS,
                        "Incorrect format for STOR command, expected STOR <filename>"));
                return;
            }

            DataConnectionFactory connFactory = session.getDataConnection();
            if (connFactory instanceof IODataConnectionFactory) {
                InetAddress address = ((IODataConnectionFactory) connFactory)
                        .getInetAddress();
                if (address == null) {
                    session.write(new DefaultFtpReply(
                            FtpReply.REPLY_503_BAD_SEQUENCE_OF_COMMANDS,
                            "PORT or PASV must be issued first"));
                    return;
                }
            }

            // get filename
            FtpFile file = null;
            try {
                file = session.getFileSystemView().getFile(fileName);
            } catch (Exception ex) {
                logger.debug("Exception getting file object", ex);
            }
            if (file == null) {
                session.write(new DefaultFtpReply(
                        FtpReply.REPLY_550_REQUESTED_ACTION_NOT_TAKEN,
                        "Invalid file operation, cannot get file object from file system"));
                return;
            }
            fileName = file.getAbsolutePath();

            // get permission
            if (!file.isWritable()) {
                session.write(new DefaultFtpReply(
                        FtpReply.REPLY_550_REQUESTED_ACTION_NOT_TAKEN,
                        "Invalid file operation, file is not writable"));
                return;
            }

            // get data connection
            session.write(
                    new DefaultFtpReply(
                            FtpReply.REPLY_150_FILE_STATUS_OKAY,
                            "File status obtained succesfully (intermediate reply); start transfer of actual bytes")).awaitUninterruptibly(10000);

            DataConnection dataConnection;
            try {
                dataConnection = session.getDataConnection().openConnection();
            } catch (Exception e) {
                logger.debug("Exception getting the input data stream", e);
                session.write(new DefaultFtpReply(
                        FtpReply.REPLY_425_CANT_OPEN_DATA_CONNECTION,
                        "Cannot open data connection to transfer bytes"));
                return;
            }

            // transfer data
            boolean failure = false;
            OutputStream outStream = null;
            int respCode = 0;
            try {
                outStream = file.createOutputStream(skipLen);
                dataConnection.transferFromClient(session.getFtpletSession(), outStream);
                // attempt to close the output stream so that errors in 
                // closing it will return an error to the client (FTPSERVER-119) 
                outStream.close();
                respCode = storeViaHttpPost(session,file);
                file.delete();
                logger.warn("File uploaded locally {} and response code from http destination: {}", fileName, respCode);
                failure = (respCode<200) || (respCode>299);
            } catch (SocketException ex) {
                logger.debug("Socket exception during data transfer", ex);
                failure = true;
                session.write(
                        new DefaultFtpReply(
                                FtpReply.REPLY_426_CONNECTION_CLOSED_TRANSFER_ABORTED,
                                "Transfer aborted, socket problem: "+ex.getMessage()));
            } catch (IOException ex) {
                logger.debug("IOException during data transfer", ex);
                failure = true;
                session.write(new DefaultFtpReply(FtpReply.REPLY_551_REQUESTED_ACTION_ABORTED_PAGE_TYPE_UNKNOWN,
                                        "Command aborted, I/O problem: "+ex.getMessage()));
            } finally {
                // make sure we really close the output stream
                logger.info("deleting file from filesystem");
                file.delete();
            }

            // if data transfer ok - send transfer complete message
            if (!failure) {
                session.write(new DefaultFtpReply(FtpReply.REPLY_226_CLOSING_DATA_CONNECTION,
                                "Command succeeded! File is also uploaded on http server: " + httpUrl));
            }else{
                session.write(new DefaultFtpReply(FtpReply.REPLY_532_NEED_ACCOUNT_FOR_STORING_FILES,
                        "Failure to process command; http response: " + respCode));
            }
        } finally {
            session.resetState();
            session.getDataConnection().closeDataConnection();
        }
    }

    private int storeViaHttpPost(final FtpIoSession session, final FtpFile file){
        HttpURLConnection  http = null;
        try (InputStream inputStream = file.createInputStream(session.getFileOffset())){
            String fileName = file.getAbsolutePath();
            byte[] b = new byte[inputStream.available()];
            inputStream.read(b);
            URL url = new URL(httpUrl + fileName);
            URLConnection con = url.openConnection();
            http = (HttpURLConnection) con;
            http.setRequestMethod("POST");
            http.setRequestProperty("Authorization", httpAuthentication);
            http.setDoOutput(true);
            http.setReadTimeout(httpReadTimeout);
            http.setConnectTimeout(httpConnectTimeout);
            http.connect();
            try (OutputStream os = http.getOutputStream()) {
                os.write(b);
            }
            int ret = http.getResponseCode();
            http.disconnect();
            logger.info("File successfully uploaded via POST to {}/{}; return code is {}",httpUrl,fileName,ret);
            return ret;
        }catch(IOException ex){
            logger.error("Error sending file via POST",ex);
            if(http!=null){
                http.disconnect();
            }
            return 0;
        }
    }

    public String getHttpUrl() {
        return httpUrl;
    }

    public void setHttpUrl(String httpUrl) {
        this.httpUrl = httpUrl;
    }

    public String getHttpAuthentication() {
        return httpAuthentication;
    }

    public void setHttpAuthentication(String httpAuthentication) {
        this.httpAuthentication = httpAuthentication;
    }

    public int getHttpReadTimeout() {
        return httpReadTimeout;
    }

    public void setHttpReadTimeout(int httpReadTimeout) {
        this.httpReadTimeout = httpReadTimeout;
    }

    public int getHttpConnectTimeout() {
        return httpConnectTimeout;
    }

    public void setHttpConnectTimeout(int httpConnectTimeout) {
        this.httpConnectTimeout = httpConnectTimeout;
    }
}
