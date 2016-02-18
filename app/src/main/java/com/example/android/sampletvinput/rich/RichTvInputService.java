/*
 * Copyright 2015 The Android Open Source Project
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

package com.example.android.sampletvinput.rich;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Point;
import android.media.tv.TvContentRating;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.CaptioningManager;

import com.example.android.sampletvinput.R;
import com.example.android.sampletvinput.TvContractUtils;
import com.example.android.sampletvinput.data.Program;
import com.example.android.sampletvinput.player.TvInputPlayer;
import com.example.android.sampletvinput.syncadapter.SyncUtils;
import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.text.CaptionStyleCompat;
import com.google.android.exoplayer.text.SubtitleLayout;
import com.google.android.exoplayer.text.Cue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TvInputService which provides a full implementation of EPG, subtitles, multi-audio,
 * parental controls, and overlay view.
 */
public class RichTvInputService extends TvInputService {
    private static final String TAG = "RichTvInputService";

    private HandlerThread mHandlerThread;
    private Handler mDbHandler;

    private List<RichTvInputSessionImpl> mSessions;
    private CaptioningManager mCaptioningManager;

    private final BroadcastReceiver mParentalControlsBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mSessions != null) {
                for (RichTvInputSessionImpl session : mSessions) {
                    session.checkContentBlockNeeded();
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mDbHandler = new Handler(mHandlerThread.getLooper());
        mCaptioningManager = (CaptioningManager) getSystemService(Context.CAPTIONING_SERVICE);

        setTheme(android.R.style.Theme_Holo_Light_NoActionBar);

        mSessions = new ArrayList<>();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TvInputManager.ACTION_BLOCKED_RATINGS_CHANGED);
        intentFilter.addAction(TvInputManager.ACTION_PARENTAL_CONTROLS_ENABLED_CHANGED);
        registerReceiver(mParentalControlsBroadcastReceiver, intentFilter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mParentalControlsBroadcastReceiver);
        mHandlerThread.quit();
        mHandlerThread = null;
        mDbHandler = null;
    }

    @Override
    public final Session onCreateSession(String inputId) {
        RichTvInputSessionImpl session = new RichTvInputSessionImpl(this, inputId);
        session.setOverlayViewEnabled(true);
        mSessions.add(session);
        return session;
    }

    class RichTvInputSessionImpl extends TvInputService.Session implements Handler.Callback {
        private static final int MSG_PLAY_PROGRAM = 1000;
        private static final float CAPTION_LINE_HEIGHT_RATIO = 0.0533f;

        private final Context mContext;
        private final String mInputId;
        private final TvInputManager mTvInputManager;
        protected TvInputPlayer mPlayer;
        private Surface mSurface;
        private float mVolume;
        private boolean mCaptionEnabled;
        private Program mCurrentProgram;
        private TvContentRating mLastBlockedRating;
        private TvContentRating mCurrentContentRating;
        private String mSelectedSubtitleTrackId;
        private SubtitleLayout mSubtitleView;
        private boolean mEpgSyncRequested;
        private final Set<TvContentRating> mUnblockedRatingSet = new HashSet<>();
        private final Handler mHandler;

        private final TvInputPlayer.Callback mPlayerCallback = new TvInputPlayer.Callback() {
            private boolean mFirstFrameDrawn;
            @Override
            public void onPrepared() {
                mFirstFrameDrawn = false;
                List<TvTrackInfo> tracks = new ArrayList<>();
                Collections.addAll(tracks, mPlayer.getTracks(TvTrackInfo.TYPE_AUDIO));
                Collections.addAll(tracks, mPlayer.getTracks(TvTrackInfo.TYPE_VIDEO));
                Collections.addAll(tracks, mPlayer.getTracks(TvTrackInfo.TYPE_SUBTITLE));

                notifyTracksChanged(tracks);
                notifyTrackSelected(TvTrackInfo.TYPE_AUDIO, mPlayer.getSelectedTrack(
                        TvTrackInfo.TYPE_AUDIO));
                notifyTrackSelected(TvTrackInfo.TYPE_VIDEO, mPlayer.getSelectedTrack(
                        TvTrackInfo.TYPE_VIDEO));
                notifyTrackSelected(TvTrackInfo.TYPE_SUBTITLE, mPlayer.getSelectedTrack(
                        TvTrackInfo.TYPE_SUBTITLE));
            }

            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                if (playWhenReady && playbackState == ExoPlayer.STATE_BUFFERING) {
                    if (mFirstFrameDrawn) {
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING);
                    }
                } else if (playWhenReady && playbackState == ExoPlayer.STATE_READY) {
                    notifyVideoAvailable();
                }
            }

            @Override
            public void onPlayWhenReadyCommitted() {
                // Do nothing.
            }

            @Override
            public void onPlayerError(ExoPlaybackException e) {
                // Do nothing.
            }

            @Override
            public void onDrawnToSurface(Surface surface) {
                mFirstFrameDrawn = true;
                notifyVideoAvailable();
            }

            @Override
            public void onCues(List<Cue> cues) {
                if (mSubtitleView != null) {
                    mSubtitleView.setCues(cues);
                    // if (TextUtils.isEmpty(text)) {
                    //     mSubtitleView.setVisibility(View.INVISIBLE);
                    // } else {
                    //     mSubtitleView.setVisibility(View.VISIBLE);
                    //     mSubtitleView.setCues(cues);
                    // }
                }
            }
        };

        private PlayCurrentProgramRunnable mPlayCurrentProgramRunnable;

        protected RichTvInputSessionImpl(Context context, String inputId) {
            super(context);

            mContext = context;
            mInputId = inputId;
            mTvInputManager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
            mLastBlockedRating = null;
            mCaptionEnabled = mCaptioningManager.isEnabled();
            mHandler = new Handler(this);
        }

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PLAY_PROGRAM:
                    playProgram((Program) msg.obj);
                    return true;
            }
            return false;
        }

        @Override
        public void onRelease() {
            if (mDbHandler != null) {
                mDbHandler.removeCallbacks(mPlayCurrentProgramRunnable);
            }
            releasePlayer();
            mSessions.remove(this);
        }

        @Override
        public View onCreateOverlayView() {
            LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            View view = inflater.inflate(R.layout.overlayview, null);
            mSubtitleView = (SubtitleLayout) view.findViewById(R.id.subtitles);

            // Configure the subtitle view.
            CaptionStyleCompat captionStyle;
            float captionTextSize = getCaptionFontSize();
            captionStyle = CaptionStyleCompat.createFromCaptionStyle(
                    mCaptioningManager.getUserStyle());
            captionTextSize *= mCaptioningManager.getFontScale();
            mSubtitleView.setStyle(captionStyle);
            mSubtitleView.setFixedTextSize(0, captionTextSize);
            return view;
        }

        @Override
        public boolean onSetSurface(Surface surface) {
            if (mPlayer != null) {
                mPlayer.setSurface(surface);
            }
            mSurface = surface;
            return true;
        }

        @Override
        public void onSetStreamVolume(float volume) {
            if (mPlayer != null) {
                mPlayer.setVolume(volume);
            }
            mVolume = volume;
        }

        private boolean playProgram(Program info) {
            releasePlayer();

            mCurrentProgram = info;
            mCurrentContentRating = (info.getContentRatings() == null
                    || info.getContentRatings().length == 0) ? null : info.getContentRatings()[0];
            mPlayer = new TvInputPlayer();
            mPlayer.addCallback(mPlayerCallback);
            Pair<Integer, String> videoInfo =TvContractUtils.parseProgramInternalProviderData(
                    info.getInternalProviderData());
            mPlayer.prepare(RichTvInputService.this, Uri.parse(videoInfo.second), videoInfo.first);
            mPlayer.setSurface(mSurface);
            mPlayer.setVolume(mVolume);

            long nowMs = System.currentTimeMillis();
            int seekPosMs = (int) (nowMs - info.getStartTimeUtcMillis());
            // if (seekPosMs > 0) {
            //     mPlayer.seekTo(seekPosMs);
            // }
            mPlayer.setPlayWhenReady(true);

            checkContentBlockNeeded();
            mDbHandler.postDelayed(mPlayCurrentProgramRunnable,
                    info.getEndTimeUtcMillis() - nowMs + 1000);
            return true;
        }

        @Override
        public boolean onTune(Uri channelUri) {
            Log.d(TAG, "TUNE " + channelUri);
            if (mSubtitleView != null) {
                mSubtitleView.setVisibility(View.INVISIBLE);
            }
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
            mUnblockedRatingSet.clear();

            mDbHandler.removeCallbacks(mPlayCurrentProgramRunnable);
            mPlayCurrentProgramRunnable = new PlayCurrentProgramRunnable(channelUri);
            mDbHandler.post(mPlayCurrentProgramRunnable);
            return true;
        }

        @Override
        public void onSetCaptionEnabled(boolean enabled) {
            mCaptionEnabled = enabled;
            if (mPlayer != null) {
                if (enabled) {
                    if (mSelectedSubtitleTrackId != null && mPlayer != null) {
                        mPlayer.selectTrack(TvTrackInfo.TYPE_SUBTITLE, mSelectedSubtitleTrackId);
                    }
                } else {
                    mPlayer.selectTrack(TvTrackInfo.TYPE_SUBTITLE, null);
                }
            }
        }

        @Override
        public boolean onSelectTrack(int type, String trackId) {
            if (mPlayer != null) {
                if (type == TvTrackInfo.TYPE_SUBTITLE) {
                    if (!mCaptionEnabled && trackId != null) {
                        return false;
                    }
                    mSelectedSubtitleTrackId = trackId;
                    if (trackId == null) {
                        mSubtitleView.setVisibility(View.INVISIBLE);
                    }
                }
                if (mPlayer.selectTrack(type, trackId)) {
                    notifyTrackSelected(type, trackId);
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onUnblockContent(TvContentRating rating) {
            if (rating != null) {
                unblockContent(rating);
            }
        }

        private void releasePlayer() {
            if (mPlayer != null) {
                mPlayer.removeCallback(mPlayerCallback);
                mPlayer.setSurface(null);
                mPlayer.stop();
                mPlayer.release();
                mPlayer = null;
            }
        }

        private void checkContentBlockNeeded() {
            if (mCurrentContentRating == null || !mTvInputManager.isParentalControlsEnabled()
                    || !mTvInputManager.isRatingBlocked(mCurrentContentRating)
                    || mUnblockedRatingSet.contains(mCurrentContentRating)) {
                // Content rating is changed so we don't need to block anymore.
                // Unblock content here explicitly to resume playback.
                unblockContent(null);
                return;
            }

            mLastBlockedRating = mCurrentContentRating;
            if (mPlayer != null) {
                // Children restricted content might be blocked by TV app as well,
                // but TIS should do its best not to show any single frame of blocked content.
                releasePlayer();
            }

            notifyContentBlocked(mCurrentContentRating);
        }

        private void unblockContent(TvContentRating rating) {
            // TIS should unblock content only if unblock request is legitimate.
            if (rating == null || mLastBlockedRating == null
                    || (mLastBlockedRating != null && rating.equals(mLastBlockedRating))) {
                mLastBlockedRating = null;
                if (rating != null) {
                    mUnblockedRatingSet.add(rating);
                }
                if (mPlayer == null && mCurrentProgram != null) {
                    playProgram(mCurrentProgram);
                }
                notifyContentAllowed();
            }
        }

        private float getCaptionFontSize() {
            Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            Point displaySize = new Point();
            display.getSize(displaySize);
            return Math.max(getResources().getDimension(R.dimen.subtitle_minimum_font_size),
                    CAPTION_LINE_HEIGHT_RATIO * Math.min(displaySize.x, displaySize.y));
        }

        private class PlayCurrentProgramRunnable implements Runnable {
            private static final int RETRY_DELAY_MS = 2000;
            private final Uri mChannelUri;

            public PlayCurrentProgramRunnable(Uri channelUri) {
                mChannelUri = channelUri;
            }

            @Override
            public void run() {
                ContentResolver resolver = mContext.getContentResolver();
                Program program = TvContractUtils.getCurrentProgram(resolver, mChannelUri);
                if (program != null) {
                    mHandler.removeMessages(MSG_PLAY_PROGRAM);
                    mHandler.obtainMessage(MSG_PLAY_PROGRAM, program).sendToTarget();
                } else {
                    Log.w(TAG, "Failed to get program info for " + mChannelUri + ". Retry in "
                            + RETRY_DELAY_MS + "ms.");
                    mDbHandler.postDelayed(mPlayCurrentProgramRunnable, RETRY_DELAY_MS);
                    if (!mEpgSyncRequested) {
                        SyncUtils.requestSync(mInputId, true);
                        mEpgSyncRequested = true;
                    }
                }
            }
        }
    }
}
