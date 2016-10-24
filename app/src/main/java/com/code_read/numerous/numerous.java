package com.code_read.numerous;

// todo: silent during lock screen
// todo: icons
// todo: cleanup upon shutdown to invoke GC
// todo: randomize the animationDrawable frames
// todo: randomize View animation sequences (zoom, rotate, axes, etc.)
// todo: startup and shutdown sequences (cf. orange app)
// todo: green fadeout darkens on small Samsung tablet
// todo: use "choreographer" method to conduct and coordinate all animations
// todo: try alternating a duplicate of lowerMask *below* numbersView
// todo: rotate and scale numbers at same time?
// todo: zoom numbers all the way out, then part way in, then rotate (can't rotate zoomed out or corners show)

import android.animation.Animator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;

//import static java.lang.Math.round;

public class numerous extends Activity {

    // For GPU tracing, per http://tools.android.com/tech-docs/gpu-profiler:
/*    static {
        System.loadLibrary("gapii");
    }*/

    final boolean backPressed    = false;
    final boolean rotatingColors = false;
    final boolean updateNumbers  = true;

    Thread track1, track2, track3, numberSpeed;
    final int soundRes = 48000;
    boolean isRunning;
    final int kickoffDelay = 5000;
    ScheduledExecutorService scheduleTaskExecutor;
    ScheduledExecutorService scheduleTaskExecutor2;
    ImageView overlay, topOverlay, numbersView, shaderView, rainbowBG, lowerMask;
    final Random myRandom = new Random();
    final ImageView[] imageViews = new ImageView[4];
//    int frameWidth, frameHeight;
    int displayWidth, displayHeight, displayShortSide;
    View outerFrame, innerFrame, pictureFrame;
    FrameLayout innerFrame2;
    RelativeLayout animationLayout;
    final int textColor = 0;
    final int[] textColors = new int[64];
    final int lastColor = textColors.length - 1;
    SoundPool numerouSounds;
    int doorOpenID, recycleID;
    int spStream;       // Currently playing sound effect
    final double twopi = 8. * Math.atan(1.);
//    MyAnimationDrawable animatedNumbers;
    AnimationDrawable animatedNumbers;
    final int numZoomCount = 0;
    float aumX, aumY, umhX, umhY;
    final int lmBaseScale = 16; // base scale for lower mask
//    final int lmBaseScale = 4; // base scale for lower mask
    final int aumZoomTo = lmBaseScale;
    int rotNumsBy;
    int rotateColorsBy;
//    int rotateFrameBy = 180;
//    int rotateFrameBy = 45;
//    int rotateFrameBy = 90;
    final int rotateFrameBy = 20;
    int pivotFrameBy, slideFrameBy;
    int ifSlideFactor;
    final boolean frameIsRotating = false;
    int lmXtoLeft;
    final int globalShrinkToPercent = 100 ;  // Divide size of ALL views to this for "birdsEye" overview
    int ifDirection;  // For reversing innerFrame animations
    final boolean birdsEye = false;

    enum specialEffects {
        rotateEffect, rotateZoomEffect, slideEffect
        {
            @Override
            specialEffects next() {
                return values()[0]; } // rollover to the first
        };
        specialEffects next() {
            // No bounds checking required here, because the last instance overrides
            return values()[ordinal() + 1];
        }
    }

    specialEffects specialEffect = specialEffects.rotateEffect;

    int buffsize = AudioTrack.getMinBufferSize(soundRes,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);

    AudioTrack audioTrack3;
    Typeface VGAFixFont;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE); //crw - no title under Kitkat
        setContentView(R.layout.numerous);
        outerFrame = findViewById(R.id.outerFrame);
        innerFrame   = findViewById(R.id.innerFrame);
        rainbowBG    = (ImageView) findViewById(R.id.rainbowBG);
        lowerMask    = (ImageView) findViewById(R.id.lowerMask);
        shaderView   = (ImageView) findViewById(R.id.shaderView);
        numbersView  = (ImageView) findViewById(R.id.numbersView);
        pictureFrame = findViewById(R.id.pictureFrame);

        //        tileFrame.setLayerType(View.LAYER_TYPE_HARDWARE, null); // no change

        shaderView.setBackgroundColor(Color.WHITE);

        // The most "numeric" of fonts: the kind computers like to read :-)
        VGAFixFont = Typeface.createFromAsset(getAssets(), "fonts/OCRAEXT.TTF");

        double frequency = (2 * Math.PI) / textColors.length; // 1 rainbow cycle per array length
        int redVal, greenVal, blueVal;

        // Prebuild a rainbow array of colors, adapted from http://krazydad.com/tutorials/makecolors.php:
        for (int i = 0; i <= lastColor; ++i) {
            redVal   = (int) Math.round(Math.sin(frequency * i + 0) * 127 + 128);
            greenVal = (int) Math.round(Math.sin(frequency * i + 2) * 127 + 128);
            blueVal  = (int) Math.round(Math.sin(frequency * i + 4) * 127 + 128);
            textColors[i] = blueVal + (greenVal << 8) + (redVal << 16) + 0xff000000; // alpha
        }

        // Setup for SoundPool.play (sound effects)
        numerouSounds = new SoundPool(1, AudioManager.STREAM_SYSTEM, 0);
//        soundIds = new int[10];
        // Note: on some platforms, latency dictates that sounds needed early should be loaded first:
        doorOpenID = numerouSounds.load(this, R.raw.doorcreak1, 1);
        recycleID  = numerouSounds.load(this, R.raw.recycle2, 1);

        // Discover screen dimensions:
        if (android.os.Build.VERSION.SDK_INT >= 13) {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            Display display  = wm.getDefaultDisplay();
            Point size       = new Point();
            display.getSize(size);
            displayWidth     = size.x;
            displayHeight    = size.y;
        } else {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            Display display  = wm.getDefaultDisplay();
            displayWidth     = display.getWidth();  // deprecated
            displayHeight    = display.getHeight();  // deprecated
        }

        displayShortSide = (displayWidth <= displayHeight) ? displayWidth : displayHeight;

        ViewGroup.LayoutParams rbParams = rainbowBG.getLayoutParams();
        rbParams.height = displayShortSide;
        rbParams.width  = displayShortSide;
        rainbowBG.setLayoutParams(rbParams);

//        outerFrame.setBackgroundColor(Color.BLACK);
        outerFrame.setBackgroundColor(Color.GRAY);

//        innerFrame.setPivotX(displayWidth * .8f);
//        innerFrame.setPivotY(displayHeight / 2);

//        lowerMask.setMaxHeight(displayHeight * lmBaseScale);
//        lowerMask.setMaxWidth( displayWidth  * lmBaseScale);

        // lowerMask is large numbers through which the lower layers may be viewed
        lowerMask.setBackground(getDrawableNumstring(0, Color.WHITE, 1));

//        lowerMask.setTranslationX(0);
//        lowerMask.setTranslationY(0);
//        lowerMask.setX(-(displayWidth * lmBaseScale) + displayWidth); // all the way to "left" as test
//        lowerMask.setX(displayWidth * lmBaseScale); // all the way to "left" as test
//        lowerMask.setX(-(displayWidth * lmBaseScale) / 2); // sets right edge to screen center

        // Prepare for innerFrame rotation:
//        lowerMask.setX((-(displayWidth * lmBaseScale) / 2) + (displayWidth / 2)); // sets right edge to screen right edge

//        Bitmap gradientBitmap = Bitmap.createBitmap(displayWidth, displayHeight, Bitmap.Config.RGB_565);
//        Bitmap gradientBitmap = Bitmap.createBitmap(displayWidth / 4, displayHeight / 4, Bitmap.Config.RGB_565);
//        Bitmap gradientBitmap = Bitmap.createBitmap(displayWidth / 8, displayHeight / 8, Bitmap.Config.RGB_565);
        Bitmap gradientBitmap = Bitmap.createBitmap(displayShortSide, displayShortSide, Bitmap.Config.RGB_565);
        Canvas gradientCanvas = new Canvas(gradientBitmap);
        Paint gradientPaint = new Paint();

        gradientPaint.setShader(new LinearGradient(0, 0, 0, displayShortSide,
                new int[] {
                        Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE, 0xFFA100FF,
                        Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE, 0xFFA100FF,
                        Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE, 0xFFA100FF,
                        Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE, 0xFFA100FF,
                        Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE, 0xFFA100FF,
                        Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE, 0xFFA100FF,
                        Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE, 0xFFA100FF },
                null,
                Shader.TileMode.MIRROR));

        gradientCanvas.setBitmap(gradientBitmap);
        gradientCanvas.drawPaint(gradientPaint);
        rainbowBG.setBackground(new BitmapDrawable(getResources(), gradientBitmap));

        // Create a frame to mask lower layers, to try and avoid their disappearance when rotating them:
        Bitmap frameBitmap = Bitmap.createBitmap(displayWidth, displayHeight, Bitmap.Config.ALPHA_8);
        Canvas frameCanvas = new Canvas(frameBitmap);
        Paint framePaint = new Paint();
//        framePaint.setAlpha(0);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(displayShortSide / 2);
        framePaint.setColor(Color.BLACK);
        frameCanvas.drawRect(0,0,displayWidth,displayHeight,framePaint);
        pictureFrame.setBackground(new BitmapDrawable(getResources(), frameBitmap));

        pictureFrame.setScaleX(8);
        pictureFrame.setScaleY(8);
        // from http://graphics-geek.blogspot.se/2013/02/devbytes-keyframe-animations.html:
        // Create the AnimationDrawable in which we will store all frames of the animation
//        animatedNumbers = new MyAnimationDrawable();
        animatedNumbers = new AnimationDrawable();
        for (int i = 0; i < 10; ++i) {
//            animatedNumbers.addFrame(getDrawableNumstring(i, Color.BLACK), 50);
            animatedNumbers.addFrame(getDrawableNumstring(i, Color.BLACK, 1), 75);
//            animatedNumbers.addFrame(getDrawableNumstring(i, Color.BLACK), 100);
        }

//        numbersView.setScaleX(1.9f); // kludge to avoid top/bottom margin problems on some Androids
//        numbersView.setScaleY(1.9f); // kludge to avoid top/bottom margin problems on some Androids

        numbersView.setImageDrawable(animatedNumbers);

        // This starts the frame animation of the top layer of constantly changing transparent
        // random numbers:
        animatedNumbers.setOneShot(false);        // Run until we say stop
        animatedNumbers.start(); // NOTE: we can .stop() and .start() if needed
    }

    // Build a bitmap from one of our strings of random numbers.  The numbers automatically "wrap"
    // to dimensions of the bitmap:
    private BitmapDrawable getDrawableNumstring(int frameNumber, int canvasColor, int textZoom) {
        Bitmap bitmap;

        // todo: to save memory, possibly use smaller dimensions then setScale() to "zoom" the bitmap:

        // If canvas is black, we can use a smaller bitmap:
        if (canvasColor == Color.BLACK) {
            bitmap = Bitmap.createBitmap(displayWidth, displayHeight, Bitmap.Config.ALPHA_8);
        } else {
            bitmap = Bitmap.createBitmap(displayWidth, displayHeight, Bitmap.Config.ARGB_8888);
        }

        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(canvasColor);

        TextPaint mTextPaint = new TextPaint();
        mTextPaint.setTextSize((displayShortSide / 35) * textZoom);
        mTextPaint.setTypeface(VGAFixFont);

        mTextPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR)); // necessary else drawColor remains
        mTextPaint.setARGB(0,0,0,0);    // clear font so we can change color with layer below
        mTextPaint.setAntiAlias(true); // Improves font appearance on SM-T330NU

        StaticLayout mTextLayout = new StaticLayout(numstrings.numStrings[frameNumber], mTextPaint,
                canvas.getWidth(), Layout.Alignment.ALIGN_NORMAL,
                0.8f, 0.0f, false); // 0.8f = text interline multiplier

        mTextLayout.draw(canvas);

        return new BitmapDrawable(getResources(), bitmap);
    }

    //<editor-fold desc="Description">
/*    // After http://stackoverflow.com/a/5607129/5025060:
    public class MyAnimationDrawable extends AnimationDrawable {
        private volatile int duration; //its volatile because another thread will update its value
        private int currentFrame;

        public MyAnimationDrawable() {
            currentFrame = 0;
        }

        @Override
        public void run() {
            int n = getNumberOfFrames();
            currentFrame++;
            if (currentFrame >= n) {
                currentFrame = 0;
            }
            selectDrawable(currentFrame);
            scheduleSelf(this, SystemClock.uptimeMillis() + duration);
        }

        public void setDuration(int duration) {
            this.duration = duration;
            //we have to do the following or the next frame will be displayed after the old duration
            unscheduleSelf(this);
            selectDrawable(currentFrame);
            scheduleSelf(this, SystemClock.uptimeMillis()+duration);
        }
    }*/
    //</editor-fold>

    @Override
    public void onResume() {
        super.onResume();
        ifDirection = -1;
        ifSlideFactor = 1;
        aumX = 0;
        aumY = 0;
        umhX = 0;
        umhY = 0;
        rotNumsBy = 1890;
        rotateColorsBy = 960;

        // Hide title and control bars:
        Window w = getWindow();
        w.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                   WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        outerFrame.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_FULLSCREEN); // hide status bar

        outerFrame.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            @Override
            public void onSystemUiVisibilityChange(int visibility) {
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    outerFrame.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                          | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                          | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                          | View.SYSTEM_UI_FLAG_FULLSCREEN); // hide status bar
                }
            }
        });

        // Launch synthesized sound loop:
        if (!isRunning) {
            isRunning = true;
            final Random lRandom = new Random();

            /*
            Derived in part from:
            https://audioprograming.wordpress.com/2012/10/18/a-simple-synth-in-android-step-by-step-guide-using-the-java-sdk

            ph is the phase index, which tells the sin() function what value to produce.
              The code synthesizes a series of samples of a sine wave at freq Hz.
              soundRes is the number of samples per second that are to be produced
              (so that freq can be given in cycles per second), ph is incremented proportionally
              to the desired frequency. If the frequency was 1 Hz, then you’d see that ph
              increments by twopi every SoundRes samples.*/

            audioTrack3 = new AudioTrack(AudioManager.STREAM_MUSIC,
                    soundRes, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    buffsize * 2, // in *bytes*
                    AudioTrack.MODE_STREAM);

            track3 = new Thread() {
                public void run() {
//                    setPriority(Thread.MAX_PRIORITY);
/*
                    int buffsize = AudioTrack.getMinBufferSize(soundRes,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
*/
/*                    AudioTrack audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                            soundRes, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            buffsize * 2, // in *bytes*
                            AudioTrack.MODE_STREAM);*/
                    short samples[] = new short[buffsize];
                    int subSampSize = buffsize / 16;    // Switch frequency at even buffer increments
                                                        // ..to avoid noise
                    int ampl;
                    double freq, ph = 0.0;
                    audioTrack3.play();
                    while (isRunning) {
                        freq = lRandom.nextInt(2048) + 512; // next frequency
//                        ampl = (int) (680 * Math.sin(ph / 4096)) + 1000;  // next sine based amplitude
                        ampl = (int) (1000 * Math.sin(ph / 4096)) + 680;  // next sine based amplitude
                        double increment = (twopi * freq) / soundRes;   // how much to increase ph by
                        for (int i = 0; i < buffsize; i++) {
                            samples[i] = (short) (ampl * Math.sin(ph));
                            if (i % subSampSize == 0) {
                                freq = lRandom.nextInt(2048) + 512;
                                ampl = (int) (680 * Math.sin(ph / 4096)) + 1000;
                                increment = (twopi * freq) / soundRes;
                            }
                            ph += increment;
                        }
                        audioTrack3.write(samples, 0, buffsize);
                        // have to run this on UI thread:
//                        animatedNumbers.setDuration(ampl / 22);
                    }
                    audioTrack3.stop();
                    audioTrack3.release();
                }
            };
            track3.start();
        }


/*
        new Handler().postDelayed(new Runnable() {
            public void run() {
                runOnUiThread(new Runnable() {
                    public void run() {
                        zoomNumbersIn();
                    }
                });
            }
        }, 4000);
*/

/*        // Shrink the entire View group for diagnostic purposes:
        float globalShrinkFactor = globalShrinkToPercent / 100;
        outerFrame.setScaleX(outerFrame.getScaleX() * globalShrinkFactor);
        outerFrame.setScaleY(outerFrame.getScaleY() * globalShrinkFactor);
        innerFrame  .setScaleX(innerFrame  .getScaleX() * globalShrinkFactor);
        innerFrame  .setScaleY(innerFrame  .getScaleY() * globalShrinkFactor);
        rainbowBG   .setScaleX(rainbowBG   .getScaleX() * globalShrinkFactor);
        rainbowBG   .setScaleY(rainbowBG   .getScaleY() * globalShrinkFactor);
        lowerMask   .setScaleX(lowerMask   .getScaleX() * globalShrinkFactor);
        lowerMask   .setScaleY(lowerMask   .getScaleY() * globalShrinkFactor);
        shaderView  .setScaleX(shaderView  .getScaleX() * globalShrinkFactor);
        shaderView  .setScaleY(shaderView  .getScaleY() * globalShrinkFactor);
        numbersView .setScaleX(numbersView .getScaleX() * globalShrinkFactor);
        numbersView .setScaleY(numbersView .getScaleY() * globalShrinkFactor);*/

//        innerFrame.setScaleX(.7f * innerFrame.getScaleX());
//        innerFrame.setScaleY(.7f * innerFrame.getScaleY());

        if (birdsEye) { // Shrink the views to provide elevated overview
            lowerMask.setScaleX(lmBaseScale * .01f);
            lowerMask.setScaleY(lmBaseScale * .01f);
            rainbowBG.setScaleX(1);
            rainbowBG.setScaleY(1);
            numbersView.setScaleX(.7f);
            numbersView.setScaleY(.7f);
            innerFrame.setPivotX(displayWidth);  // Pivot at right edge of display
            innerFrame.setPivotY(displayHeight / 2);
        } else {
            pivotFrameBy =   displayWidth / 2;
            slideFrameBy = -(displayWidth / 2);
            lowerMask.setScaleX(lmBaseScale * .2f);
            lowerMask.setScaleY(lmBaseScale * .2f);
            lmXtoLeft = (-(displayWidth * lmBaseScale) / 2) + (displayWidth / 2);
            rainbowBG.setScaleX(4);
            rainbowBG.setScaleY(4);
            innerFrame.setPivotX(displayWidth);  // Pivot at right edge of display
            innerFrame.setPivotY(displayHeight / 2);
        }

        // We launch this in advance of other View animations.  Its long StartDelay
        // ..gives the appearance of coordination with other animations:
        revealColoration();
        rotateFrame();
    }

    // N.B.: the only way I've discovered (short of NDK) of reliably choreographing Android views
    // ..is via callbacks.  So we exploit callbacks to create a continous sequence by having
    // ..the following methods call one another from *.onAnimationEnd():

    public void revealColoration() {
        // Slowly reveal number coloration:
//        shaderView.animate().alpha(0).setDuration(8000).setStartDelay(20000);
        shaderView.animate().alpha(0).setDuration(80).setStartDelay(20)
                .withLayer();
        shaderView.animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) {
                rotateColors();
                animateLowerMask(); // janky, even w/o audio or other animations,
                                    // so we show it below nums.
            }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });
    }

    public void zoomNumbersIn() {
//            numbersView.animate().scaleX(8f).scaleY(8f).setDuration(3400);
            numbersView.animate().scaleX(1f).scaleY(1f).setDuration(3400)
                    .withLayer();
        numbersView.animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) { zoomNumbersOut(); }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });
    }

    public void zoomNumbersOut() {
            numbersView.animate().scaleX(1.9f).scaleY(1.9f).setDuration(3400)
                    .withLayer();
//            numbersView.animate().scaleX(1f).scaleY(1f).setDuration(3400);
        numbersView.animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) { rotateNumbers(); }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });
    }

    public void rotateNumbers() {
            numbersView.animate().rotationBy(rotNumsBy).setDuration(3400)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withLayer();;
        rotNumsBy *= -1; // alternate cw/ccw
        numbersView.animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) { zoomNumbersIn(); }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });
    }

    public void rotateColors() {
            rainbowBG.animate().rotationBy(rotateColorsBy).setDuration(4000)
                    .setInterpolator(new AccelerateInterpolator()).withLayer();
        rotateColorsBy *= -1; // alternate cw/ccw
        rainbowBG.animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) { rotateColors(); }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });
    }

    public void animateLowerMask() {
//        if (frameIsRotating) return;
        // Where to slide to: random coordinates between -5 and +5 times displayShortSide:
        umhX = aumX;
        umhY = aumY;
        aumX = myRandom.nextInt(10) - 5;
        aumY = myRandom.nextInt(10) - 5;
        // Calculate distance in order to maintain uniform velocity:
        double xDist    = aumX - umhX;
        double yDist    = aumY - umhY;
        double moveDist = Math.hypot(xDist, yDist);
        long   moveDur  = Math.round(moveDist * 150);
/*        Log.e("mDur", moveDur + " mDist " + moveDist
                + "\nX: " + umhX + " to " + aumX
                + "\nY: " + umhY + " to " + aumY
                + "\nxD: " + xDist + " yD: " + yDist);*/

        lowerMask.animate()
//                .setStartDelay(3000) // debug
//                    .x(displayShortSide * aumX)
//                    .y(displayShortSide * aumY)
//                    .translationX(displayShortSide * aumX)
//                    .translationY(displayShortSide * aumY)
                .x(100 * aumX) // debug
                .y(100 * aumY) // debug
                .setDuration(moveDur)
                .setInterpolator(new LinearInterpolator());  // This either was not the default on
                                                            // KK Samsung, or our other setInterpolator
                                                            // calls caused default to change.
//                .withLayer();

        lowerMask.animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) { animateLowerMask(); }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });
    }

    // Apparently if we rotate around Y and are too wide, we exceed upper Z limit.  So try
    // ..sliding (x) left while moving x pivot to right so that our (z) "close" edge doesn't reach far
    // .."above" the display:
    public void rotateFrame() {
//        pivotFrameBy  *= -1;
//        slideFrameBy  *= -1;

//        numbersView.setVisibility(View.INVISIBLE);
//        rainbowBG.setVisibility(View.INVISIBLE);
/*
        lowerMask.animate().rotationYBy(rotateFrameBy).setDuration(20000);
        lowerMask.animate().setListener(new Animator.AnimatorListener() {
*/
        // Animating innerFrame does not clip children, animating outerFrame does (bc it's outmost?)
//        innerFrame.animate().rotationYBy(rotateFrameBy).xBy(slideFrameBy)
//                .setDuration(20000).setListener(new Animator.AnimatorListener() {

//        lowerMask.setX((-(displayWidth * lmBaseScale) / 2) + (displayWidth / 2));

//        frameIsRotating = true;  // Prevent other lowerMask animations

//        lowerMask.animate().x(lmXtoLeft).setDuration(3000).start();

        // todo: zoom and rotate at same time (to keep screen filled):
        // todo: and/or "slide" entire innerFrame to left (w/rotate in) and back to right (w/rotate out)
        innerFrame.animate().rotationYBy(rotateFrameBy * ifDirection)
//                .scaleX(1.9f * ifDirection * -1).scaleY(1.9f * ifDirection * -1)
                .x(slideFrameBy * ifSlideFactor)
                .setDuration(2000)
                .setStartDelay(720)
//                .setInterpolator(new LinearInterpolator())  // linear is used by default
                .setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) {
//                numbersView.setVisibility(View.VISIBLE);
//                rainbowBG.setVisibility(View.VISIBLE);
//                frameIsRotating = false;
//                animateLowerMask();
                new Handler().postDelayed(new Runnable() {
                    public void run() {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                rotateFrame();
                            }
                        });
                    }
                }, 1000);
            }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });
        ifDirection *= -1;  // reverse each time
        ifSlideFactor = (ifSlideFactor == 1) ? 0 : 1; // toggle between 1 and 0
    }

    //<editor-fold desc="Description">
/*    public void rotateFrame() {
        rotateFrameBy *= -1;  // reverse each time
//        outerFrame.setPivotX(0);
//        outerFrame.setPivotY(displayHeight / 2);
//        outerFrame.setPivotX(displayWidth / 2);
//        outerFrame.animate().rotationYBy(rotateFrameBy).setDuration(2000).setStartDelay(2000);
//        outerFrame.animate().scaleYBy(rotateFrameBy).setDuration(2000).setStartDelay(2000);
*//*        outerFrame.animate().translationXBy(rotateFrameBy).scaleXBy(rotateFrameBy * .04f)
                .scaleYBy(rotateFrameBy * .04f)
                .setDuration(7000).setStartDelay(2000);*//*
        lowerMask.animate().rotationYBy(rotateFrameBy).setDuration(20000);
        rainbowBG.animate().rotationYBy(rotateFrameBy).setDuration(20000);
        numbersView.animate().rotationYBy(rotateFrameBy).setDuration(20000);
        shaderView.animate().rotationYBy(rotateFrameBy).setDuration(20000);

        shaderView.animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) {
                new Handler().postDelayed(new Runnable() {
                    public void run() {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                rotateFrame();
                            }
                        });
                    }
                }, 6000);

//                rotateFrame();
            }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });
    }*/


/*    public void animateLowerMask() {
        // Calculate distance in order to set same speed for each move:
        aumX = myRandom.nextInt(10) - 5;
        aumY = myRandom.nextInt(10) - 5;
        int xDist = aumX - umhX;
        int yDist = aumY - umhY;
        int moveDist = (int) Math.hypot(xDist, yDist);
//        int moveDur = moveDist * 2000;
        int moveDur = moveDist * 1500;
        lowerMask.animate().scaleX(aumZoomTo).scaleY(aumZoomTo).x(displayShortSide * aumX)
                .y(displayShortSide * aumY).setDuration(moveDur).withLayer();
        lowerMask.animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) { upperMaskHelper(); }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });
    }

    public void upperMaskHelper() {
        umhX = myRandom.nextInt(10) - 5;
        umhY = myRandom.nextInt(10) - 5;
        int xDist = umhX - aumX;
        int yDist = umhY - aumY;
        int moveDist = (int) Math.hypot(xDist, yDist);
//        int moveDur = moveDist * 2000;
        int moveDur = moveDist * 1500;
        lowerMask.animate().scaleX(lmBaseScale).scaleY(lmBaseScale).x(displayShortSide * umhX)
                .y(displayShortSide * umhY).setDuration(moveDur).withLayer();
            lowerMask.animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) { animateLowerMask(); }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });
    }*/

/*    // Same "jank" as animateLowerMask().  Repeats but doesn't change direction each time:
    public void animateUpperMask2() {
        aumX = myRandom.nextInt(8) - 4;
        aumY = myRandom.nextInt(8) - 4;
        int xDist = aumX - umhX;
        int yDist = aumY - umhY;
        int moveDist = (int) Math.hypot(xDist, yDist);
        int moveDur = moveDist * 1500;
        ObjectAnimator xMove = ObjectAnimator.ofFloat(lowerMask, "X", displayShortSide * aumX);
        ObjectAnimator yMove = ObjectAnimator.ofFloat(lowerMask, "Y", displayShortSide * aumY);
        xMove.setRepeatCount(ObjectAnimator.INFINITE);
        yMove.setRepeatCount(ObjectAnimator.INFINITE);
        xMove.setRepeatMode(ObjectAnimator.REVERSE);
        yMove.setRepeatMode(ObjectAnimator.REVERSE);
        AnimatorSet animSetXY = new AnimatorSet();
        animSetXY.playTogether(xMove, yMove);
        animSetXY.setDuration(moveDur);
        animSetXY.start();
    }

    public void openDoor() {
        numbersView.setPivotX(0);
        numbersView.setPivotY(displayHeight / 2);
        numbersView.animate().rotationYBy(45).setDuration(3400);
        numbersView.animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) {
                closeDoor(); }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });
    }

    public void closeDoor() {
        numbersView.animate().rotationYBy(-45).setDuration(3400);
        numbersView.animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) {
                zoomNumbersIn(); }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });
    }

    public void bgAnimation1() {
        rainbowBG.animate().scaleX(4f).scaleY(4f).setDuration(9000);
    }

    public void bgAnimation2() {
        rainbowBG.animate().scaleX(4f).scaleY(4f).setDuration(9000);
    }

    public void animation3() {
        outerFrame.setPivotX(400);
        outerFrame.setPivotY(400);
//        tileFrame.animate().rotationXBy(10).rotationXBy(20).setDuration(3400);
        outerFrame.animate().scaleX(0.5f).scaleY(0.5f).rotation(360).setDuration(3400);
        outerFrame.animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) { zoomNumbersIn(); }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });
    }*/

/*    public void openDoor() {
        Log.w("openDoor", "start");
        if (!isRunning) return;

*//*        // Swap "doors" in order to make refreshed door visible:
        visibleDoor   = (visibleDoor == doorView2) ? doorView1 : doorView2;
        invisibleDoor = (visibleDoor == doorView2) ? doorView1 : doorView2;
        // Prepare to open "door" by cloning current view's appearance:
        if (rotatingColors) visibleDoor.setTextColor(textColors[textColor]);

        //todo: clone "door" to current zoom/rotation, etc of innerFrame

        visibleDoor.setVisibility(View.VISIBLE);

        // Rotate visibleDoor to simulate opening door:
        visibleDoor.setPivotX(0);
        visibleDoor.setPivotY(300); // Should be 1/2 screen height (but how to calculate?)
        visibleDoor.animate().rotationYBy(90).setInterpolator(new DecelerateInterpolator()).setDuration(3400);
        visibleDoor.animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) { doorIsOpen(); }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });*//*
//        overlay.setVisibility(View.VISIBLE);
    }*/

/*    public void doorIsOpen() {
//        visibleDoor.setVisibility(View.INVISIBLE);
        Log.w("doorIsOpen", "start");
        if (!isRunning) return;

        audioTrack3.play();
        overlay.setVisibility(View.INVISIBLE);
//        topOverlay.setAlpha(0);
        topOverlay.animate().alpha(0).setDuration(3);
        updateNumbers = true;
        // Invoke SE on background FrameView:

        switch (specialEffect) {
            case rotateEffect:
                specialEffect = specialEffect.next();
//                invisibleDoor.animate().scaleX(1).scaleY(1).rotation(0);
//                invisibleDoor.animate().rotation(3000).setDuration(5000).setInterpolator(new AccelerateInterpolator());
//                innerFrame.animate().scaleX(1).scaleY(1).rotation(0);
//                innerFrame.animate().rotation(1800).setDuration(3400).setInterpolator(new AccelerateInterpolator());
                innerFrame.animate().rotation(1800).setDuration(3400);
                innerFrame.animate().setListener(new Animator.AnimatorListener() {
                    public void onAnimationStart(Animator animator) {}
                    public void onAnimationEnd(Animator animator) { resetView(innerFrame); }
                    public void onAnimationCancel(Animator animator) {}
                    public void onAnimationRepeat(Animator animator) {}
                });
                break;

            case rotateZoomEffect:
                specialEffect = specialEffect.next();

//                invisibleDoor.animate().scaleX(8).scaleY(8).rotation(-500).setDuration(3400);
//                invisibleDoor.animate().scaleX(1).scaleY(1).rotation(0);
//                innerFrame.animate().scaleX(1).scaleY(1).rotation(0);
                innerFrame.animate().scaleX(8).scaleY(8).rotation(-500).setDuration(3400);
                innerFrame.animate().setListener(new Animator.AnimatorListener() {
                    public void onAnimationStart(Animator animator) {}
                    public void onAnimationEnd(Animator animator) { resetView(innerFrame); }
                    public void onAnimationCancel(Animator animator) {}
                    public void onAnimationRepeat(Animator animator) {}
                });
                break;

            case slideEffect:
                specialEffect = specialEffect.next();
                innerFrame.animate().x(500).y(500).setDuration(3400);
                innerFrame.animate().setListener(new Animator.AnimatorListener() {
                    public void onAnimationStart(Animator animator) {}
                    public void onAnimationEnd(Animator animator) { resetView(innerFrame); }
                    public void onAnimationCancel(Animator animator) {}
                    public void onAnimationRepeat(Animator animator) {}
                });

                innerFrame2.setLeft(-400);
                innerFrame2.animate().x(500).y(500).setDuration(3400);
                innerFrame2.animate().setListener(new Animator.AnimatorListener() {
                    public void onAnimationStart(Animator animator) {}
                    public void onAnimationEnd(Animator animator) { resetView(innerFrame); }
                    public void onAnimationCancel(Animator animator) {}
                    public void onAnimationRepeat(Animator animator) {}
                });
                break;

            default:
                specialEffect = specialEffect.next();
                resetView(innerFrame);
                break;
        }*/

        // Reset "door" for next segue:
//        visibleDoor.setRotationY(0);
/*        new Handler().postDelayed(new Runnable() {
            public void run() {
                runOnUiThread(new Runnable() {
                    public void run() {
//                        invisibleDoor.animate().scaleX(1).scaleY(1).rotation(0);
                        innerFrame.animate().scaleX(1).scaleY(1).rotation(0).setDuration(3400);
                        innerFrame.animate().setListener(new Animator.AnimatorListener() {
                            public void onAnimationStart(Animator animator) {}
                            public void onAnimationEnd(Animator animator) { openDoor(); }
                            public void onAnimationCancel(Animator animator) {}
                            public void onAnimationRepeat(Animator animator) {}
                        });
                    }
                });
            }
        }, kickoffDelay);
    }*/

/*
    public void resetView(View viewToReset) {
        Log.w("resetView", "start");
        if (!isRunning) return;

        updateNumbers = false;
        audioTrack3.pause();
        overlay.setVisibility(View.VISIBLE);
//        overlay.invalidate();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        topOverlay.animate().alpha(1).setDuration(1);

        numerouSounds.stop(spStream);                               // Stop any SE currently playing
//        spStream = numerouSounds.play(doorOpenID, 1, 1, 1, 0, 1);   // Play door open SE
        spStream = numerouSounds.play(recycleID, 1, 1, 1, 0, 1);

//        overlay.setAlpha(1);
//        overlay.animate().alpha(1).setDuration(2000);

//        viewToReset.animate().scaleX(1).scaleY(1).rotation(0).setDuration(3400);
        viewToReset.animate().x(0).y(0).scaleX(1).scaleY(1).rotation(0).setDuration(1);
        viewToReset.animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) {}
            public void onAnimationEnd(Animator animator) {
//                overlay.setVisibility(View.VISIBLE);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                doorIsOpen(); }
            public void onAnimationCancel(Animator animator) {}
            public void onAnimationRepeat(Animator animator) {}
        });
    }
*/

/*    public void animateIn () {
        if (!isRunning) return;
        for (int viewNum = 0; viewNum < lastView; viewNum++) {
            textViews[viewNum].animate().scaleX(.5f).scaleY(.5f).setDuration(3000);
            textViews[viewNum].animate().rotationYBy(60).setInterpolator(new AccelerateDecelerateInterpolator()).setDuration(3000);
        }
        textViews[lastView].animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) { animateOut(); }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });
    }

    public void animateOut () {
        if (!isRunning) return;
        for (int viewNum = 0; viewNum < lastView; viewNum++) {
            textViews[viewNum].animate().scaleX(1.0f).scaleY(1.0f).setDuration(3000);
            textViews[viewNum].animate().rotationYBy(-60).setInterpolator(new AccelerateDecelerateInterpolator()).setDuration(3000);
        }
        // Attach setListener() to animation of the last item in textViews[]:
        textViews[lastView].animate().scaleX(1.0f).scaleY(1.0f).setDuration(3000);
        textViews[lastView].animate().rotationYBy(-60).setInterpolator(new AccelerateDecelerateInterpolator()).setDuration(3000);
        textViews[lastView].animate().setListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator animator) { }
            public void onAnimationEnd(Animator animator) { animateIn(); }
            public void onAnimationCancel(Animator animator) { }
            public void onAnimationRepeat(Animator animator) { }
        });
    }*/
    //</editor-fold>

    @Override
    public void onPause() { // suspend until resume or exit
        isRunning = false;
        numerouSounds.stop(spStream); // Stop any sound currently playing
        shaderView.animate().cancel();
        lowerMask.animate().cancel();
        rainbowBG.animate().cancel();
        shaderView.animate().cancel();
        numbersView.animate().cancel();
        super.onPause();
    }

/*    @Override
    public void onBackPressed() {
//        isRunning = false;  // stop synth sound ASAP
//        numerouSounds.stop(spStream); // Stop any sound currently playing
        finish();
        super.onBackPressed();
    }*/

    @Override
    protected void onDestroy() {
        numerouSounds.stop(spStream); // Stop any sound currently playing
//        scheduleTaskExecutor.shutdown();
        isRunning = false;
        super.onDestroy();
    }
}
