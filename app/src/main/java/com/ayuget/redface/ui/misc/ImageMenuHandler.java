/*
 * Copyright 2015 Ayuget
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ayuget.redface.ui.misc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.app.ShareCompat;
import android.view.View;

import com.ayuget.redface.R;
import com.ayuget.redface.storage.StorageHelper;
import com.ayuget.redface.ui.UIConstants;
import com.ayuget.redface.ui.activity.BaseActivity;
import com.ayuget.redface.ui.activity.ExifDetailsActivity;
import com.squareup.okhttp.Callback;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.tbruyelle.rxpermissions.RxPermissions;

import java.io.File;
import java.io.IOException;

import rx.functions.Action1;
import timber.log.Timber;

public class ImageMenuHandler {
    public static final String PNG_FILE_EXTENSION = ".png";
    private final Activity activity;

    private final String imageUrl;

    public interface ImageSavedCallback {
        void onImageSaved(File savedImage, Bitmap.CompressFormat format);
    }

    public ImageMenuHandler(Activity activity, String imageUrl) {
        this.activity = activity;
        this.imageUrl = imageUrl;
    }

    public void saveImage(boolean compressAsPng) {
        saveImage(compressAsPng, true, true, true, null);
    }

    public void saveImage(final boolean compressAsPng, final boolean notifyUser, final boolean broadcastSave, final boolean overrideExisting, final ImageSavedCallback imageSavedCallback) {
        RxPermissions.getInstance(activity)
                .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .subscribe(new Action1<Boolean>() {
                    @Override
                    public void call(Boolean granted) {
                        if (granted) {
                            String imageOriginalName = StorageHelper.getFilenameFromUrl(imageUrl);
                            final Bitmap.CompressFormat targetFormat = compressAsPng ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;

                            // When compressing image as PNG, we need to make sure the file extension is ".png"
                            final String imageName = compressAsPng ?  replaceExtensionWithPng(imageOriginalName) : imageOriginalName;

                            // Images can be already stored locally so they are only downloaded from the network
                            // if necessary.
                            try {
                                final File mediaFile = StorageHelper.getMediaFile(imageName);

                                if (mediaFile.exists() && ! overrideExisting) {
                                    Timber.d("Image '%s' already exists, it will not be redownloaded for efficiency reasons", mediaFile.getAbsolutePath());
                                    notifyImageWasSaved(imageSavedCallback, mediaFile, targetFormat);
                                }

                                else {
                                    saveImageFromNetwork(mediaFile, targetFormat, compressAsPng, notifyUser, broadcastSave, imageSavedCallback);
                                }
                            }
                            catch (IOException e) {
                                Timber.e(e, "Unable to save image to external storage");
                                SnackbarHelper.makeError(activity, R.string.error_saving_image).show();
                            }
                        }
                        else {
                            Timber.w("WRITE_EXTERNAL_STORAGE denied, unable to save image");
                            SnackbarHelper.makeError(activity, R.string.error_saving_image_permission_denied).show();
                        }
                    }
                });
    }

    /**
     * Saves image from network using OkHttp. Picasso is not used because it would strip away the
     * EXIF data once the image is saved (Picasso directly gives us a Bitmap).
     */
    private void saveImageFromNetwork(final File mediaFile, final Bitmap.CompressFormat targetFormat, final boolean compressAsPng, final boolean notifyUser, final boolean broadcastSave, final ImageSavedCallback imageSavedCallback) {
        OkHttpClient okHttpClient = new OkHttpClient();

        final Request request = new Request.Builder().url(imageUrl).build();
        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Request request, IOException e) {
                SnackbarHelper.makeError(activity, R.string.error_saving_image).show();
            }

            @Override
            public void onResponse(Response response) throws IOException {
                final byte[] imageBytes = response.body().bytes();

                Timber.d("Image successfully decoded, requesting WRITE_EXTERNAL_STORAGE permission to save image");

                RxPermissions.getInstance(activity)
                        .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        .subscribe(new Action1<Boolean>() {
                            @Override
                            public void call(Boolean granted) {
                                if (granted) {
                                    Timber.d("WRITE_EXTERNAL_STORAGE granted, saving image to disk");

                                    try {
                                        Timber.d("Saving image to %s", mediaFile.getAbsolutePath());

                                        if (compressAsPng) {
                                            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                                            StorageHelper.storeImageToFile(bitmap, mediaFile, targetFormat);
                                        }
                                        else {
                                            StorageHelper.storeImageToFile(imageBytes, mediaFile);
                                        }

                                        if (broadcastSave) {
                                            // First, notify the system that a new image has been saved
                                            // to external storage. This is important for user experience
                                            // because it makes the image visible in the system gallery
                                            // app.
                                            StorageHelper.broadcastImageWasSaved(activity, mediaFile, targetFormat);
                                        }

                                        if (notifyUser) {
                                            // Then, notify the user with an enhanced snackbar, allowing
                                            // him (or her) to open the image in his favorite app.
                                            Snackbar snackbar = SnackbarHelper.makeWithAction(activity, R.string.image_saved_successfully, R.string.action_snackbar_open_image, new View.OnClickListener() {
                                                @Override
                                                public void onClick(View v) {
                                                    Intent intent = new Intent();
                                                    intent.setAction(Intent.ACTION_VIEW);
                                                    intent.setDataAndType(Uri.parse("file://" + mediaFile.getAbsolutePath()), "image/*");
                                                    activity.startActivity(intent);
                                                }
                                            });
                                            snackbar.show();
                                        }

                                        notifyImageWasSaved(imageSavedCallback, mediaFile, targetFormat);
                                    }
                                    catch (IOException e) {
                                        Timber.e(e, "Unable to save image to external storage");
                                        SnackbarHelper.makeError(activity, R.string.error_saving_image).show();
                                    }
                                }
                                else {
                                    Timber.w("WRITE_EXTERNAL_STORAGE denied, unable to save image");
                                    SnackbarHelper.makeError(activity, R.string.error_saving_image_permission_denied).show();
                                }
                            }
                        });
            }
        });
    }

    private void notifyImageWasSaved(final ImageSavedCallback imageSavedCallback, final File mediaFile, final Bitmap.CompressFormat targetFormat) {
        if (imageSavedCallback != null) {
            // We need to make sure to call the callback on the
            // main thread.
            Handler mainHandler = new Handler(activity.getMainLooper());
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    imageSavedCallback.onImageSaved(mediaFile, targetFormat);
                }
            });
        }
    }

    private String replaceExtensionWithPng(String imageName) {
        int dotIndex = imageName.lastIndexOf('.');

        if (dotIndex >= 0) {
            return imageName.substring(0, imageName.lastIndexOf('.')) + PNG_FILE_EXTENSION;
        }
        else {
            return imageName + PNG_FILE_EXTENSION;
        }
    }

    public void openImage() {
        Timber.d("Opening '%s' in browser (or custom tab if supported)", imageUrl);
        ((BaseActivity) activity).openLink(imageUrl);
    }

    public void openExifData() {
        Intent intent = new Intent(activity, ExifDetailsActivity.class);
        intent.putExtra(UIConstants.ARG_EXIF_IMAGE, imageUrl);
        activity.startActivity(intent, ActivityOptionsCompat.makeCustomAnimation(activity, R.anim.slide_up, R.anim.slide_down).toBundle());
    }

    public void shareImage() {
        saveImage(false, false, true, true, new ImageSavedCallback() {
            @Override
            public void onImageSaved(File savedImage, Bitmap.CompressFormat format) {
                Timber.d("Sharing image : '%s'", savedImage);
                ShareCompat.IntentBuilder.from(activity)
                        .setText(activity.getText(R.string.action_share_image))
                        .setType(StorageHelper.getImageMimeType(format))
                        .setSubject(savedImage.getName())
                        .setStream(Uri.fromFile(savedImage))
                        .startChooser();
            }
        });
    }
}
