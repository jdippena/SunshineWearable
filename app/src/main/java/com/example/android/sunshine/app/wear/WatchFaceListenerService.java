package com.example.android.sunshine.app.wear;

import android.os.Bundle;
import android.support.annotation.Nullable;

import com.example.android.sunshine.app.sync.SunshineSyncAdapter;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

public class WatchFaceListenerService extends WearableListenerService implements GoogleApiClient.ConnectionCallbacks {
    GoogleApiClient mGoogleApiClient;

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .build();
        mGoogleApiClient.connect();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        SunshineSyncAdapter.syncWearable(this, mGoogleApiClient);
    }

    @Override
    public void onConnectionSuspended(int i) {

    }
}
