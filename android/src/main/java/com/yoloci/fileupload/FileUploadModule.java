package com.yoloci.fileupload;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.GuardedAsyncTask;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableMap;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.util.UUID;

public class FileUploadModule extends ReactContextBaseJavaModule {

    @Override
    public String getName() {
        return "FileUpload";
    }

    public FileUploadModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @ReactMethod
    public void upload(final ReadableMap options, final Callback callback) {
        new UploadImage(getReactApplicationContext(), options, callback)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static class UploadImage extends GuardedAsyncTask<Void, Void> {
        private final Context mContext;
        private final ReadableMap mOptions;
        private final Callback mCallBack;

        protected UploadImage(ReactContext reactContext, ReadableMap options, final Callback callback) {
            super(reactContext);
            mContext = reactContext;
            mOptions = options;
            mCallBack = callback;
        }

        @Override
        protected void doInBackgroundGuarded(Void... params) {

            String lineEnd = "\r\n";
            String twoHyphens = "--";
            String boundary = "*****" + UUID.randomUUID().toString() + "*****";

            String uploadUrl = mOptions.getString("uploadUrl");
            String method;
            if (mOptions.hasKey("method")) {
                method = mOptions.getString("method");
            } else {
                method = "POST";
            }

            ReadableMap headers = mOptions.getMap("headers");
            ReadableArray files = mOptions.getArray("files");
            ReadableMap fields = mOptions.getMap("fields");

            HttpURLConnection connection = null;
            DataOutputStream outputStream = null;
            URL connectURL = null;
            FileInputStream fileInputStream = null;

            int bytesRead, bytesAvailable, bufferSize;
            byte[] buffer;
            int maxBufferSize = 1*1024*1024;

            try {

                connectURL = new URL(uploadUrl);


                connection = (HttpURLConnection) connectURL.openConnection();

                // Allow Inputs &amp; Outputs.
                connection.setDoInput(true);
                connection.setDoOutput(true);
                connection.setUseCaches(false);

                connection.setRequestMethod(method);

                // set headers
                ReadableMapKeySetIterator iterator = headers.keySetIterator();
                while (iterator.hasNextKey()) {
                    String key = iterator.nextKey();
                    connection.setRequestProperty(key, headers.getString(key));
                }

                connection.setRequestProperty("Connection", "Keep-Alive");
                connection.setRequestProperty("Content-Type", "multipart/form-data;boundary="+boundary);

                outputStream = new DataOutputStream( connection.getOutputStream() );

                // set fields
                ReadableMapKeySetIterator fieldIterator = fields.keySetIterator();
                while (fieldIterator.hasNextKey()) {
                    outputStream.writeBytes(twoHyphens + boundary + lineEnd);

                    String key = fieldIterator.nextKey();
                    outputStream.writeBytes("Content-Disposition: form-data; name=\"" + key +  "\"" + lineEnd + lineEnd);
                    outputStream.writeBytes(fields.getString(key));
                    outputStream.writeBytes(lineEnd);
                }

                for (int i = 0; i < files.size(); i++) {

                    ReadableMap file = files.getMap(i);

                    String filename = "filename";
                    if (file.hasKey("filename")) {
                        filename = file.getString("filename");
                    }

                    String name = "name";
                    if (file.hasKey("name")) {
                        name = file.getString("name");
                    }

                    String filepath = file.getString("filepath");
                    Uri uri = Uri.parse(filepath);
                    if (filepath.startsWith("content")) {
                        filepath = getFilePathFromUri(uri);
                    }
                    else if (filepath.startsWith("file")) {
                        filepath = uri.getPath();
                    }
                    File f = new File(filepath);

                    outputStream.writeBytes(twoHyphens + boundary + lineEnd);
                    outputStream.writeBytes("Content-Disposition: form-data; name=\""+name+"\";filename=\"" + filename + "\"" + lineEnd);
                    outputStream.writeBytes(lineEnd);

                    if (file.hasKey("size") || file.hasKey("compress")) {
                        double width = 0;
                        double height = 0;
                        if (file.hasKey("size")) {
                            ReadableMap size =  file.getMap("size");
                            width = size.getDouble("width");
                            height = size.getDouble("height");
                        }

                        Bitmap bitmap = scaleImage(filepath, width, height);
                        try {
                            if (file.hasKey("png") && file.getBoolean("png")) {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                            }
                            else {
                                if(file.hasKey("compress")) {
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, (int)(file.getDouble("compress")*100), outputStream);
                                }
                                else {
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
                                }
                            }
                        } catch (Exception e) {
                            mCallBack.invoke("Error happened: " + e.toString(), null);
                        }
                        bitmap.recycle();
                    }
                    else {
                        fileInputStream = new FileInputStream(f);
                        bytesAvailable = fileInputStream.available();
                        bufferSize = Math.min(bytesAvailable, maxBufferSize);
                        buffer = new byte[bufferSize];

                        // Read file
                        bytesRead = fileInputStream.read(buffer, 0, bufferSize);
                        while (bytesRead > 0) {
                            outputStream.write(buffer, 0, bufferSize);
                            bytesAvailable = fileInputStream.available();
                            bufferSize = Math.min(bytesAvailable, maxBufferSize);
                            bytesRead = fileInputStream.read(buffer, 0, bufferSize);
                        }
                    }

                    outputStream.writeBytes(lineEnd);
                }

                outputStream.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);

                // Responses from the server (code and message)
                int serverResponseCode = connection.getResponseCode();
                String serverResponseMessage = connection.getResponseMessage();
                if (serverResponseCode != 200) {
                    if (fileInputStream != null) {
                        fileInputStream.close();
                    }
                    outputStream.flush();
                    outputStream.close();

                    mCallBack.invoke("Error happened: " + serverResponseMessage, null);
                } else {
                    InputStream inputStream = connection.getInputStream();
                    BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    br.close();
                    String data = sb.toString();

                    if (fileInputStream != null) {
                        fileInputStream.close();
                    }
                    inputStream.close();
                    outputStream.flush();
                    outputStream.close();

                    WritableMap result = Arguments.createMap();
                    result.putString("data", data);
                    result.putInt("status", serverResponseCode);
                    mCallBack.invoke(null, result);
                }

            } catch(Exception ex) {
                Log.e("file upload error", ex.toString());
                mCallBack.invoke("Error happened: " + ex.toString(), null);
            }
        }

        private String getFilePathFromUri(Uri uri) {

            String ret = "";
            String[] projection = { MediaStore.Images.Media.DATA};
            Cursor cursor = mContext.getContentResolver().query(uri, projection, null, null, null);
            if(cursor!=null){
                if (cursor.moveToFirst()) {
                    int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                    ret = cursor.getString(column_index);
                }
                cursor.close();
            }
            return ret;
        }

        private Bitmap scaleImage(String filepath, double width, double height) {

            BitmapFactory.Options op = new BitmapFactory.Options();
            op.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(filepath, op);
            int w = op.outWidth;
            int h = op.outHeight;

            op.inJustDecodeBounds = false;
            if (width > 0 && height > 0 && w>0 && h>0) {
                double scale = Math.max(width/w, height/h);
                op.outWidth = (int)(width * scale);
                op.outHeight = (int)(height * scale);
            }
            else {
                op.outWidth = w;
                op.outHeight = h;
            }

            Bitmap bitmap = BitmapFactory.decodeFile(filepath, op);
            return bitmap;
        }
    }

}
