/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create("sans-serif-condensed-light", Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     * Handler message to update the weather information.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private static final int MSG_UPDATE_WEATHER= 1;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
                //Handler to send a message to update the weather. This occurs every 30 minutes.
                switch (msg.what) {
                    case MSG_UPDATE_WEATHER:
                        engine.handleUpdateWeatherMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        public static final int WEATHER_UPDATE_TIME = 30; //Updates weather every 30 minutes
        public static final String WEATHER_HI_LOW_PATH = "/weather_HI_LOW";
        public static final String WEATHER_HI_LOW_KEY = "weather_HI_LOW";
        private static final String WEATHER_IMAGE_KEY = "image";
        private static final String WEATHER_IMAGE_PATH = "/image";
        private static final String TAG = "CanvasWatchFaceService";
        GoogleApiClient mGoogleApiClient;

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        final Handler mUpdateWeatherHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mWeatherHiTextPaint;
        Paint mWeatherLoTextPaint;
        Paint mLinePaint;
        Bitmap mWeatherIcon;
        String hi;
        String low;
        boolean mAmbient;
        Time mTime;
        Time mLastRefreshTime;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        float mXOffset;
        float mYOffset;

        private static final String MESSAGE = "/update";
        private String nodeId;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setHotwordIndicatorGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));
            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mWeatherHiTextPaint = new Paint();
            mWeatherHiTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mWeatherLoTextPaint = new Paint();
            mWeatherLoTextPaint = createTextPaint(resources.getColor(R.color.weather_low_color));
            mLinePaint = new Paint();
            mLinePaint = createTextPaint(resources.getColor(R.color.line_color));
            mTime = new Time();
            mTime.setToNow();
            mLastRefreshTime = new Time();
            //start the timer to update the weather data every 30 minutes.
            mUpdateWeatherHandler.sendEmptyMessage(MSG_UPDATE_WEATHER);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            mUpdateWeatherHandler.removeMessages(MSG_UPDATE_WEATHER);
            releaseGoogleApiClient();
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();
              //  sendUpdateMessage();
                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
                releaseGoogleApiClient();
            }
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        //Disconnects the googleapiclient of connected.
        private void releaseGoogleApiClient() {
            if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                Wearable.DataApi.removeListener(mGoogleApiClient, onDataChangedListener);
                mGoogleApiClient.disconnect();
            }
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float weatherLowTextSize = resources.getDimension(isRound
                    ? R.dimen.weather__low_text_size_round : R.dimen.weather_low_text_size);
            float weatherHiTextSize = resources.getDimension(isRound
                    ? R.dimen.weather__hi_text_size_round : R.dimen.weather_hi_text_size);
            mWeatherLoTextPaint.setTextSize(weatherLowTextSize);
            mWeatherHiTextPaint.setTextSize(weatherHiTextSize);
            mTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mWeatherHiTextPaint.setAntiAlias(!inAmbientMode);
                    mWeatherLoTextPaint.setAntiAlias(!inAmbientMode);
                    mLinePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();

            //Update weather data when off ambient mode
            releaseGoogleApiClient();
            mGoogleApiClient.connect();
            sendUpdateMessage();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }
            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String text = mAmbient
                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);
            canvas.drawText(text, bounds.centerX()-(mTextPaint.measureText(text))/2, bounds.centerY()-60, mTextPaint);
            canvas.drawLine(bounds.centerX()-(mTextPaint.getTextSize()), bounds.centerY()-50, bounds.centerX()+(mTextPaint.getTextSize()),bounds.centerY()-50, mLinePaint);

            if(mWeatherIcon!=null) {
                if(!isInAmbientMode()) {
                    canvas.drawBitmap(mWeatherIcon, canvas.getWidth() / 2 - mWeatherIcon.getWidth() / 2, canvas.getHeight() / 2 - mWeatherIcon.getWidth() / 2, mTextPaint);
                    canvas.drawLine(bounds.centerX(), bounds.centerY() + (mWeatherIcon.getHeight() / 2), bounds.centerX(), bounds.bottom - 10, mLinePaint);
                }
                else {
                    //Convert image to grayscale if in ambient mode.
                    canvas.drawBitmap(toGrayscale(mWeatherIcon), canvas.getWidth() / 2 - mWeatherIcon.getWidth() / 2, canvas.getHeight() / 2 - mWeatherIcon.getWidth() / 2, mTextPaint);
                    canvas.drawLine(bounds.centerX(), bounds.centerY() + (mWeatherIcon.getHeight() / 2), bounds.centerX(), bounds.bottom - 10, mLinePaint);
                }
            }
            if(low!=null & hi!=null) {
                canvas.drawText(hi, ((bounds.centerX()-bounds.left)/2), bounds.centerY() + bounds.height()/3, mWeatherHiTextPaint);
                canvas.drawText(low, (bounds.centerX()+(bounds.right-bounds.centerX())/4), bounds.centerY() + bounds.height()/3, mWeatherLoTextPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        //Updates the weather data every 30 minutes.
        private void handleUpdateWeatherMessage() {
            long delayMs = TimeUnit.MINUTES.toMillis(WEATHER_UPDATE_TIME);
            releaseGoogleApiClient();
            mGoogleApiClient.connect();
            sendUpdateMessage();
            mUpdateWeatherHandler.sendEmptyMessageDelayed(MSG_UPDATE_WEATHER, delayMs);
        }

        //function the converts a color image to grayscale.
        //Find more info here:
        //http://stackoverflow.com/questions/3373860/convert-a-bitmap-to-grayscale-in-android

        public Bitmap toGrayscale(Bitmap bmpOriginal)
        {
            int width, height;
            height = bmpOriginal.getHeight();
            width = bmpOriginal.getWidth();

            Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmpGrayscale);
            Paint paint = new Paint();
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0);
            ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
            paint.setColorFilter(f);
            c.drawBitmap(bmpOriginal, 0, 0, paint);
            return bmpGrayscale;
        }

        //Sends a message to the mobile device to request an update.
        private void sendUpdateMessage() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    NodeApi.GetConnectedNodesResult result =
                            Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();
                    List<Node> nodes = result.getNodes();
                    if (nodes.size() > 0) {
                        nodeId = nodes.get(0).getId();
                        Wearable.MessageApi.sendMessage(mGoogleApiClient, nodeId, MESSAGE, null);
                    }
                }
            }).start();
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, onDataChangedListener);
            Wearable.DataApi.getDataItems(mGoogleApiClient).setResultCallback(onConnectedResultCallback);
        }

        @Override
        public void onConnectionFailed(ConnectionResult result) {
            Log.e(TAG, "onConnectionFailed(): Failed to connect, with result: " + result);
        }

        @Override
        public void onConnectionSuspended(int cause) {
            Log.v(TAG, "onConnectionSuspended(): Connection to Google API client was suspended");
        }

        //processes the data returned
        private final ResultCallback<DataItemBuffer> onConnectedResultCallback = new ResultCallback<DataItemBuffer>() {
            @Override
            public void onResult(DataItemBuffer dataItems) {
                for (DataItem item : dataItems) {
                    if (item.getUri().getPath().compareTo(WEATHER_HI_LOW_PATH)==0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        String weatherInfo = dataMap.getString(WEATHER_HI_LOW_KEY);
                        Log.v(TAG, weatherInfo);
                        String splitWeather[] = weatherInfo.split("\\s+");
                        hi = splitWeather[0];
                        low = splitWeather[1];
                        invalidate();
                    }
                    if (item.getUri().getPath().compareTo(WEATHER_IMAGE_PATH)==0) {
                        DataMapItem dataMapItem = DataMapItem.fromDataItem(item);
                        Asset profileAsset = dataMapItem.getDataMap().getAsset(WEATHER_IMAGE_KEY);
                        new getBitmapTask().execute(profileAsset);
                    }
                }
                dataItems.release();
            }
        };

        private class getBitmapTask extends AsyncTask<Asset, Integer, Bitmap> {
            protected Bitmap doInBackground(Asset... assets) {
                Asset asset = assets[0];

                if (asset == null) {
                    throw new IllegalArgumentException("Asset must be non-null");
                }
                ConnectionResult result =
                        mGoogleApiClient.blockingConnect(30, TimeUnit.MILLISECONDS);
                if (!result.isSuccess()) {
                    return null;
                }
                // convert asset into a file descriptor and block until it's ready
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                        mGoogleApiClient, asset).await().getInputStream();
                mGoogleApiClient.disconnect();

                if (assetInputStream == null) {
                    Log.w(TAG, "Requested an unknown Asset.");
                    return null;
                }
                // decode the stream into a bitmap
                return BitmapFactory.decodeStream(assetInputStream);
            }

            protected void onPostExecute(Bitmap bitmap) {
                mWeatherIcon = bitmap;
                invalidate();
            }
        }

        private final DataApi.DataListener onDataChangedListener = new DataApi.DataListener() {
            @Override
            public void onDataChanged(DataEventBuffer dataEvents) {
                for (DataEvent event : dataEvents) {
                    if (event.getType() == DataEvent.TYPE_CHANGED) {
                        // DataItem changed
                        DataItem item = event.getDataItem();
                        if (item.getUri().getPath().compareTo(WEATHER_HI_LOW_PATH)==0) {
                            DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                            String weatherInfo = dataMap.getString(WEATHER_HI_LOW_KEY);
                            Log.v(TAG, weatherInfo);
                            String splitWeather[] = weatherInfo.split("\\s+");
                            hi = splitWeather[0];
                            low = splitWeather[1];
                            invalidate();
                        }
                        if (item.getUri().getPath().compareTo(WEATHER_IMAGE_PATH)==0) {
                            DataMapItem dataMapItem = DataMapItem.fromDataItem(item);
                            Asset profileAsset = dataMapItem.getDataMap().getAsset(WEATHER_IMAGE_KEY);
                            new getBitmapTask().execute(profileAsset);
                        }
                    }
                }
                dataEvents.release();
            }
        };
    }

}
