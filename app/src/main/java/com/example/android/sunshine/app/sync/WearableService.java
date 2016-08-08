package com.example.android.sunshine.app.sync;

import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import com.bumptech.glide.Glide;
import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

//Class that extends WearableListenerService to receive the update message requested by the wear device.
//When it receives the update message the sendData() function will be called. This function will retrieve the
//the latest high and low as well as the image and send it via DataItem.

public class WearableService extends WearableListenerService {

    private static final String MESSAGE = "/update";
    private static final String WEATHER_HI_LOW_KEY = "weather_HI_LOW";
    private static final String WEATHER_PATH = "/weather_HI_LOW";
    private static final String WEATHER_IMAGE_KEY = "image";
    private static final String WEATHER_IMAGE_PATH = "/image";
    private static final long CONNECTION_TIME_OUT_MS = 30;
    private static final String[] NOTIFY_WEATHER_PROJECTION = new String[] {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_SHORT_DESC
    };
    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        String LOG_TAG = "WearableService";
        if (messageEvent.getPath().equals(MESSAGE)) {
            sendData(getBaseContext());
            Log.v(LOG_TAG, messageEvent.getPath());
        }
    }

    private static Asset createAssetFromBitmap(Bitmap bitmap) {
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
        return Asset.createFromBytes(byteStream.toByteArray());
    }

    public static void sendData(Context context) {
        final GoogleApiClient client = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .build();
        String locationQuery = Utility.getPreferredLocation(context);
        Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());
        // we'll query our contentProvider, as always
        Cursor cursor = context.getContentResolver().query(weatherUri, NOTIFY_WEATHER_PROJECTION, null, null, null);

        if (cursor.moveToFirst()) {
            int weatherId = cursor.getInt(INDEX_WEATHER_ID);
            final String high = Utility.formatTemperature(context, cursor.getDouble(INDEX_MAX_TEMP));
            final String low = Utility.formatTemperature(context, cursor.getDouble(INDEX_MIN_TEMP));

            Resources resources = context.getResources();
            int artResourceId = Utility.getArtResourceForWeatherCondition(weatherId);
            String artUrl = Utility.getArtUrlForWeatherCondition(context, weatherId);
            Bitmap largeIcon;
            try {
                largeIcon = Glide.with(context)
                        .load(artUrl)
                        .asBitmap()
                        .error(artResourceId)
                        .fitCenter()
                        .into(96, 96).get();
            } catch (InterruptedException | ExecutionException e) {
                Log.e("WearableService", "Error retrieving large icon from " + artUrl, e);
                largeIcon = BitmapFactory.decodeResource(resources, artResourceId);
            }
            final Asset asset = createAssetFromBitmap(largeIcon);
            final String weather = high + " " + low;

            new Thread(new Runnable() {
                @Override
                public void run() {
                    PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_PATH);
                    putDataMapRequest.getDataMap().putString(WEATHER_HI_LOW_KEY, weather);
                    PutDataRequest request = putDataMapRequest.asPutDataRequest();

                    PutDataMapRequest  requestImage = PutDataMapRequest.create(WEATHER_IMAGE_PATH);
                    requestImage.getDataMap().putAsset(WEATHER_IMAGE_KEY, asset);
                    PutDataRequest requestImages = requestImage.asPutDataRequest();

                    Log.v("WearableService", "Generating DataItem: " + request);
                    if (!client.isConnected() || !client.isConnecting()) {
                        ConnectionResult connectionResult = client
                                .blockingConnect(CONNECTION_TIME_OUT_MS, TimeUnit.SECONDS);
                        if (!connectionResult.isSuccess()) {
                            Log.e("WearableService", "DataLayerListenerService failed to connect to GoogleApiClient, "
                                    + "error code: " + connectionResult.getErrorCode());
                            return;
                        }
                    }
                    Wearable.DataApi.putDataItem(client, request)
                            .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                                @Override
                                public void onResult(DataApi.DataItemResult dataItemResult) {
                                    if (!dataItemResult.getStatus().isSuccess()) {
                                        Log.e("WearableService", "ERROR: failed to putDataItem, status code: "
                                                + dataItemResult.getStatus().getStatusCode());
                                    }
                                }
                            });

                    Wearable.DataApi.putDataItem(client, requestImages)
                            .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                                @Override
                                public void onResult(DataApi.DataItemResult dataItemResult) {
                                    if (!dataItemResult.getStatus().isSuccess()) {
                                        Log.e("WearableService", "ERROR: failed to putDataItem, status code: "
                                                + dataItemResult.getStatus().getStatusCode());
                                    }
                                }
                            });
                }
            }).start();
        }
        cursor.close();
    }

}

