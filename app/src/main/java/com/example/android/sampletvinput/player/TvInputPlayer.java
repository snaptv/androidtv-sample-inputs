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
package com.example.android.sampletvinput.player;

import android.util.Log;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import com.google.android.exoplayer.DefaultLoadControl;
import com.google.android.exoplayer.DummyTrackRenderer;
import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.ExoPlayerLibraryInfo;
import com.google.android.exoplayer.LoadControl;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecTrackRenderer;
import com.google.android.exoplayer.MediaCodecUtil;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.audio.AudioTrack;
import com.google.android.exoplayer.chunk.ChunkSampleSource;
import com.google.android.exoplayer.chunk.ChunkSource;
import com.google.android.exoplayer.chunk.Format;
import com.google.android.exoplayer.chunk.FormatEvaluator;
// import com.google.android.exoplayer.chunk.MultiTrackChunkSource;
import com.google.android.exoplayer.dash.DashChunkSource;
import com.google.android.exoplayer.dash.mpd.AdaptationSet;
import com.google.android.exoplayer.dash.mpd.MediaPresentationDescription;
import com.google.android.exoplayer.dash.mpd.MediaPresentationDescriptionParser;
import com.google.android.exoplayer.dash.mpd.Period;
import com.google.android.exoplayer.dash.mpd.Representation;
import com.google.android.exoplayer.hls.HlsChunkSource;
import com.google.android.exoplayer.hls.HlsPlaylist;
import com.google.android.exoplayer.hls.HlsPlaylistParser;
import com.google.android.exoplayer.hls.HlsSampleSource;
import com.google.android.exoplayer.extractor.ExtractorSampleSource;
import com.google.android.exoplayer.extractor.ExtractorInput;
import com.google.android.exoplayer.extractor.ExtractorOutput;
import com.google.android.exoplayer.extractor.ts.TsExtractor;
import com.google.android.exoplayer.extractor.ts.PtsTimestampAdjuster;
import com.google.android.exoplayer.extractor.PositionHolder;
// import com.google.android.exoplayer.source.DefaultSampleSource;
// import com.google.android.exoplayer.source.FrameworkSampleExtractor;
import com.google.android.exoplayer.text.TextRenderer;
import com.google.android.exoplayer.text.Cue;
import com.google.android.exoplayer.text.eia608.Eia608TrackRenderer;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DefaultAllocator;
import com.google.android.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer.upstream.DefaultUriDataSource;
import com.google.android.exoplayer.upstream.UdpDataSource;
import com.google.android.exoplayer.upstream.TransferListener;
import com.google.android.exoplayer.util.ManifestFetcher;
import com.google.android.exoplayer.util.MimeTypes;
import com.google.android.exoplayer.util.Util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A wrapper around {@link ExoPlayer} that provides a higher level interface. Designed for
 * integration with {@link android.media.tv.TvInputService}.
 */
public class TvInputPlayer implements TextRenderer {
    private static final String TAG = "TvInputPlayer";

    public static final int SOURCE_TYPE_HTTP_PROGRESSIVE = 0;
    public static final int SOURCE_TYPE_HLS = 1;
    public static final int SOURCE_TYPE_MPEG_DASH = 2;
    public static final int SOURCE_TYPE_MPEGTS_MCAST = 3;

    private static final int RENDERER_COUNT = 3;
    private static final int MIN_BUFFER_MS = 1000;
    private static final int MIN_REBUFFER_MS = 5000;

    private static final int BUFFER_SEGMENT_SIZE = 64 * 1024;
    private static final int VIDEO_BUFFER_SEGMENTS = 200;
    private static final int AUDIO_BUFFER_SEGMENTS = 60;
    private static final int LIVE_EDGE_LATENCY_MS = 30000;

    private static final int NO_TRACK_SELECTED = -1;

    private final Handler mHandler;
    private final ExoPlayer mPlayer;
    private TrackRenderer mVideoRenderer;
    private TrackRenderer mAudioRenderer;
    private TrackRenderer mTextRenderer;
    private final CopyOnWriteArrayList<Callback> mCallbacks;
    private float mVolume;
    private Surface mSurface;
    private Long mPendingSeekPosition;
    private final TvTrackInfo[][] mTvTracks = new TvTrackInfo[RENDERER_COUNT][];
    private final int[] mSelectedTvTracks = new int[RENDERER_COUNT];
    // private final MultiTrackChunkSource[] mMultiTrackSources =
    //         new MultiTrackChunkSource[RENDERER_COUNT];

    private final MediaCodecVideoTrackRenderer.EventListener mVideoRendererEventListener =
            new MediaCodecVideoTrackRenderer.EventListener() {
        @Override
        public void onDroppedFrames(int count, long elapsed) {
            // Do nothing.
        }

        @Override
        public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
            // Do nothing.
        }

        @Override
        public void onDrawnToSurface(Surface surface) {
            for(Callback callback : mCallbacks) {
                callback.onDrawnToSurface(surface);
            }
        }

        @Override
        public void onDecoderInitialized(String decoderName, long elapsedRealtimeMs, long initializationDurationMs) {
            // Do nothing.
            Log.d(TAG, "onDecoderInitialized(" + decoderName + ", ...)");
        }

        @Override
        public void onDecoderInitializationError(
                MediaCodecTrackRenderer.DecoderInitializationException e) {
            for(Callback callback : mCallbacks) {
                callback.onPlayerError(new ExoPlaybackException(e));
            }
        }

        @Override
        public void onCryptoError(MediaCodec.CryptoException e) {
            for(Callback callback : mCallbacks) {
                callback.onPlayerError(new ExoPlaybackException(e));
            }
        }
    };

    private final MediaCodecAudioTrackRenderer.EventListener mAudioRendererEventListener =
            new MediaCodecAudioTrackRenderer.EventListener() {
        @Override
        public void onDecoderInitializationError(MediaCodecAudioTrackRenderer.DecoderInitializationException e) {
            Log.e(TAG, "onDecoderInitializationError" + e);
        }

        @Override
        public void onCryptoError(MediaCodec.CryptoException e) {
            Log.e(TAG, "onCryptoError", e);
        }

        @Override
        public void onDecoderInitialized(String decoderName, long elapsedRealtimeMs, long initializationDurationMs) {
            Log.d(TAG, "MediaCodecAudioTrackRenderer#onDecoderInitialized(" + decoderName + ", ...)");
        }

        @Override
        public void onAudioTrackInitializationError(AudioTrack.InitializationException e) {
            Log.e(TAG, "onAudioTrackInitializationError " + e);
        }

        @Override
        public void onAudioTrackWriteError(AudioTrack.WriteException e) {
            Log.e(TAG, "onAudioTrackWriteError " + e);
        }

        @Override
        public void onAudioTrackUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
            Log.w(TAG, "onAudioTrackUnderrun");
        }
    };

    public TvInputPlayer() {
        mHandler = new Handler();
        for (int i = 0; i < RENDERER_COUNT; ++i) {
            mTvTracks[i] = new TvTrackInfo[0];
            mSelectedTvTracks[i] = NO_TRACK_SELECTED;
        }
        mCallbacks = new CopyOnWriteArrayList<>();
        mPlayer = ExoPlayer.Factory.newInstance(RENDERER_COUNT, MIN_BUFFER_MS, MIN_REBUFFER_MS);
        mPlayer.addListener(new ExoPlayer.Listener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                Log.d(TAG, "onPlayerStateChanged(" + playWhenReady + ", " + playbackState + ")");
                for(Callback callback : mCallbacks) {
                    callback.onPlayerStateChanged(playWhenReady, playbackState);
                }
                // if (mPendingSeekPosition != null && playbackState != ExoPlayer.STATE_IDLE
                //         && playbackState != ExoPlayer.STATE_PREPARING) {
                //     seekTo(mPendingSeekPosition);
                //     mPendingSeekPosition = null;
                // }
            }

            @Override
            public void onPlayWhenReadyCommitted() {
                for(Callback callback : mCallbacks) {
                    callback.onPlayWhenReadyCommitted();
                }
            }

            @Override
            public void onPlayerError(ExoPlaybackException e) {
                Log.e(TAG, "Error:" + e);
                for(Callback callback : mCallbacks) {
                    callback.onPlayerError(e);
                }
            }
        });
    }

    @Override
    public void onCues(List<Cue> cues) {
        for (Callback callback : mCallbacks) {
            callback.onCues(cues);
        }
    }

    public void prepare(final Context context, final Uri uri, int sourceType) {
        Log.w(TAG, "PREPARE " + uri + " type " + sourceType);
        final String userAgent = getUserAgent(context);

        if (sourceType == SOURCE_TYPE_HTTP_PROGRESSIVE) {
            DataSource dataSource = new DefaultUriDataSource(context, userAgent);
            ExtractorSampleSource sampleSource =
                new ExtractorSampleSource(uri, dataSource,
                        VIDEO_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE);
            mAudioRenderer = new MediaCodecAudioTrackRenderer(sampleSource);
            mVideoRenderer = new MediaCodecVideoTrackRenderer(context, sampleSource,
                    MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, 0, mHandler,
                    mVideoRendererEventListener, 50);
            mTextRenderer = new DummyTrackRenderer();
            prepareInternal();
        } else if (sourceType == SOURCE_TYPE_HLS) {
            HlsPlaylistParser parser = new HlsPlaylistParser();
            DefaultUriDataSource manifestDataSource = new DefaultUriDataSource(context, userAgent);
            ManifestFetcher<HlsPlaylist> playlistFetcher =
                    new ManifestFetcher<>(uri.toString(), manifestDataSource, parser);
            playlistFetcher.singleLoad(mHandler.getLooper(),
                    new ManifestFetcher.ManifestCallback<HlsPlaylist>() {
                        @Override
                        public void onSingleManifest(HlsPlaylist manifest) {
                            DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
                            DataSource dataSource = new DefaultUriDataSource(context, userAgent);
                            LoadControl loadControl = new DefaultLoadControl(new DefaultAllocator(BUFFER_SEGMENT_SIZE));
                            HlsChunkSource chunkSource = new HlsChunkSource(dataSource,
                                    uri.toString(), manifest, bandwidthMeter, null,
                                    HlsChunkSource.ADAPTIVE_MODE_SPLICE);
                            HlsSampleSource sampleSource = new HlsSampleSource(chunkSource, loadControl,
                                    VIDEO_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE);
                            mAudioRenderer = new MediaCodecAudioTrackRenderer(sampleSource);
                            mVideoRenderer = new MediaCodecVideoTrackRenderer(context, sampleSource,
                                    MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, 0, mHandler,
                                    mVideoRendererEventListener, 50);
                            mTextRenderer = new Eia608TrackRenderer(sampleSource,
                                    TvInputPlayer.this, mHandler.getLooper());
                            // TODO: Implement custom HLS source to get the internal track metadata.
                            mTvTracks[TvTrackInfo.TYPE_SUBTITLE] = new TvTrackInfo[1];
                            mTvTracks[TvTrackInfo.TYPE_SUBTITLE][0] =
                                    new TvTrackInfo.Builder(TvTrackInfo.TYPE_SUBTITLE, "1")
                                        .build();
                            prepareInternal();
                        }

                        @Override
                        public void onSingleManifestError(IOException e) {
                            for (Callback callback : mCallbacks) {
                                callback.onPlayerError(new ExoPlaybackException(e));
                            }
                        }
                    });
        // } else if (sourceType == SOURCE_TYPE_MPEG_DASH) {
        //     MediaPresentationDescriptionParser parser = new MediaPresentationDescriptionParser();
        //     DefaultUriDataSource manifestDataSource = new DefaultUriDataSource(context, userAgent);
        //     final ManifestFetcher<MediaPresentationDescription> manifestFetcher =
        //             new ManifestFetcher<>(uri.toString(), manifestDataSource, parser);
        //     manifestFetcher.singleLoad(mHandler.getLooper(),
        //             new ManifestFetcher.ManifestCallback<MediaPresentationDescription>() {
        //         @Override
        //         public void onSingleManifest(MediaPresentationDescription manifest) {
        //             Period period = manifest.getPeriod(0);
        //             LoadControl loadControl = new DefaultLoadControl(new DefaultAllocator(BUFFER_SEGMENT_SIZE));
        //             DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();

        //             // Determine which video representations we should use for playback.
        //             int maxDecodableFrameSize;
        //             try {
        //                 maxDecodableFrameSize = MediaCodecUtil.maxH264DecodableFrameSize();
        //             } catch (MediaCodecUtil.DecoderQueryException e) {
        //                 for (Callback callback : mCallbacks) {
        //                     callback.onPlayerError(new ExoPlaybackException(e));
        //                 }
        //                 return;
        //             }

        //             int videoAdaptationSetIndex = period.getAdaptationSetIndex(
        //                     AdaptationSet.TYPE_VIDEO);
        //             List<Representation> videoRepresentations =
        //                     period.adaptationSets.get(videoAdaptationSetIndex).representations;
        //             ArrayList<Integer> videoRepresentationIndexList = new ArrayList<>();
        //             for (int i = 0; i < videoRepresentations.size(); i++) {
        //                 Format format = videoRepresentations.get(i).format;
        //                 if (format.width * format.height > maxDecodableFrameSize) {
        //                     // Filtering stream that device cannot play
        //                 } else if (!format.mimeType.equals(MimeTypes.VIDEO_MP4)
        //                         && !format.mimeType.equals(MimeTypes.VIDEO_WEBM)) {
        //                     // Filtering unsupported mime type
        //                 } else {
        //                     videoRepresentationIndexList.add(i);
        //                 }
        //             }

        //             // Build the video renderer.
        //             if (videoRepresentationIndexList.isEmpty()) {
        //                 mVideoRenderer = new DummyTrackRenderer();
        //             } else {
        //                 int[] videoRepresentationIndices = Util.toArray(
        //                         videoRepresentationIndexList);
        //                 DataSource videoDataSource = new DefaultUriDataSource(context, userAgent);
        //                 ChunkSource videoChunkSource = new DashChunkSource(manifestFetcher,
        //                         videoAdaptationSetIndex, videoRepresentationIndices,
        //                         videoDataSource,
        //                         new FormatEvaluator.AdaptiveEvaluator(bandwidthMeter),
        //                         LIVE_EDGE_LATENCY_MS);
        //                 ChunkSampleSource videoSampleSource = new ChunkSampleSource(
        //                         videoChunkSource, loadControl,
        //                         VIDEO_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE, true);
        //                 mVideoRenderer = new MediaCodecVideoTrackRenderer(videoSampleSource,
        //                         MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, 0, mHandler,
        //                         mVideoRendererEventListener, 50);
        //             }

        //             // Build the audio chunk sources.
        //             int audioAdaptationSetIndex = period.getAdaptationSetIndex(
        //                     AdaptationSet.TYPE_AUDIO);
        //             AdaptationSet audioAdaptationSet = period.adaptationSets.get(
        //                     audioAdaptationSetIndex);
        //             List<ChunkSource> audioChunkSourceList = new ArrayList<>();
        //             List<TvTrackInfo> audioTrackList = new ArrayList<>();
        //             if (audioAdaptationSet != null) {
        //                 DataSource audioDataSource = new UriDataSource(userAgent, bandwidthMeter);
        //                 FormatEvaluator audioEvaluator = new FormatEvaluator.FixedEvaluator();
        //                 List<Representation> audioRepresentations =
        //                         audioAdaptationSet.representations;
        //                 for (int i = 0; i < audioRepresentations.size(); i++) {
        //                     Format format = audioRepresentations.get(i).format;
        //                     audioTrackList.add(new TvTrackInfo.Builder(TvTrackInfo.TYPE_AUDIO,
        //                             Integer.toString(i))
        //                             .setAudioChannelCount(format.numChannels)
        //                             .setAudioSampleRate(format.audioSamplingRate)
        //                             .setLanguage(format.language)
        //                             .build());
        //                     audioChunkSourceList.add(new DashChunkSource(manifestFetcher,
        //                             audioAdaptationSetIndex, new int[] {i}, audioDataSource,
        //                             audioEvaluator, LIVE_EDGE_LATENCY_MS));
        //                 }
        //             }

        //             // Build the audio renderer.
        //             final MultiTrackChunkSource audioChunkSource;
        //             if (audioChunkSourceList.isEmpty()) {
        //                 mAudioRenderer = new DummyTrackRenderer();
        //             } else {
        //                 audioChunkSource = new MultiTrackChunkSource(audioChunkSourceList);
        //                 SampleSource audioSampleSource = new ChunkSampleSource(audioChunkSource,
        //                         loadControl, AUDIO_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE, true);
        //                 mAudioRenderer = new MediaCodecAudioTrackRenderer(audioSampleSource);
        //                 TvTrackInfo[] tracks = new TvTrackInfo[audioTrackList.size()];
        //                 audioTrackList.toArray(tracks);
        //                 mTvTracks[TvTrackInfo.TYPE_AUDIO] = tracks;
        //                 mSelectedTvTracks[TvTrackInfo.TYPE_AUDIO] = 0;
        //                 mMultiTrackSources[TvTrackInfo.TYPE_AUDIO] = audioChunkSource;
        //             }

        //             // Build the text renderer.
        //             mTextRenderer = new DummyTrackRenderer();

        //             prepareInternal();
        //         }

        //         @Override
        //         public void onSingleManifestError(IOException e) {
        //             for (Callback callback : mCallbacks) {
        //                 callback.onPlayerError(new ExoPlaybackException(e));
        //             }
        //         }
        //     });

        } else if (sourceType == SOURCE_TYPE_MPEGTS_MCAST) {
            final int requestedBufferSize = VIDEO_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE;
            DataSource dataSource = new UdpDataSource(null);
            ExtractorSampleSource sampleSource =
                new ExtractorSampleSource(uri, dataSource, requestedBufferSize, new TsExtractor(new PtsTimestampAdjuster(0), true));
                // new ExtractorSampleSource(uri, dataSource, requestedBufferSize);
            mAudioRenderer = new MediaCodecAudioTrackRenderer(sampleSource, null, mAudioRendererEventListener);
            mVideoRenderer = new MediaCodecVideoTrackRenderer(context, sampleSource,
                    MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, 0, mHandler,
                    mVideoRendererEventListener, 50);
            mTextRenderer = new DummyTrackRenderer();
            prepareInternal();

        } else {
            throw new IllegalArgumentException("Unknown source type: " + sourceType);
        }
    }

    public TvTrackInfo[] getTracks(int trackType) {
        if (trackType < 0 || trackType >= mTvTracks.length) {
            throw new IllegalArgumentException("Illegal track type: " + trackType);
        }
        return mTvTracks[trackType];
    }

    public String getSelectedTrack(int trackType) {
        if (trackType < 0 || trackType >= mTvTracks.length) {
            throw new IllegalArgumentException("Illegal track type: " + trackType);
        }
        if (mSelectedTvTracks[trackType] == NO_TRACK_SELECTED) {
            return null;
        }
        return mTvTracks[trackType][mSelectedTvTracks[trackType]].getId();
    }

    public boolean selectTrack(int trackType, String trackId) {
        if (trackType < 0 || trackType >= mTvTracks.length) {
            return false;
        }
        if (trackId == null) {
            mPlayer.setRendererEnabled(trackType, false);
        } else {
            // int trackIndex = Integer.parseInt(trackId);
            // if (mMultiTrackSources[trackType] == null) {
                mPlayer.setRendererEnabled(trackType, true);
            // } else {
            //     boolean playWhenReady = mPlayer.getPlayWhenReady();
            //     mPlayer.setPlayWhenReady(false);
            //     mPlayer.setRendererEnabled(trackType, false);
            //     mPlayer.sendMessage(mMultiTrackSources[trackType],
            //             MultiTrackChunkSource.MSG_SELECT_TRACK, trackIndex);
            //     mPlayer.setRendererEnabled(trackType, true);
            //     mPlayer.setPlayWhenReady(playWhenReady);
            // }
        }
        return true;
    }

    public void setPlayWhenReady(boolean playWhenReady) {
        mPlayer.setPlayWhenReady(playWhenReady);
    }

    public void setVolume(float volume) {
        mVolume = volume;
        if (mPlayer != null && mAudioRenderer != null) {
            mPlayer.sendMessage(mAudioRenderer, MediaCodecAudioTrackRenderer.MSG_SET_VOLUME,
                    volume);
        }
    }

    public void setSurface(Surface surface) {
        mSurface = surface;
        if (mPlayer != null && mVideoRenderer != null) {
            mPlayer.sendMessage(mVideoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE,
                    surface);
        }
    }

    public void seekTo(long position) {
        // if (isPlayerPrepared(mPlayer)) {  // The player doesn't know the duration until prepared.
        //     if (mPlayer.getDuration() != ExoPlayer.UNKNOWN_TIME) {
        //         mPlayer.seekTo(position);
        //     }
        // } else {
        //     mPendingSeekPosition = position;
        // }
    }

    public void stop() {
        mPlayer.stop();
    }

    public void release() {
        mPlayer.release();
    }

    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    public void removeCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    private void prepareInternal() {
        mPlayer.prepare(mAudioRenderer, mVideoRenderer, mTextRenderer);
        mPlayer.sendMessage(mAudioRenderer, MediaCodecAudioTrackRenderer.MSG_SET_VOLUME,
                mVolume);
        mPlayer.sendMessage(mVideoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE,
                mSurface);
        // Disable text track by default.
        mPlayer.setRendererEnabled(TvTrackInfo.TYPE_SUBTITLE, false);
        for (Callback callback : mCallbacks) {
            callback.onPrepared();
        }
    }

    private static String getUserAgent(Context context) {
        String versionName;
        try {
            String packageName = context.getPackageName();
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            versionName = info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            versionName = "?";
        }
        return "SampleTvInput/" + versionName + " (Linux;Android " + Build.VERSION.RELEASE +
                ") " + "ExoPlayerLib/" + ExoPlayerLibraryInfo.VERSION;
    }

    private static boolean isPlayerPrepared(ExoPlayer player) {
        int state = player.getPlaybackState();
        return state != ExoPlayer.STATE_PREPARING && state != ExoPlayer.STATE_IDLE;
    }

    public interface Callback {
        void onPrepared();
        void onPlayerStateChanged(boolean playWhenReady, int state);
        void onPlayWhenReadyCommitted();
        void onPlayerError(ExoPlaybackException e);
        void onDrawnToSurface(Surface surface);
        void onCues(List<Cue> cues);
    }
}
