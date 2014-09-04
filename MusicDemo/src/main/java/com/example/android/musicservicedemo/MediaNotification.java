/*
 * Copyright (C) 2014 Google Inc. All Rights Reserved.
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

package com.example.android.musicservicedemo;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.AsyncTask;
import android.util.SparseArray;

import com.example.android.musicservicedemo.utils.BitmapHelper;
import com.example.android.musicservicedemo.utils.LogHelper;

import java.io.IOException;

/**
 * Keeps track of a notification and updates it automatically for a given
 * MediaSession. Maintaining a visible notification (usually) guarantees that the music service
 * won't be killed during playback.
 */
public class MediaNotification extends BroadcastReceiver {
    private static final String TAG = "MediaNotification";

    private static final int NOTIFICATION_ID = 412;

    public static final String ACTION_PAUSE = "com.example.android.musicservicedemo.pause";
    public static final String ACTION_PLAY = "com.example.android.musicservicedemo.play";
    public static final String ACTION_PREV = "com.example.android.musicservicedemo.prev";
    public static final String ACTION_NEXT = "com.example.android.musicservicedemo.next";


    private final MusicService mService;
    private MediaSession.Token mSessionToken;
    private MediaController mController;
    private MediaController.TransportControls mTransportControls;
    private final SparseArray<PendingIntent> mIntents = new SparseArray<PendingIntent>();

    private PlaybackState mPlaybackState;
    private MediaMetadata mMetadata;

    private Notification.Builder mNotificationBuilder;
    private NotificationManager mNotificationManager;
    private Notification.Action mPlayPauseAction;

    private String mCurrentAlbumArt;

    private boolean mStarted = false;

    public MediaNotification(MusicService service) {
        mService = service;
        updateSessionToken();

        mNotificationManager = (NotificationManager) mService
                .getSystemService(Context.NOTIFICATION_SERVICE);

        String pkg = mService.getPackageName();
        mIntents.put(android.R.drawable.ic_media_pause, PendingIntent.getBroadcast(mService, 100,
                new Intent(ACTION_PAUSE).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT));
        mIntents.put(android.R.drawable.ic_media_play, PendingIntent.getBroadcast(mService, 100,
                new Intent(ACTION_PLAY).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT));
        mIntents.put(android.R.drawable.ic_media_previous, PendingIntent.getBroadcast(mService, 100,
                new Intent(ACTION_PREV).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT));
        mIntents.put(android.R.drawable.ic_media_next, PendingIntent.getBroadcast(mService, 100,
                new Intent(ACTION_NEXT).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT));
    }

    /**
     * Posts the notification and starts tracking the session to keep it
     * updated. The notification will automatically be removed if the session is
     * destroyed before {@link #stopNotification} is called.
     */
    public void startNotification() {
        if (!mStarted) {
            mController.registerCallback(mCb);
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_NEXT);
            filter.addAction(ACTION_PAUSE);
            filter.addAction(ACTION_PLAY);
            filter.addAction(ACTION_PREV);
            mService.registerReceiver(this, filter);

            mMetadata = mController.getMetadata();
            mPlaybackState = mController.getPlaybackState();

            mStarted = true;
            // The notification must be updated after setting started to true
            updateNotificationMetadata();
        }
    }

    /**
     * Removes the notification and stops tracking the session. If the session
     * was destroyed this has no effect.
     */
    public void stopNotification() {
        mStarted = false;
        mController.unregisterCallback(mCb);
        try {
            mService.unregisterReceiver(this);
        } catch (IllegalArgumentException ex) {
            // ignore if the receiver is not registered.
        }
        mService.stopForeground(true);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        LogHelper.d(TAG, "Received intent with action " + action);
        if (ACTION_PAUSE.equals(action)) {
            mTransportControls.pause();
        } else if (ACTION_PLAY.equals(action)) {
            mTransportControls.play();
        } else if (ACTION_NEXT.equals(action)) {
            mTransportControls.skipToNext();
        } else if (ACTION_PREV.equals(action)) {
            mTransportControls.skipToPrevious();
        }
    }

    /**
     * Update the state based on a change on the session token. Called either when
     * we are running for the first time or when the media session owner has destroyed the session
     * (see {@link android.media.session.MediaController.Callback#onSessionDestroyed()})
     */
    private void updateSessionToken() {
        MediaSession.Token freshToken = mService.getSessionToken();
        if (mSessionToken == null || !mSessionToken.equals(freshToken)) {
            if (mController != null) {
                mController.unregisterCallback(mCb);
            }
            mSessionToken = freshToken;
            mController = new MediaController(mService, mSessionToken);
            mTransportControls = mController.getTransportControls();
            if (mStarted) {
                mController.registerCallback(mCb);
            }
        }
    }

    private final MediaController.Callback mCb = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            mPlaybackState = state;
            LogHelper.d(TAG, "Received new playback state", state);
            updateNotificationPlaybackState();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            mMetadata = metadata;
            LogHelper.d(TAG, "Received new metadata ", metadata);
            updateNotificationMetadata();
        }

        @Override
        public void onSessionDestroyed() {
            super.onSessionDestroyed();
            LogHelper.d(TAG, "Session was destroyed, resetting to the new session token");
            updateSessionToken();
        }
    };

    private void updateNotificationMetadata() {
        LogHelper.d(TAG, "updateNotificationMetadata. mMetadata=" + mMetadata);
        if (mMetadata == null) {
            return;
        }

        updatePlayPauseAction();

        boolean firstRun = false;

        if (mNotificationBuilder == null) {
            firstRun = true;

            mNotificationBuilder = new Notification.Builder(mService);

            mNotificationBuilder
                    .addAction(android.R.drawable.ic_media_previous,
                            mService.getString(R.string.label_previous),
                            mIntents.get(android.R.drawable.ic_media_previous))
                    .addAction(mPlayPauseAction)
                    .addAction(android.R.drawable.ic_media_next,
                            mService.getString(R.string.label_next),
                            mIntents.get(android.R.drawable.ic_media_next))
                    .setStyle(new Notification.MediaStyle()
                            .setShowActionsInCompactView(1)  // only show play/pause in compact view
                            .setMediaSession(mSessionToken))
                    .setColor(android.R.attr.colorPrimaryDark)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setUsesChronometer(true);
        }

        MediaDescription description = mMetadata.getDescription();
        Bitmap art = description.getIconBitmap();
        mNotificationBuilder
                .setContentTitle(description.getTitle())
                .setContentText(description.getSubtitle())
                .setLargeIcon(art);

        updateNotificationPlaybackState();

        if (firstRun) {
            mService.startForeground(NOTIFICATION_ID, mNotificationBuilder.build());
        } else {
            mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
        }

        if (art == null && description.getIconUri() != null) {
            // This sample assumes the iconUri will be a valid URL formatted String, but
            // it can actually be any valid Android Uri formatted String.
            String albumUrl = description.getIconUri().toString();
            if (mCurrentAlbumArt == null || !mCurrentAlbumArt.equals(albumUrl)) {
                mCurrentAlbumArt = albumUrl;
                // async fetch the album art icon
                getBitmapFromURLAsync(albumUrl);
            }
        }
    }

    private void updatePlayPauseAction() {
        LogHelper.d(TAG, "updatePlayPauseAction");
        String playPauseLabel = "";
        int playPauseIcon;
        if (mPlaybackState.getState() == PlaybackState.STATE_PLAYING) {
            playPauseLabel = mService.getString(R.string.label_pause);
            playPauseIcon = android.R.drawable.ic_media_pause;
        } else {
            playPauseLabel = mService.getString(R.string.label_play);
            playPauseIcon = android.R.drawable.ic_media_play;
        }
        if (mPlayPauseAction == null) {
            mPlayPauseAction = new Notification.Action(playPauseIcon, playPauseLabel,
                    mIntents.get(playPauseIcon));
        } else {
            mPlayPauseAction.icon = playPauseIcon;
            mPlayPauseAction.title = playPauseLabel;
            mPlayPauseAction.actionIntent = mIntents.get(playPauseIcon);
        }
    }

    private void updateNotificationPlaybackState() {
        LogHelper.d(TAG, "updateNotificationPlaybackState. mPlaybackState=" + mPlaybackState);
        if (mPlaybackState == null || !mStarted) {
            LogHelper.d(TAG, "updateNotificationPlaybackState. cancelling notification!");
            mService.stopForeground(true);
            return;
        }
        if (mNotificationBuilder == null) {
            LogHelper.d(TAG, "updateNotificationPlaybackState. there is no notificationBuilder. Ignoring request to update state!");
            return;
        }
        if (mPlaybackState.getPosition() >= 0) {
            LogHelper.d(TAG, "updateNotificationPlaybackState. updating playback position to ",
                    (System.currentTimeMillis() - mPlaybackState.getPosition()) / 1000, " seconds");
            mNotificationBuilder
                    .setWhen(System.currentTimeMillis() - mPlaybackState.getPosition())
                    .setShowWhen(true)
                    .setUsesChronometer(true);
            mNotificationBuilder.setShowWhen(true);
        } else {
            LogHelper.d(TAG, "updateNotificationPlaybackState. hiding playback position");
            mNotificationBuilder
                    .setWhen(0)
                    .setShowWhen(false)
                    .setUsesChronometer(false);
        }

        updatePlayPauseAction();

        mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
    }

    public void getBitmapFromURLAsync(final String source) {
        LogHelper.d(TAG, "getBitmapFromURLAsync: starting asynctask to fetch ", source);
        new AsyncTask() {
            @Override
            protected Object doInBackground(Object[] objects) {
                try {
                    Bitmap bitmap = BitmapHelper.fetchAndRescaleBitmap(source,
                            BitmapHelper.MEDIA_ART_BIG_WIDTH, BitmapHelper.MEDIA_ART_BIG_HEIGHT);
                    if (mMetadata != null) {
                        MediaDescription currentDescription = mMetadata.getDescription();
                        // If the media is still the same, update the notification:
                        if (mNotificationBuilder != null &&
                                currentDescription.getIconUri().toString().equals(source)) {
                            LogHelper.d(TAG, "getBitmapFromURLAsync: set bitmap to ", source);
                            mCurrentAlbumArt = source;
                            mNotificationBuilder.setLargeIcon(bitmap);
                            mNotificationManager.notify(NOTIFICATION_ID,
                                    mNotificationBuilder.build());
                        }
                    }
                } catch (IOException e) {
                    LogHelper.e(TAG, e, "getBitmapFromURLAsync: " + source);
                }
                return null;
            }
        }.execute();
    }

}
