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
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService  {
    private static final String LOG_TAG = SunshineWatchFace.class.getName();
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine
            implements GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks, DataApi.DataListener {
        boolean mIsRound;
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimeTextPaint;
        Paint mCirclePaint;
        Paint mWeatherPanePaint;
        Paint mWeatherTextPaint;
        Paint mBitmapPaint;
        ColorMatrixColorFilter mBlackAndWhiteFilter;
        boolean mAmbient;
        Time mTime;

        String mHigh;
        String mLow;
        Bitmap mWeatherArt;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        float mTimeYOffset;
        float mCircleRadiusFactor;
        float mWeatherPaneYOffsetFactor;
        float mWeatherTextAdjustmentFactor;
        int mChinSize;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        GoogleApiClient mGoogleApiClient;
        public static final String WEATHER_ID_KEY = "weatherid";
        public static final String HIGH_TEMP_KEY = "hightemp";
        public static final String LOW_TEMP_KEY = "lowtemp";

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setViewProtectionMode(WatchFaceStyle.PROTECT_STATUS_BAR)
                    .setHotwordIndicatorGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL)
                    .setStatusBarGravity(Gravity.TOP | Gravity.END)
                    .build());
            Resources res = getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(res.getColor(R.color.background));

            mTimeTextPaint = createTimeTextPaint();

            mCirclePaint = new Paint();
            mCirclePaint.setColor(res.getColor(R.color.accent));
            mCirclePaint.setAntiAlias(true);

            mWeatherPanePaint = new Paint();
            mWeatherPanePaint.setColor(res.getColor(R.color.detail_accent_pane_background));
            mWeatherPanePaint.setAntiAlias(true);

            mWeatherTextPaint = createWeatherTextPaint();

            mBitmapPaint = new Paint();

            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            mBlackAndWhiteFilter = new ColorMatrixColorFilter(colorMatrix);

            mTime = new Time();

            // temp values
            updateResources(800);
            mHigh = "100°";
            mLow = "0°";

            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            mGoogleApiClient.disconnect();
            super.onDestroy();
        }


        // GoogleApiClient and data calls
        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            sendUpdateMessage();
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event: dataEventBuffer) {
                DataItem item = event.getDataItem();
                DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                String high = dataMap.getString(HIGH_TEMP_KEY);
                String low = dataMap.getString(LOW_TEMP_KEY);
                int weatherId = dataMap.getInt(WEATHER_ID_KEY);

                updateResources(weatherId);
                mHigh = high;
                mLow = low;
            }
        }

        private void sendUpdateMessage() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    NodeApi.GetConnectedNodesResult nodes = Wearable.NodeApi
                            .getConnectedNodes(mGoogleApiClient)
                            .await();
                    byte[] message = "update".getBytes();
                    for (Node node : nodes.getNodes()) {
                        Wearable.MessageApi.sendMessage(mGoogleApiClient, node.getId(), "/sync", message);
                    }
                }
            }).start();
        }




        private Paint createTimeTextPaint() {
            Paint paint = new Paint();
            paint.setColor(getResources().getColor(R.color.secondary_text));
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            paint.setTextAlign(Paint.Align.CENTER);
            return paint;
        }

        private Paint createWeatherTextPaint() {
            Paint paint = new Paint();
            paint.setColor(getResources().getColor(android.R.color.white));
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            paint.setTextAlign(Paint.Align.CENTER);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources res = SunshineWatchFace.this.getResources();
            mIsRound = insets.isRound();
            mChinSize = insets.getSystemWindowInsetBottom();
            float timeTextSize = res.getDimension(mIsRound ?
                    R.dimen.time_text_size_round : R.dimen.time_text_size);
            mTimeTextPaint.setTextSize(timeTextSize);

            mTimeYOffset = res.getDimension(mIsRound ?
                    R.dimen.time_y_offset_round : R.dimen.time_y_offset);
            mCircleRadiusFactor = mIsRound ? 0.4f : 0.45f;
            mWeatherPaneYOffsetFactor = mIsRound ? 0.75f : 0.8f;
            mWeatherTextAdjustmentFactor = mIsRound ? 0.75f : 1;
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
                    mTimeTextPaint.setAntiAlias(!inAmbientMode);
                }
                if (mAmbient) {
                    mBackgroundPaint.setColorFilter(mBlackAndWhiteFilter);
                    mTimeTextPaint.setColorFilter(mBlackAndWhiteFilter);
                    mCirclePaint.setColorFilter(mBlackAndWhiteFilter);
                    mWeatherPanePaint.setColorFilter(mBlackAndWhiteFilter);
                    mWeatherTextPaint.setColorFilter(mBlackAndWhiteFilter);
                    mBitmapPaint.setColorFilter(mBlackAndWhiteFilter);
                } else {
                    mBackgroundPaint.setColorFilter(null);
                    mTimeTextPaint.setColorFilter(null);
                    mCirclePaint.setColorFilter(null);
                    mWeatherPanePaint.setColorFilter(null);
                    mWeatherTextPaint.setColorFilter(null);
                    mBitmapPaint.setColorFilter(null);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
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
            String timeText = String.format(Locale.getDefault(), "%d:%02d", mTime.hour, mTime.minute);
            canvas.drawText(timeText, bounds.exactCenterX(), mTimeYOffset, mTimeTextPaint);

            float radius = mCircleRadiusFactor * bounds.height();
            float weatherPaneYOffset = mWeatherPaneYOffsetFactor * bounds.height();
            canvas.drawCircle(bounds.exactCenterX(), weatherPaneYOffset, radius, mCirclePaint);
            canvas.drawRect(bounds.left, weatherPaneYOffset, bounds.right, bounds.bottom, mWeatherPanePaint);

            mWeatherTextPaint.setTextSize(Math.min((bounds.height() - weatherPaneYOffset - mChinSize)/2, 18*getResources().getDisplayMetrics().scaledDensity));
            float weatherTextHeightOffset = weatherPaneYOffset +
                    (bounds.height() - weatherPaneYOffset - mChinSize + mWeatherTextAdjustmentFactor * mWeatherTextPaint.getTextSize())/2;
            String weatherString = mLow + "   " + mHigh;
            canvas.drawText(weatherString, bounds.exactCenterX(), weatherTextHeightOffset, mWeatherTextPaint);

            final int s = Math.round(2*radius/(float) Math.sqrt(5));
            final int c = Math.round(bounds.centerX());
            final int b = Math.round(weatherPaneYOffset);
            Rect weatherArtBounds = new Rect(c-s/2, b-s, c+s/2, b);
            canvas.drawBitmap(mWeatherArt, null, weatherArtBounds, mBitmapPaint);
        }

        private void updateResources(int weatherId) {
            mCirclePaint.setColor(getResources().getColor(
                    Util.getCircleColorFromWeatherId(weatherId))
            );
            int weatherArtId = Util.getArtResourceForWeatherCondition(weatherId);
            mWeatherArt = BitmapFactory.decodeResource(getResources(), weatherArtId);
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
    }
}
