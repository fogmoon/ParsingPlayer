/*
 *
 * Copyright (c) 2017 UniqueStudio
 *
 * This file is part of ParsingPlayer.
 *
 * ParsingPlayer is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with ParsingPlayer; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

package com.hustunique.parsingplayer.player;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.RelativeLayout;

import com.hustunique.parsingplayer.LogUtil;
import com.hustunique.parsingplayer.R;
import com.hustunique.parsingplayer.parser.entity.VideoInfo;
import com.hustunique.parsingplayer.parser.provider.ConcatSourceProvider;
import com.hustunique.parsingplayer.parser.provider.Quality;
import com.hustunique.parsingplayer.parser.provider.VideoInfoSourceProvider;
import com.hustunique.parsingplayer.player.io.LoadingCallback;

import java.io.IOException;
import java.util.Map;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkTimedText;

/**
 * Created by JianGuo on 1/16/17.
 * VideoView using {@link tv.danmaku.ijk.media.player.IMediaPlayer} as media player
 */

public class ParsingVideoView extends RelativeLayout implements IMediaPlayerControl {
    private static final String TAG = "ParsingVideoView";
    private static final float MUSIC_SLIDE_GAP = 2f;
    private static final float VOLUME_SLOP = 2f;
    private IParsingPlayer mMediaPlayer;
    private int mVideoWidth;
    private int mVideoHeight;
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private IMediaPlayer.OnCompletionListener mOnCompletionListener;
    private IMediaPlayer.OnPreparedListener mOnPreparedListener;
    private IMediaPlayer.OnErrorListener mOnErrorListener;
    private IMediaPlayer.OnInfoListener mOnInfoListener;

    private Context mContext;

    // all possible internal states
    private static final int STATE_ERROR = -1;
    private static final int STATE_IDLE = 0;
    private static final int STATE_PREPARING = 1;
    private static final int STATE_PREPARED = 2;
    private static final int STATE_PLAYING = 3;
    private static final int STATE_PAUSED = 4;
    private static final int STATE_PLAYBACK_COMPLETED = 5;

    private ViewGroup mDecorView;

    private ControllerView mControllerView;
    private TextureRenderView mRenderView;
    private int mCurrentState;
    private int mTargetState;
    private int mSeekWhenPrepared;
    private int mCurrentBufferPercentage;


    private int mCurrentAspectRatio = IRenderView.AR_ASPECT_FIT_PARENT;

    private int mVideoSarNum;
    private int mVideoSarDen;
    private int mVideoRotationDegree;

    private boolean mIsFullscreen = false;
    private boolean mCanPause = true;
    private boolean mCanSeekBack = true;
    private boolean mCanSeekForward = true;
    private VideoInfoSourceProvider mProvider;
    private AudioManager mAudioManager;
    private float mScreenHeight;
    private float mScreenWidth;

    public ParsingVideoView(Context context) {
        this(context, null);
    }

    public ParsingVideoView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ParsingVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initView(context);
        initGesture();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public ParsingVideoView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initView(context);
        initGesture();
    }

    private void configurePlayer() {
        mCurrentState = mTargetState = STATE_IDLE;
        mVideoHeight = mVideoWidth = 0;
        release(false);
        mAudioManager = (AudioManager) mContext.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        mAudioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        mMediaPlayer = createPlayer();
        configureRenderView();
    }

    // visible for override
    protected IParsingPlayer createPlayer() {
        IParsingPlayer iParsingPlayer = new ParsingPlayer(mContext);
        iParsingPlayer.setOnPreparedListener(mPreparedListener);
        iParsingPlayer.setOnVideoSizeChangedListener(mSizeChangedListener);
        iParsingPlayer.setOnCompletionListener(mCompletionListener);
        iParsingPlayer.setOnErrorListener(mErrorListener);
        iParsingPlayer.setOnInfoListener(mInfoListener);
        iParsingPlayer.setOnBufferingUpdateListener(mBufferingUpdateListener);
        iParsingPlayer.setOnSeekCompleteListener(mSeekCompleteListener);
        iParsingPlayer.setOnTimedTextListener(mOnTimedTextListener);
        return iParsingPlayer;
    }
    private void initView(Context context) {
        mContext = context;
        LayoutInflater.from(context).inflate(R.layout.parsing_video_view, this);
        mRenderView = (TextureRenderView) findViewById(R.id.texture_view);
        mControllerView = (ControllerView) findViewById(R.id.controller_view);
        mScreenHeight = mContext.getResources().getDisplayMetrics().heightPixels;
        mScreenWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        mRenderView.setOnClickListener(mRenderViewClickListener);
        mDecorView = (ViewGroup) ((Activity) getContext()).getWindow().getDecorView();
        mDecorView.setOnSystemUiVisibilityChangeListener(mSysUiChangeListener);
        mControllerView.setFullscreenListner(mFullscreenListener);
    }


    void setQuality(@Quality int quality) {
        // We need to recreate a instance of player to play another video
        // ref: https://github.com/Bilibili/ijkplayer/issues/400
        mSeekWhenPrepared = (int) mMediaPlayer.getCurrentPosition();
        LogUtil.d(TAG, "current Pos: " + mSeekWhenPrepared);
        configurePlayer();
        setConcatContent(mProvider.provideSource(quality));
    }

    // visible for override
    protected void configureRenderView() {
        if (mMediaPlayer != null) {
            mRenderView.getSurfaceHolder().bindToMediaPlayer(mMediaPlayer);
            mRenderView.setVideoSize(mMediaPlayer.getVideoWidth(), mMediaPlayer.getVideoHeight());
            mRenderView.setVideoSampleAspectRatio(mMediaPlayer.getVideoSarNum(),
                    mMediaPlayer.getVideoSarDen());
            mRenderView.setAspectRatioMode(mCurrentAspectRatio);
        }
        mRenderView.setAspectRatioMode(mCurrentAspectRatio);
        if (mVideoWidth > 0 && mVideoHeight > 0)
            mRenderView.setVideoSize(mVideoWidth, mVideoHeight);
        if (mVideoSarNum > 0 && mVideoSarDen > 0)
            mRenderView.setVideoSampleAspectRatio(mVideoSarNum, mVideoSarDen);
        mRenderView.addRenderCallback(mSHCallback);
        mRenderView.setVideoRotation(mVideoRotationDegree);
    }

    private View.OnClickListener mFullscreenListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            if (mIsFullscreen)
                showTiny();
            else
                showFullscreen();
        }
    };

    private View.OnClickListener mRenderViewClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            toggleMediaControlsVisibility();
        }
    };

    private View.OnSystemUiVisibilityChangeListener mSysUiChangeListener = new OnSystemUiVisibilityChangeListener() {
        @Override
        public void onSystemUiVisibilityChange(int visibility) {
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0 && mIsFullscreen) {
                postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        hideSystemUI();
                    }
                }, 1000);
            }
        }
    };

    private IRenderView.ISurfaceHolder mSurfaceHolder;
    private IRenderView.IRenderCallback mSHCallback = new IRenderView.IRenderCallback() {
        @Override
        public void onSurfaceCreated(@NonNull IRenderView.ISurfaceHolder holder, int width, int height) {
            if (holder.getRenderView() != mRenderView) {
                LogUtil.e(TAG, "onSurfaceCreated: unmatched render callback\n");
                return;
            }
            mSurfaceHolder = holder;
            if (mMediaPlayer != null) bindSurfaceHolder(mMediaPlayer, holder);
        }

        @Override
        public void onSurfaceChanged(@NonNull IRenderView.ISurfaceHolder holder, int format, int width, int height) {
            if (holder.getRenderView() != mRenderView) {
                LogUtil.e(TAG, "onSurfaceChanged: unmatched render callback\n");
                return;
            }
            mSurfaceWidth = width;
            mSurfaceHeight = height;
            boolean isValidState = (mTargetState == STATE_PLAYING);
            boolean hasValidSize = !mRenderView.shouldWaitForResize()
                    || (mVideoWidth == width && mVideoHeight == height);
            if (mMediaPlayer != null && isValidState && hasValidSize) {
                if (mSeekWhenPrepared != 0) {
                    seekTo(mSeekWhenPrepared);
                }
                start();
            }
        }

        @Override
        public void onSurfaceDestroyed(@NonNull IRenderView.ISurfaceHolder holder) {
            if (holder.getRenderView() != mRenderView) {
                LogUtil.e(TAG, "onSurfaceDestroyed: unmatched render callback\n");
                return;
            }

            mSurfaceHolder = null;
            releaseWithoutStop();
        }
    };

    private void releaseWithoutStop() {
        if (mMediaPlayer != null) {
            mMediaPlayer.setDisplay(null);
        }
    }


    IMediaPlayer.OnVideoSizeChangedListener mSizeChangedListener =
            new IMediaPlayer.OnVideoSizeChangedListener() {
                public void onVideoSizeChanged(IMediaPlayer mp, int width, int height, int sarNum, int sarDen) {
                    mVideoWidth = mp.getVideoWidth();
                    mVideoHeight = mp.getVideoHeight();
                    mVideoSarNum = mp.getVideoSarNum();
                    mVideoSarDen = mp.getVideoSarDen();
                    if (mVideoWidth != 0 && mVideoHeight != 0) {
                        if (mRenderView != null) {
                            mRenderView.setVideoSize(mVideoWidth, mVideoHeight);
                            mRenderView.setVideoSampleAspectRatio(mVideoSarNum, mVideoSarDen);
                        }
                        requestLayout();
                    }
                }
            };


    private IMediaPlayer.OnPreparedListener mPreparedListener = new IMediaPlayer.OnPreparedListener() {
        @Override
        public void onPrepared(IMediaPlayer mp) {
            mCurrentState = STATE_PREPARED;
            mTargetState = STATE_PLAYING;
            if (mOnPreparedListener != null) {
                mOnPreparedListener.onPrepared(mp);
            }
            setMediaController();
            mVideoWidth = mp.getVideoWidth();
            mVideoHeight = mp.getVideoHeight();
            int seekToPosition = mSeekWhenPrepared;
            if (seekToPosition != 0) {
                seekTo(seekToPosition);
            }
            if (mVideoWidth != 0 && mVideoHeight != 0) {
                if (mRenderView != null) {
                    mRenderView.setVideoSize(mVideoWidth, mVideoHeight);
                    mRenderView.setVideoSampleAspectRatio(mVideoSarNum, mVideoSarDen);

                    if (!mRenderView.shouldWaitForResize() || mSurfaceWidth == mVideoWidth
                            || mSurfaceHeight == mVideoHeight) {
                        if (mTargetState == STATE_PLAYING) {
                            start();
                        } else if (!isPlaying() && (seekToPosition != 0 || getCurrentPosition() > 0)) {
                            if (mControllerView != null) {
                                mControllerView.show();
                            }
                        }
                    }
                }
            } else {
                if (mTargetState == STATE_PLAYING) {
                    start();
                }
            }
        }
    };

    private IMediaPlayer.OnCompletionListener mCompletionListener = new IMediaPlayer.OnCompletionListener() {
        @Override
        public void onCompletion(IMediaPlayer mp) {
            mCurrentState = STATE_PLAYBACK_COMPLETED;
            mTargetState = STATE_PLAYBACK_COMPLETED;
            if (mControllerView != null) {
                mControllerView.complete();
            }
            if (mOnCompletionListener != null) {
                mOnCompletionListener.onCompletion(mp);
            }
        }
    };


    private IMediaPlayer.OnInfoListener mInfoListener = new IMediaPlayer.OnInfoListener() {
        @Override
        public boolean onInfo(IMediaPlayer mp, int arg1, int arg2) {
            if (mOnInfoListener != null) {
                mOnInfoListener.onInfo(mp, arg1, arg2);
            }
            switch (arg1) {
                case IMediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING:
                    LogUtil.d(TAG, "MEDIA_INFO_VIDEO_TRACK_LAGGING:");
                    break;
                case IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START:
                    LogUtil.d(TAG, "MEDIA_INFO_VIDEO_RENDERING_START:");
                    break;
                case IMediaPlayer.MEDIA_INFO_BUFFERING_START:
                    LogUtil.d(TAG, "MEDIA_INFO_BUFFERING_START:");
                    break;
                case IMediaPlayer.MEDIA_INFO_BUFFERING_END:
                    LogUtil.w(TAG, "Bad networking!");
                    LogUtil.d(TAG, "MEDIA_INFO_BUFFERING_END:");
                    break;
                case IMediaPlayer.MEDIA_INFO_NETWORK_BANDWIDTH:
                    LogUtil.d(TAG, "MEDIA_INFO_NETWORK_BANDWIDTH: " + arg2);
                    break;
                case IMediaPlayer.MEDIA_INFO_BAD_INTERLEAVING:
                    LogUtil.d(TAG, "MEDIA_INFO_BAD_INTERLEAVING:");
                    break;
                case IMediaPlayer.MEDIA_INFO_NOT_SEEKABLE:
                    LogUtil.d(TAG, "MEDIA_INFO_NOT_SEEKABLE:");
                    break;
                case IMediaPlayer.MEDIA_INFO_METADATA_UPDATE:
                    LogUtil.d(TAG, "MEDIA_INFO_METADATA_UPDATE:");
                    break;
                case IMediaPlayer.MEDIA_INFO_UNSUPPORTED_SUBTITLE:
                    LogUtil.d(TAG, "MEDIA_INFO_UNSUPPORTED_SUBTITLE:");
                    break;
                case IMediaPlayer.MEDIA_INFO_SUBTITLE_TIMED_OUT:
                    LogUtil.d(TAG, "MEDIA_INFO_SUBTITLE_TIMED_OUT:");
                    break;
                case IMediaPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED:
                    mVideoRotationDegree = arg2;
                    LogUtil.d(TAG, "MEDIA_INFO_VIDEO_ROTATION_CHANGED: " + arg2);
                    if (mRenderView != null)
                        mRenderView.setVideoRotation(arg2);
                    break;
                case IMediaPlayer.MEDIA_INFO_AUDIO_RENDERING_START:
                    LogUtil.d(TAG, "MEDIA_INFO_AUDIO_RENDERING_START:");
                    break;
            }
            return true;
        }
    };

    private IMediaPlayer.OnErrorListener mErrorListener = new IMediaPlayer.OnErrorListener() {
        @Override
        public boolean onError(IMediaPlayer mp, int framework_err, int impl_err) {
            LogUtil.e(TAG, "Error: " + framework_err + "," + impl_err);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            if (mControllerView != null) {
                mControllerView.hide();
            }

                    /* If an error handler has been supplied, use it and finish. */
            if (mOnErrorListener != null) {
                if (mOnErrorListener.onError(mMediaPlayer, framework_err, impl_err)) {
                    return true;
                }
            }
            return true;
        }
    };

    private IMediaPlayer.OnBufferingUpdateListener mBufferingUpdateListener = new IMediaPlayer.OnBufferingUpdateListener() {
        @Override
        public void onBufferingUpdate(IMediaPlayer mp, int percent) {
            mCurrentBufferPercentage = percent;
        }
    };

    private IMediaPlayer.OnSeekCompleteListener mSeekCompleteListener = new IMediaPlayer.OnSeekCompleteListener() {
        @Override
        public void onSeekComplete(IMediaPlayer mp) {

        }
    };

    private IMediaPlayer.OnTimedTextListener mOnTimedTextListener = new IMediaPlayer.OnTimedTextListener() {
        @Override
        public void onTimedText(IMediaPlayer mp, IjkTimedText text) {
            // TODO: 1/21/17 Show subtitle if necessary  
        }
    };

    private void openVideo() {
        mCurrentBufferPercentage = 0;
        bindSurfaceHolder(mMediaPlayer, mSurfaceHolder);
        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mMediaPlayer.setScreenOnWhilePlaying(true);
        mMediaPlayer.prepareAsync();
        mCurrentState = STATE_PREPARING;
    }


    // TODO: 2/5/17 Show sth if the io is running
    private void setConcatContent(String content) {
        LogUtil.i(TAG, "set temp file content: \n" + content);
        // TODO: 2/14/17 create a meaningful temp file name
        configurePlayer();
        mMediaPlayer.setConcatVideoPath(SystemClock.currentThreadTimeMillis() + "",
                content, new LoadingCallback<String>() {
                    @Override
                    public void onSuccess(final String result) {
                        // use post here to run in main thread
                        post(new Runnable() {
                            @Override
                            public void run() {

                                setVideoPath(result);
                            }
                        });

                    }

                    @Override
                    public void onFailed(Exception e) {
                        Log.wtf(TAG, e);
                    }
                });
    }


    /**
     * Set video source info for concat segments.
     *
     * @param videoInfo the video info
     */
    public void setConcatVideos(@NonNull VideoInfo videoInfo, @Quality int quality) {
        mProvider = new ConcatSourceProvider(videoInfo, mContext);
        setConcatContent(mProvider.provideSource(quality));
    }

    /**
     * Set video path
     *
     * @param path the video source path
     */
    public void setVideoPath(String path) {
        setVideoURI(Uri.parse(path));
    }

    /**
     * Set video source uri
     *
     * @param uri the video source uri
     */
    public void setVideoURI(Uri uri) {
        setVideoURI(uri, null);
    }


    private void setVideoURI(Uri uri, Map<String, String> headers) {
        try {
            mMediaPlayer.setDataSource(mContext, uri, headers);
            openVideo();
            requestLayout();
            invalidate();
        } catch (IOException e) {
            LogUtil.wtf(TAG, e);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
        }

    }

    public void setMediaController() {
        if (mControllerView != null) {
            mControllerView.hide();
        }
        attachMediaController();
    }


    private void attachMediaController() {
        if (mMediaPlayer != null && mControllerView != null) {
            mControllerView.setMediaPlayer(this);
        }
    }

    /**
     * Release media player
     *
     * @param clearTargetState <tt>true</tt> if you want to abandon current state,
     *                         <tt>false</tt> otherwise.
     */
    public void release(boolean clearTargetState) {
        if (mMediaPlayer != null) {
            mMediaPlayer.reset();
            mMediaPlayer.release();
            mMediaPlayer = null;
            mCurrentState = STATE_IDLE;
            if (clearTargetState) {
                mTargetState = STATE_IDLE;
            }
            AudioManager am = (AudioManager) mContext.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
            am.abandonAudioFocus(null);
        }
    }

    private void bindSurfaceHolder(IMediaPlayer mp, IRenderView.ISurfaceHolder holder) {
        if (mp == null) return;
        if (holder == null) {
            mp.setDisplay(null);
            return;
        }
        holder.bindToMediaPlayer(mp);
    }

    /**
     * Set view to render frame in video stream.
     *
     * @param renderView see {@link IRenderView} for details
     */
    @Deprecated
    public void setRenderView(IRenderView renderView) {
        if (mRenderView != null) {
            if (mMediaPlayer != null) {
                mMediaPlayer.setDisplay(null);
            }
            mRenderView.removeRenderCallback(mSHCallback);
            mRenderView = null;
        }

        mRenderView = (TextureRenderView) renderView;
        renderView.setAspectRatioMode(mCurrentAspectRatio);
        if (mVideoWidth > 0 && mVideoHeight > 0)
            renderView.setVideoSize(mVideoWidth, mVideoHeight);
        if (mVideoSarNum > 0 && mVideoSarDen > 0)
            renderView.setVideoSampleAspectRatio(mVideoSarNum, mVideoSarDen);
        mRenderView.addRenderCallback(mSHCallback);
        mRenderView.setVideoRotation(mVideoRotationDegree);
    }


    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean isKeyCodeSupported = keyCode != KeyEvent.KEYCODE_BACK &&
                keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
                keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
                keyCode != KeyEvent.KEYCODE_VOLUME_MUTE &&
                keyCode != KeyEvent.KEYCODE_MENU &&
                keyCode != KeyEvent.KEYCODE_CALL &&
                keyCode != KeyEvent.KEYCODE_ENDCALL;
        if (isInPlayBackState() && isKeyCodeSupported && mControllerView != null) {
            if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (mMediaPlayer.isPlaying()) {
                    pause();
                    mControllerView.show();
                } else {
                    start();
                    mControllerView.hide();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                if (!mMediaPlayer.isPlaying()) {
                    start();
                    mControllerView.hide();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (mMediaPlayer.isPlaying()) {
                    pause();
                    mControllerView.show();
                }
                return true;
            } else {
                toggleMediaControlsVisibility();
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private ScaleGestureDetector mScaleGestureDetector;
    private boolean onScale = false;
    private android.view.ScaleGestureDetector.OnScaleGestureListener mScaleListener = new ScaleGestureDetector.SimpleOnScaleGestureListener() {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            onScale = true;
            return super.onScaleBegin(detector);
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactorDiff = detector.getScaleFactor();
            if (detector.isInProgress()) {
                handleScale(scaleFactorDiff);
            }
            return Float.compare(scaleFactorDiff, 1.0f) != 0;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            super.onScaleEnd(detector);
            onScale = false;
        }
    };

    private void handleScale(float scaleFactorDiff) {
        if (mRenderView != null)
            mRenderView.setAspectRatio(scaleFactorDiff);
    }

    private void initGesture() {
        mScaleGestureDetector = new ScaleGestureDetector(mContext, mScaleListener);
    }


    //    private float mLastTouchX;
//    private float mLastTouchY;
//    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;
//    private int mGestureDownVolume;
//    private int mGestureDownBrightness;
//    private boolean mChangeVolume;
//    private boolean mChangeBrightness;
//    @Override
    // TODO: 1/23/17 Implement moving feature
//    public boolean onTouchEvent(MotionEvent event) {
//        mScaleGestureDetector.onTouchEvent(event);
//        if (!onScale) {
//            final int action = event.getActionMasked();
//            switch (action) {
//                case MotionEvent.ACTION_DOWN: {
//                    if (isInPlayBackState() && mControllerView != null) {
//                        toggleMediaControlsVisibility();
//                    }
//                    break;
//                }
//            }
//        }
//        return super.onTouchEvent(event);
//    }


//    // FIXME: 2/17/17 Buggy when scroll up the first time
//    private void updateBrightness(float dy) {
//        float delta = Math.abs(dy);
//        if (Float.compare(delta, VOLUME_SLOP) < 0) return;
//        dy = -dy;
//        int deltaV = (int) (255 * dy * 3 / mScreenHeight);
//        WindowManager.LayoutParams lp = ((Activity) mContext).getWindow().getAttributes();
//        if ((mGestureDownBrightness + deltaV) / 255 >= 1) {
//            lp.screenBrightness = 1;
//        } else if ((mGestureDownBrightness + deltaV) / 255 <= 0) {
//            lp.screenBrightness = 0.01f;
//        } else {
//            lp.screenBrightness = (mGestureDownBrightness + deltaV) / 255;
//        }
//
//        ((Activity) mContext).getWindow().setAttributes(lp);
//        int brightnessPercent = (int) (mGestureDownBrightness * 100 / 255 + dy * 3 * 100 / mScreenHeight);
//        LogUtil.d(TAG, "set brightness: " + brightnessPercent);
//    }
//
//
//    private void updateVolume(float dy) {
//        float delta = Math.abs(dy);
//        if (Float.compare(delta, VOLUME_SLOP) < 0) return;
//        dy = -dy;
//        int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
//        int deltaV = (int) (maxVolume * dy * 3 / (mScreenHeight / 2));
//        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mGestureDownVolume + deltaV, 0);
//        float volumePercentage = mGestureDownVolume * 100 / maxVolume + dy * 3 * 100 / (mScreenHeight * 2);
//        LogUtil.d(TAG, "update volume: " + volumePercentage);
//    }


    private void toggleMediaControlsVisibility() {
        if (mControllerView.isShowing()) {
            mControllerView.hide();
        } else {
            mControllerView.show();
        }
    }

    private boolean isInPlayBackState() {
        return (mMediaPlayer != null &&
                mCurrentState != STATE_ERROR &&
                mCurrentState != STATE_IDLE &&
                mCurrentState != STATE_PREPARING);
    }

    /**
     * set listener when loading is completed.
     *
     * @param onCompletionListener the listener,
     *                             {@link tv.danmaku.ijk.media.player.IMediaPlayer.OnCompletionListener#onCompletion(IMediaPlayer)}
     *                             will be called when player loads the video
     */
    public void setOnCompletionListener(@Nullable IMediaPlayer.OnCompletionListener onCompletionListener) {
        mOnCompletionListener = onCompletionListener;
    }

    /**
     * set listener when error occurs
     *
     * @param onErrorListener {@link tv.danmaku.ijk.media.player.IMediaPlayer.OnErrorListener#onError(IMediaPlayer, int, int)}
     *                        will be called when error occurs
     */
    public void setOnErrorListener(@Nullable IMediaPlayer.OnErrorListener onErrorListener) {
        mOnErrorListener = onErrorListener;
    }

    /**
     * set listener for info
     *
     * @param onInfoListener the listener
     */
    public void setOnInfoListener(@Nullable IMediaPlayer.OnInfoListener onInfoListener) {
        mOnInfoListener = onInfoListener;
    }

    /**
     * set listener when player prepares the data source
     *
     * @param onPreparedListener {@link tv.danmaku.ijk.media.player.IMediaPlayer.OnPreparedListener#onPrepared(IMediaPlayer)}
     *                           will be called when player has done the preparation.
     */
    public void setOnPreparedListener(@Nullable IMediaPlayer.OnPreparedListener onPreparedListener) {
        mOnPreparedListener = onPreparedListener;
    }


    @Override
    public void start() {
        if (isInPlayBackState()) {
            mMediaPlayer.start();
            mCurrentState = STATE_PLAYING;
        }
        mTargetState = STATE_PLAYING;
    }

    @Override
    public void pause() {
        if (isInPlayBackState()) {
            if (mMediaPlayer.isPlaying()) {
                mMediaPlayer.pause();
                mCurrentState = STATE_PAUSED;
            }
        }
        mTargetState = STATE_PAUSED;
    }

    @Override
    public int getDuration() {
        if (isInPlayBackState()) {
            return (int) mMediaPlayer.getDuration();
        }
        return -1;
    }

    @Override
    public int getCurrentPosition() {
        if (isInPlayBackState()) {
            return (int) mMediaPlayer.getCurrentPosition();
        }
        return 0;
    }

    @Override
    public void seekTo(int pos) {
        if (isInPlayBackState()) {
            LogUtil.d(TAG, "seekTo: " + pos);
            mMediaPlayer.seekTo(pos);
            mSeekWhenPrepared = 0;
        } else {
            mSeekWhenPrepared = pos;
        }
    }

    @Override
    public boolean isPlaying() {
        return isInPlayBackState() && mMediaPlayer.isPlaying();
    }

    @Override
    public int getBufferPercentage() {
        if (mMediaPlayer != null) {
            return mCurrentBufferPercentage;
        }
        return 0;
    }

    @Override
    public boolean canPause() {
        return mCanPause;
    }

    @Override
    public boolean canSeekBackward() {
        return mCanSeekBack;
    }

    @Override
    public boolean canSeekForward() {
        return mCanSeekForward;
    }

    @Override
    public void play(String videoUrl) {
        play(videoUrl, VideoInfo.HD_UNSPECIFIED);
    }

    private void play(String videoUrl, @Quality int quality) {
        ParsingTask parsingTask = new ParsingTask(this, quality);
        parsingTask.execute(videoUrl);
    }

    @Override
    public int getAudioSessionId() {
        return 0;
    }


    @Override
    // FIXME: 1/23/17 Can't maintain View status after configuration changes, as we can't maintain mediaplayer here
    protected void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        LogUtil.d(TAG, "onRestoreInstanceState " + ss.toString());
        mCurrentState = ss.currentState;
        mTargetState = ss.targetState;
        mCurrentBufferPercentage = ss.currentBufferPercentage;
        int currPos = ss.currentPos;

        seekTo(currPos);

    }

    private void hideSystemUI() {
        // Set the IMMERSIVE flag.
        // Set the content to appear under the system bars so that the content
        // doesn't resize when the system bars hide and show.
        mDecorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // hide nav bar
                        | View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                        | View.SYSTEM_UI_FLAG_IMMERSIVE);
    }

    //  It does this by removing all the flags
    // except for the ones that make the content appear under the system bars.
    private void showSystemUI() {
        mDecorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // FIXME: 2/17/17 Can't click playing button when return to small size
    private void showTiny() {
        mIsFullscreen = false;
        showSystemUI();
        requestLayout();
        ((Activity) getContext()).setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        ViewGroup vp = (ViewGroup) mDecorView.findViewById(Window.ID_ANDROID_CONTENT);
        View view = LayoutInflater.from(getContext()).inflate(rootId, null);
        vp.addView(view);
    }

    private void showFullscreen() {
        mIsFullscreen = true;
        hideSystemUI();
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        setLayoutParams(params);
        ((Activity) getContext()).setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        ((ViewGroup) getParent()).removeView(this);
        ViewGroup vp = (ViewGroup) mDecorView.findViewById(Window.ID_ANDROID_CONTENT);
        vp.removeAllViews();
        vp.addView(this);
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable parcelable = super.onSaveInstanceState();
        SavedState ss = new SavedState(parcelable);
        ss.currentState = mCurrentState;
        ss.targetState = mTargetState;
        ss.currentPos = mMediaPlayer == null ? 0 : (int) mMediaPlayer.getCurrentPosition();
        ss.currentBufferPercentage = mCurrentBufferPercentage;
        LogUtil.d(TAG, "onSaveInstanceState " + ss.toString());
        return ss;
    }


    class SavedState extends BaseSavedState {
        int currentState;
        int targetState;
        int currentBufferPercentage;
        int currentPos;
        private ClassLoader mClassLoader;

        public SavedState(Parcel in) {
            super(in);
            mClassLoader = mContext.getClassLoader();
            currentState = in.readInt();
            targetState = in.readInt();
            currentPos = in.readInt();
            currentBufferPercentage = in.readInt();
        }


        @RequiresApi(api = Build.VERSION_CODES.N)
        public SavedState(Parcel source, ClassLoader loader) {
            super(source, loader);
            if (loader == null) {
                loader = getClass().getClassLoader();
            }
            mClassLoader = loader;
        }

        public SavedState(Parcelable superState) {
            super(superState);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(currentState);
            out.writeInt(targetState);
            out.writeInt(currentPos);
            out.writeInt(currentBufferPercentage);
        }

        @Override
        public String toString() {
            return "SavedState{" +
                    "currentState=" + currentState +
                    ", targetState=" + targetState +
                    ", currentBufferPercentage=" + currentBufferPercentage +
                    ", currentPos=" + currentPos +
                    '}';
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == GONE && mMediaPlayer != null && mMediaPlayer.isPlaying()) {
            mMediaPlayer.pause();
            if (mControllerView != null) {
                mControllerView.hide();
            }
        }
        if (visibility == VISIBLE && mMediaPlayer != null && !mMediaPlayer.isPlaying()) {
            mMediaPlayer.start();
            if (mControllerView != null) {
                mControllerView.show();

            }
        }
    }

    private int rootId;

    public void setRootLayoutId(int id) {
        this.rootId = id;
    }
}
