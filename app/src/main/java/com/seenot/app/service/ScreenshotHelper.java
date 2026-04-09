package com.seenot.app.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import androidx.core.content.ContextCompat;

/**
 * Helper class for taking screenshots via AccessibilityService
 */
public class ScreenshotHelper {
    private static final String TAG = "ScreenshotHelper";

    public interface ScreenshotCallback {
        void onResult(Bitmap bitmap);
    }

    public static void takeScreenshot(AccessibilityService service, ScreenshotCallback callback) {
        Log.d(TAG, "takeScreenshot called");
        try {
            android.os.Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> {
                try {
                    Log.d(TAG, "Running on main thread");
                    java.util.concurrent.Executor executor = ContextCompat.getMainExecutor(service);
                    Log.d(TAG, "Executor created");

                    AccessibilityService.TakeScreenshotCallback screenshotCallback =
                        new AccessibilityService.TakeScreenshotCallback() {
                            @Override
                            public void onSuccess(AccessibilityService.ScreenshotResult screenshotResult) {
                                Log.d(TAG, "onSuccess called");
                                try {
                                    if (screenshotResult != null) {
                                        android.graphics.Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                            screenshotResult.getHardwareBuffer(),
                                            screenshotResult.getColorSpace()
                                        );
                                        if (hardwareBitmap != null) {
                                            Bitmap mutableBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, true);
                                            Log.d(TAG, "Screenshot success: " + mutableBitmap.getWidth() + "x" + mutableBitmap.getHeight());
                                            callback.onResult(mutableBitmap);
                                        } else {
                                            Log.w(TAG, "Failed to create bitmap from hardware buffer");
                                            callback.onResult(null);
                                        }
                                    } else {
                                        Log.w(TAG, "screenshotResult is null");
                                        callback.onResult(null);
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Error processing screenshot", e);
                                    callback.onResult(null);
                                }
                            }

                            @Override
                            public void onFailure(int errorCode) {
                                Log.e(TAG, "Screenshot failed with error code: " + errorCode);
                                callback.onResult(null);
                            }
                        };

                    Log.d(TAG, "Calling service.takeScreenshot...");
                    service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, screenshotCallback);
                    Log.d(TAG, "takeScreenshot called successfully");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to take screenshot", e);
                    callback.onResult(null);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to take screenshot", e);
            callback.onResult(null);
        }
    }
}
