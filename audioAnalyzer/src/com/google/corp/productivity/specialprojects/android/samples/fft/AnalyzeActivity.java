
package com.google.corp.productivity.specialprojects.android.samples.fft;


import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.TextView;

public class AnalyzeActivity extends Activity {
  static final String TAG="AnalyzeActivity";
  static float DPRatio;

  private Looper samplingThread;

  private final static int RECORDER_AGC_OFF = MediaRecorder.AudioSource.VOICE_RECOGNITION;
  private final static int BYTE_OF_SAMPLE = 2;
  
  private static int fftLen = 512;
  private static int sampleRate = 16000;
  private static int nFFTAverage = 2;
  private static String wndFuncName;

  private static int audioSourceId = RECORDER_AGC_OFF;
  private boolean isAWeighting = false;
  
  float listItemTextSize = 20;        // see R.dimen.button_text_fontsize
  float listItemTitleTextSize = 12;   // see R.dimen.button_text_small_fontsize
  
  Object oblock = new Object();

  double maxAmpFreq;

  StringBuilder textCur = new StringBuilder("");  // for textCurChar
  StringBuilder textRMS  = new StringBuilder("");
  StringBuilder textPeak = new StringBuilder("");
  StringBuilder textRec = new StringBuilder("");  // for textCurChar
  char[] textPeakChar;

  PopupWindow popupMenuSampleRate;
  PopupWindow popupMenuFFTLen;
  PopupWindow popupMenuAverage;
  
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main);
    
    DPRatio = getResources().getDisplayMetrics().density;
    
    final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
    Log.i(TAG, " max mem = " + maxMemory + "k");
    
    // set and get preferences in PreferenceActivity
    PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
    // Set variable according to the preferences
    //updatePreferenceSaved();

    textPeakChar = new char[getString(R.string.textview_peak_text).length()];

    Resources res = getResources();
    getAudioSourceNameFromIdPrepare(res);

    listItemTextSize      = res.getDimension(R.dimen.button_text_fontsize);
    listItemTitleTextSize = res.getDimension(R.dimen.button_text_small_fontsize);
    
    setTextViewFontSize();
  }

  // Set text font size of textview_cur and textview_peak according to space left
  @SuppressWarnings("deprecation")
  private void setTextViewFontSize() {
    TextView tv = (TextView) findViewById(R.id.textview_peak);

    Paint mTestPaint = new Paint();
    mTestPaint.setTextSize(tv.getTextSize());
    mTestPaint.setTypeface(Typeface.MONOSPACE);
    
    final String text = "Peak:";

    Display display = getWindowManager().getDefaultDisplay();
    
    // pixels left
    float px = display.getWidth() - getResources().getDimension(R.dimen.textview_RMS_layout_width) - 5;
    
    float fs = tv.getTextSize();  // size in pixel
    while (mTestPaint.measureText(text) > px && fs > 5) {
      fs -= 0.5;
      mTestPaint.setTextSize(fs);
    }
    ((TextView) findViewById(R.id.textview_peak)).setTextSize(fs / DPRatio);
  }
  
  @Override
  protected void onResume() {
    super.onResume();
    
    // load preferences
    SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
    boolean keepScreenOn = sharedPref.getBoolean("keepScreenOn", true);
    if (keepScreenOn) {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    } else {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    
    audioSourceId = Integer.parseInt(sharedPref.getString("audioSource", Integer.toString(RECORDER_AGC_OFF)));
    wndFuncName = sharedPref.getString("windowFunction", "Blackman Harris");

    samplingThread = new Looper();
    samplingThread.start();
  }

  @Override
  protected void onPause() {
    super.onPause();
    samplingThread.finish();
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  @Override
  protected void onDestroy() {
//    Debug.stopMethodTracing();
    super.onDestroy();
  }

  @Override
  public void onSaveInstanceState(Bundle savedInstanceState) {
    savedInstanceState.putDouble("maxAmpFreq",  maxAmpFreq);

    super.onSaveInstanceState(savedInstanceState);
  }

  @Override
  public void onRestoreInstanceState(Bundle savedInstanceState) {
    // will be calls after the onStart()
    super.onRestoreInstanceState(savedInstanceState);

    maxAmpFreq  = savedInstanceState.getDouble("maxAmpFreq");
  }
  
  
  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
      MenuInflater inflater = getMenuInflater();
      inflater.inflate(R.menu.info, menu);
      return true;
  }
 
  static String[] audioSourceNames;
  static int[] audioSourceIDs;
  private void getAudioSourceNameFromIdPrepare(Resources res) {
    audioSourceNames   = res.getStringArray(R.array.audio_source);
    String[] sasid = res.getStringArray(R.array.audio_source_id);
    audioSourceIDs = new int[audioSourceNames.length];
    for (int i = 0; i < audioSourceNames.length; i++) {
      audioSourceIDs[i] = Integer.parseInt(sasid[i]);
    }
  }
  
  // Get audio source name from its ID
  // Tell me if there is better way to do it.
  private static String getAudioSourceNameFromId(int id) {
    for (int i = 0; i < audioSourceNames.length; i++) {
      if (audioSourceIDs[i] == id) {
        return audioSourceNames[i];
      }
    }
    Log.e(TAG, "getAudioSourceName(): no this entry.");
    return "";
  }
  
  private void refreshPeakLabel() {
	int maxPeak;
    textPeak.setLength(0);
    textPeak.append("Peak:");
    maxPeak = (int) Math.round(maxAmpFreq);
    textPeak.append(maxPeak);
    textPeak.append("Hz");
    textPeak.getChars(0, Math.min(textPeak.length(), textPeakChar.length), textPeakChar, 0);
    
    
    TextView tv1 = (TextView) findViewById(R.id.textview_peak);
    tv1.setText(textPeak);
    //tv.invalidate();
  }

  // used to detect if the data is unchanged
  double[] cmpDB;
  public void sameTest(double[] data) {
    // test
    if (cmpDB == null || cmpDB.length != data.length) {
      Log.i(TAG, "sameTest(): new");
      cmpDB = new double[data.length];
    } else {
      boolean same = true;
      for (int i=0; i<data.length; i++) {
        if (!Double.isNaN(cmpDB[i]) && !Double.isInfinite(cmpDB[i]) && cmpDB[i] != data[i]) {
          same = false;
          break;
        }
      }
      if (same) {
        Log.i(TAG, "sameTest(): same data row!!");
      }
      for (int i=0; i<data.length; i++) {
        cmpDB[i] = data[i];
      }
    }
  }
  
  long timeToUpdate = SystemClock.uptimeMillis();;
  volatile boolean isInvalidating = false;
  static final int VIEW_MASK_textview_peak = 1<<2;
  
  public void invalidateGraphView(int viewMask) {
    if (isInvalidating) {
      return ;
    }
    isInvalidating = true;
    
    long t = SystemClock.uptimeMillis();
    if (t >= timeToUpdate) {             // limit frame rate
      
      idPaddingInvalidate = false;

      // peak frequency
      if ((viewMask & VIEW_MASK_textview_peak) != 0)
        refreshPeakLabel();
    } else {
      if (idPaddingInvalidate == false) {
        idPaddingInvalidate = true;
        paddingViewMask = viewMask;
        paddingInvalidateHandler.postDelayed(paddingInvalidateRunnable, timeToUpdate - t + 1);
      } else {
        paddingViewMask |= viewMask;
      }
    }
    isInvalidating = false;
  }

  volatile boolean idPaddingInvalidate = false;
  volatile int paddingViewMask = -1;
  Handler paddingInvalidateHandler = new Handler();
  
  Runnable paddingInvalidateRunnable = new Runnable() {
    @Override
    public void run() {
      if (idPaddingInvalidate) {
        AnalyzeActivity.this.invalidateGraphView(paddingViewMask);
      }
    }
  };
  
  double[] spectrumDBcopy;   // XXX, transfers data from Looper to AnalyzeView
  
  public class Looper extends Thread {
    AudioRecord record;
    volatile boolean isRunning = true;
    volatile boolean isPaused1 = false;
    double wavSecOld = 0;      // used to reduce frame rate
    public STFT stft;   // use with care

    double[] mdata;

    private void SleepWithoutInterrupt(long millis) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    private double baseTimeMs = SystemClock.uptimeMillis();

    @Override
    public void run() {

    	SleepWithoutInterrupt(500);
      
      int minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
                                                  AudioFormat.ENCODING_PCM_16BIT);
      if (minBytes == AudioRecord.ERROR_BAD_VALUE) {
        Log.e(TAG, "Looper::run(): Invalid AudioRecord parameter.\n");
        return;
      }

      // Determine size of buffers for AudioRecord and AudioRecord::read()
      int readChunkSize    = fftLen/2;  // /2 due to overlapped analyze window
      readChunkSize        = Math.min(readChunkSize, 2048);  // read in a smaller chunk, hopefully smaller delay
      int bufferSampleSize = Math.max(minBytes / BYTE_OF_SAMPLE, fftLen/2) * 2;
      // tolerate up to about 1 sec.
      bufferSampleSize = (int)Math.ceil(1.0 * sampleRate / bufferSampleSize) * bufferSampleSize; 

      // Use the mic with AGC turned off. e.g. VOICE_RECOGNITION
      if (audioSourceId < 1000) {
        record = new AudioRecord(audioSourceId, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                                 AudioFormat.ENCODING_PCM_16BIT, BYTE_OF_SAMPLE * bufferSampleSize);
      } else {
        record = new AudioRecord(RECORDER_AGC_OFF, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                                 AudioFormat.ENCODING_PCM_16BIT, BYTE_OF_SAMPLE * bufferSampleSize);
      }
      Log.i(TAG, "Looper::Run(): Starting recorder... \n" +
        "  source          : " + (audioSourceId<1000?getAudioSourceNameFromId(audioSourceId):audioSourceId) + "\n" +
        String.format("  sample rate     : %d Hz (request %d Hz)\n", record.getSampleRate(), sampleRate) +
        String.format("  min buffer size : %d samples, %d Bytes\n", minBytes / BYTE_OF_SAMPLE, minBytes) +
        String.format("  buffer size     : %d samples, %d Bytes\n", bufferSampleSize, BYTE_OF_SAMPLE*bufferSampleSize) +
        String.format("  read chunk size : %d samples, %d Bytes\n", readChunkSize, BYTE_OF_SAMPLE*readChunkSize) +
        String.format("  FFT length      : %d\n", fftLen) +
        String.format("  nFFTAverage     : %d\n", nFFTAverage));
      sampleRate = record.getSampleRate();

      if (record.getState() == AudioRecord.STATE_UNINITIALIZED) {
        Log.e(TAG, "Looper::run(): Fail to initialize AudioRecord()");
        // If failed somehow, leave user a chance to change preference.
        return;
      }

      short[] audioSamples = new short[readChunkSize];
      int numOfReadShort;

      stft = new STFT(fftLen, sampleRate, wndFuncName);
      stft.setAWeighting(isAWeighting);
      if (spectrumDBcopy == null || spectrumDBcopy.length != fftLen/2+1) {
        spectrumDBcopy = new double[fftLen/2+1];
      }

      // Start recording
      record.startRecording();

      // Main loop
      // When running in this loop (including when paused), you can not change properties
      while (isRunning) {
        // Read data
      	numOfReadShort = record.read(audioSamples, 0, readChunkSize);

        stft.feedData(audioSamples, numOfReadShort);

        // If there is new spectrum data, do plot
        if (stft.nElemSpectrumAmp() >= nFFTAverage) {

          update(spectrumDBcopy);
          
          stft.calculatePeak();
          Log.i("Peak",""+maxAmpFreq);
          maxAmpFreq = stft.maxAmpFreq;
          Log.i("Peak2",""+maxAmpFreq);
        }
      }
      Log.i(TAG, "Looper::Run(): Stopping and releasing recorder.");
      record.stop();
      record.release();
      record = null;
    }
    
    long lastTimeNotifyOverrun = 0;

    private void update(final double[] data) {
     
      AnalyzeActivity.this.runOnUiThread(new Runnable() {
        @Override
        public void run() {
        	refreshPeakLabel();
        }
      });
    }
   
    public void setPause(boolean pause) {
      this.isPaused1 = pause;
    }

    public boolean getPause() {
      return this.isPaused1;
    }
    
    public void finish() {
      isRunning = false;
      interrupt();
    }
  }

  interface Visit {
    public void exec(View view);
  }
}