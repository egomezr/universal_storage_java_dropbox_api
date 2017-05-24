package com.universal.storage;

import com.universal.util.PathValidator;
import com.universal.error.UniversalIOException;
import com.universal.storage.settings.UniversalSettings;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.apache.commons.io.FileUtils;
import java.security.GeneralSecurityException;

import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.WriteMode;
import com.dropbox.core.DbxHost;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.DbxException;
import com.dropbox.core.v2.files.UploadSessionCursor;
import com.dropbox.core.v2.files.CommitInfo;
import com.dropbox.core.RetryException;
import com.dropbox.core.NetworkIOException;
import com.dropbox.core.v2.files.UploadSessionLookupErrorException;
import com.dropbox.core.v2.files.UploadSessionFinishErrorException;

/**
 * This class is the implementation of a storage that will manage files within a dropbox app folder.
 * This implementation will manage file using a dropbox app folder as a root storage.
 */
public class UniversalDropboxStorage extends UniversalStorage {
    private final long CHUNKED_UPLOAD_CHUNK_SIZE = 8L << 20; // 8MiB
    private static final int CHUNKED_UPLOAD_MAX_ATTEMPTS = 5;

    private DbxClientV2 dbxClient;
    /**
     * This constructor receives the settings for this new FileStorage instance.
     * 
     * @param settings for this new FileStorage instance.
     */
    public UniversalDropboxStorage(UniversalSettings settings) {
        super(settings);
        DbxRequestConfig requestConfig = new DbxRequestConfig("UniversalStorage");
        dbxClient = new DbxClientV2(requestConfig, this.settings.getDropboxAccessToken(), DbxHost.DEFAULT);
    }

    /**
     * This method stores a file within the storage provider according to the current settings.
     * The method will replace the file if already exists within the root.
     * 
     * For exemple:
     * 
     * path == null
     * File = /var/wwww/html/index.html
     * Root = APP_FOLDER/storage/
     * Copied File = APP_FOLDER/storage/index.html
     * 
     * path == "/myfolder"
     * File = /var/wwww/html/index.html
     * Root = APP_FOLDER/storage/
     * Copied File = APP_FOLDER/storage/myfolder/index.html
     * 
     * If this file is a folder, a error will be thrown informing that should call the createFolder method.
     * 
     * Validations:
     * Validates if root is a bucket.
     * 
     * @param file to be stored within the storage.
     * @param path is the path for this new file within the root.
     * @throws UniversalIOException when a specific IO error occurs.
     */
    void storeFile(File file, String path) throws UniversalIOException {
        if (file.isDirectory()) {
            throw new UniversalIOException(file.getName() + " is a folder.  You should call the createFolder method.");
        }

        if (path != null) {
            path = path.trim().replace("\\", "/");
            
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
        }

        if (path != null && !path.trim().equals("") && !path.trim().startsWith("/")) {
            path = "/" + path;            
        }

        if (path == null || path.trim().equals("")) {
            path = "/" + this.settings.getRoot();
        } else {
            path = "/" + this.settings.getRoot() + path;
        }

        if (file.length() <= (2 * CHUNKED_UPLOAD_CHUNK_SIZE)) {
            uploadFile(file, path);
        } else {
            uploadFileAsChunks(file, path);
        }
    }

    /**
     * This method uploads a file using the passed file and path.  This method will split the file as chunks.
     * 
     * @param file in context.
     * @param path where the file will be stored in Dropbox.
     */
    private void uploadFileAsChunks(File file, String path) throws UniversalIOException {
        try {
            long size = file.length();

            long uploaded = 0L;
            // Chunked uploads have 3 phases, each of which can accept uploaded bytes:
            //
            //    (1)  Start: initiate the upload and get an upload session ID
            //    (2) Append: upload chunks of the file to append to our session
            //    (3) Finish: commit the upload and close the session
            //
            // We track how many bytes we uploaded to determine which phase we should be in.
            String sessionId = null;
            for (int i = 0; i < CHUNKED_UPLOAD_MAX_ATTEMPTS; ++i) {
                try {
                    InputStream in = new FileInputStream(file);

                    // if this is a retry, make sure seek to the correct offset
                    in.skip(uploaded);

                    // (1) Start
                    if (sessionId == null) {
                        sessionId = dbxClient.files().uploadSessionStart()
                            .uploadAndFinish(in, CHUNKED_UPLOAD_CHUNK_SIZE)
                            .getSessionId();

                            uploaded += CHUNKED_UPLOAD_CHUNK_SIZE;
                    }

                    UploadSessionCursor cursor = new UploadSessionCursor(sessionId, uploaded);

                    // (2) Append
                    while ((size - uploaded) > CHUNKED_UPLOAD_CHUNK_SIZE) {
                        dbxClient.files().uploadSessionAppendV2(cursor)
                            .uploadAndFinish(in, CHUNKED_UPLOAD_CHUNK_SIZE);

                        uploaded += CHUNKED_UPLOAD_CHUNK_SIZE;

                        cursor = new UploadSessionCursor(sessionId, uploaded);
                    }

                    // (3) Finish
                    long remaining = size - uploaded;
                    CommitInfo commitInfo = CommitInfo.newBuilder(path)
                        .withMode(WriteMode.ADD)
                        .withClientModified(new Date(file.lastModified()))
                        .build();

                    dbxClient.files().uploadSessionFinish(cursor, commitInfo).uploadAndFinish(in, remaining);
                } catch (RetryException ex) {
                    // RetryExceptions are never automatically retried by the client for uploads. Must
                    // catch this exception even if DbxRequestConfig.getMaxRetries() > 0.
                    sleepQuietly(ex.getBackoffMillis());
                    continue;
                } catch (NetworkIOException ex) {
                    // network issue with Dropbox (maybe a timeout?) try again
                    continue;
                } catch (UploadSessionLookupErrorException ex) {
                    if (ex.errorValue.isIncorrectOffset()) {
                        uploaded = ex.errorValue.getIncorrectOffsetValue().getCorrectOffset();
                        
                        continue;
                    } else {
                        throw new UniversalIOException(ex.getMessage());
                    }
                } catch (UploadSessionFinishErrorException ex) {
                    if (ex.errorValue.isLookupFailed() && ex.errorValue.getLookupFailedValue().isIncorrectOffset()) {
                        uploaded = ex.errorValue.getLookupFailedValue().getIncorrectOffsetValue().getCorrectOffset();

                        continue;
                    } else {
                        throw new UniversalIOException(ex.getMessage());
                    }
                } catch (DbxException ex) {
                    throw new UniversalIOException(ex.getMessage());
                } catch (IOException ex) {
                    throw new UniversalIOException(ex.getMessage());
                }
            }
        } catch (Exception e) {
            throw new UniversalIOException(e.getMessage());
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignore) {}
    }

    /**
     * This method uploads a file using the passed file and path.
     * 
     * @param file in context.
     * @param path where the file will be stored in Dropbox.
     */
    private void uploadFile(File file, String path) throws UniversalIOException {
        try {
            dbxClient.files().uploadBuilder(path + "/" + file.getName())
                .withMode(WriteMode.OVERWRITE)
                .withClientModified(new Date(file.lastModified()))
                .uploadAndFinish(new FileInputStream(file));
        } catch (Exception e) {
            throw new UniversalIOException(e.getMessage());
        }
    }

    /**
     * This method stores a file according to the provided path within the storage provider 
     * according to the current settings.
     * 
     * @param path pointing to the file which will be stored within the storage.
     * @throws UniversalIOException when a specific IO error occurs.
     */
    void storeFile(String path) throws UniversalIOException {
        this.storeFile(new File(path), null);
    }

    /**
     * This method stores a file according to the provided path within the storage provider according to the current settings.
     * 
     * @param path pointing to the file which will be stored within the storage.
     * @param targetPath is the path within the storage.
     * 
     * @throws UniversalIOException when a specific IO error occurs.
     */
    void storeFile(String path, String targetPath) throws UniversalIOException {
        PathValidator.validatePath(path);
        PathValidator.validatePath(targetPath);

        this.storeFile(new File(path), targetPath);
    }

    /**
     * This method removes a file from the storage.  This method will use the path parameter 
     * to localte the file and remove it from the storage.  The deletion process will delete the last
     * version of this object.
     * 
     * Root = APP_FOLDER/storage/
     * path = myfile.txt
     * Target = APP_FOLDER/storage/myfile.txt
     * 
     * Root = APP_FOLDER/storage/
     * path = myfolder/myfile.txt
     * Target = APP_FOLDER/storage/myfolder/myfile.txt 
     * 
     * @param path is the object's path within the storage.  
     * @throws UniversalIOException when a specific IO error occurs.
     */
    void removeFile(String path) throws UniversalIOException {
        PathValidator.validatePath(path);
        
        int index = path.lastIndexOf("/");
        String fileName = path;
        if (index > -1) {
            fileName = path.substring(index + 1);
            path = path.substring(0, index);
        } else {
            path = "";
        }

        if (path != null && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        if (path != null && !path.trim().equals("") && !path.trim().startsWith("/")) {
            path = "/" + path;            
        }

        if (path.trim().equals("")) {
            path = "/" + this.settings.getRoot() + "/" + fileName;
        } else {
            path = "/" + this.settings.getRoot() + path + "/" + fileName;
        }

        try {
            this.dbxClient.files().delete(path);
        } catch (Exception e) {
            throw new UniversalIOException(e.getMessage());
        }      
    }

    /**
     * This method creates a new folder within the storage using the passed path. If the new folder name already
     * exists within the storage, this  process will skip the creation step.
     * 
     * Root = APP_FOLDER/storage/
     * path = /myFolder
     * Target = APP_FOLDER/storage/myFolder
     * 
     * Root = APP_FOLDER/storage/
     * path = /folders/myFolder
     * Target = APP_FOLDER/storage/folders/myFolder
     * 
     * @param path is the folder's path.
     * @param storeFiles is a flag to store the files after folder creation.
     * 
     * @throws UniversalIOException when a specific IO error occurs.
     * @throws IllegalArgumentException is path has an invalid value.
     */
    void createFolder(String path) throws UniversalIOException {
        PathValidator.validatePath(path);

        if ("".equals(path.trim())) {
            throw new UniversalIOException("Invalid path.  The path shouldn't be empty.");
        }

        if (path != null && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        if (path != null && !path.trim().equals("") && !path.trim().startsWith("/")) {
            path = "/" + path;            
        }

        if (!path.trim().equals("")) {
            path = "/" + this.settings.getRoot() + path;
        }

        try {
            this.dbxClient.files().createFolder(path);
        } catch (Exception e) {
            throw new UniversalIOException(e.getMessage());
        }   
    }

    /**
     * This method removes the folder located on that path.
     * The folder should be empty in order for removing.
     * 
     * Root = APP_FOLDER/storage/
     * path = myFolder
     * Target = APP_FOLDER/storage/myFolder
     * 
     * Root = APP_FOLDER/storage/
     * path = folders/myFolder
     * Target = APP_FOLDER/storage/folders/myFolder
     * 
     * @param path of the folder.
     */
    void removeFolder(String path) throws UniversalIOException {
        PathValidator.validatePath(path);

        if ("".equals(path.trim())) {
            return;
        }

        if (path != null && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        if (path != null && !path.trim().equals("") && !path.trim().startsWith("/")) {
            path = "/" + path;            
        }

        if (!path.trim().equals("")) {
            path = "/" + this.settings.getRoot() + path;
        }

        try {
            this.dbxClient.files().delete(path);
        } catch (Exception e) {
            throw new UniversalIOException(e.getMessage());
        }
    }

    /**
     * This method retrieves a file from the storage.
     * The method will retrieve the file according to the passed path.  
     * A file will be stored within the settings' tmp folder.
     * 
     * @param path in context.
     * @returns a file pointing to the retrieved file.
     */
    public File retrieveFile(String path) throws UniversalIOException {
        PathValidator.validatePath(path);

        if ("".equals(path.trim())) {
            return null;
        }

        if (path.trim().endsWith("/")) {
            throw new UniversalIOException("Invalid path.  Looks like you're trying to retrieve a folder.");
        }

        int index = path.lastIndexOf("/");
        String fileName = path;
        if (index > -1) {
            fileName = path.substring(index + 1);
        }

        /**
         * This method will store the file within tmp folder.
         */
        InputStream is = this.retrieveFileAsStream(path);
        try {
            is.close();
        } catch (Exception ignore) {}
        
        return new File(this.settings.getTmp(), fileName);
    }

    /**
     * This method retrieves a file from the storage as InputStream.
     * The method will retrieve the file according to the passed path.  
     * A file will be stored within the settings' tmp folder.
     * 
     * @param path in context.
     * @returns an IntputStream pointing to the retrieved file.
     */
    public InputStream retrieveFileAsStream(String path) throws UniversalIOException {
        PathValidator.validatePath(path);

        if (path == null || "".equals(path.trim())) {
            return null;
        }

        path = path.trim().replace("\\", "/");

        if (path.trim().endsWith("/")) {
            throw new UniversalIOException("Invalid path.  Looks like you're trying to retrieve a folder.");
        }

        int index = path.lastIndexOf("/");
        String fileName = path;
        if (index > -1) {
            fileName = path.substring(index + 1);
            path = path.substring(0, index);
        } else {
            path = "";
        }

        if (path != null && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        if (path != null && !path.trim().equals("") && !path.trim().startsWith("/")) {
            path = "/" + path;            
        }

        if (path.trim().equals("")) {
            return null;
        } else {
            path = "/" + this.settings.getRoot() + path;
        }

        OutputStream downloadFile = null;
        try {
            downloadFile = new FileOutputStream(this.settings.getTmp() + fileName);
            dbxClient.files().downloadBuilder(path + "/" + fileName).download(downloadFile);
            
            return new FileInputStream(this.settings.getTmp() + fileName);
        } catch (Exception e) {
            throw new UniversalIOException(e.getMessage());
        } finally {
            if (downloadFile != null) {
                try {
                    downloadFile.close();
                } catch (Exception ignore){}
            }
        }
    }

    /**
     * This method cleans the context of this storage.  This method doesn't remove any file from the storage.
     * The method will clean the tmp folder to release disk usage.
     */
    public void clean() throws UniversalIOException  {
        try {
            FileUtils.cleanDirectory(new File(this.settings.getTmp()));
        } catch (Exception e) {
            throw new UniversalIOException(e.getMessage());
        }
    }
}