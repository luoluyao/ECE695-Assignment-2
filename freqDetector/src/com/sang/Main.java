package com.sang;

import android.app.Activity;
import android.content.SharedPreferences;
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
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

public class Main extends Activity {
  static final String TAG="AnalyzeActivity";
  static float DPRatio;

  private double freq_result = 0;
  private double freq_output = 0;

  private int counter = 0;
  private boolean freq_found = false;
  
  private Looper samplingThread;

  private final static int RECORDER_AGC_OFF = MediaRecorder.AudioSource.VOICE_RECOGNITION;
  private final static int BYTE_OF_SAMPLE = 2;
  
  private static int fftLen = 2048;
  private static int sampleRate = 32000;
  private static int nFFTAverage = 2;
  private static String wndFuncName;

  private static int audioSourceId = RECORDER_AGC_OFF;
  private boolean isAWeighting = false;
  
  float listItemTextSize = 20;        
  float listItemTitleTextSize = 12;   
  
  Object oblock = new Object();
  
  Button btn;
  
  double maxAmpFreq;

  StringBuilder textPeak = new StringBuilder("");

  char[] textPeakChar;
  
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main);
    
    addListenerOnButton();
    DPRatio = getResources().getDisplayMetrics().density;
    
    final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
    Log.i(TAG, " max mem = " + maxMemory + "k");
    
    // set and get preferences in PreferenceActivity
    PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
    // Set variable according to the preferences

    textPeakChar = new char[getString(R.string.textview_peak_text).length()];

    setTextViewFontSize();
  }

  private void addListenerOnButton() {
	// TODO Auto-generated method stub
	  btn = (Button) findViewById(R.id.Reset);
	  
		btn.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View arg0) {
				freq_found = false;
			}

		});
}

// Set text font size of textview_cur and textview_peak according to space left
  @SuppressWarnings("deprecation")
  private void setTextViewFontSize() {
    TextView tv = (TextView) findViewById(R.id.textview_peak);
    TextView tv1= (TextView) findViewById(R.id.result);

    Paint mTestPaint = new Paint();
    mTestPaint.setTextSize(tv.getTextSize());
    mTestPaint.setTypeface(Typeface.MONOSPACE);
    
    final String text = "Current Peak:";

    Display display = getWindowManager().getDefaultDisplay();
    
    // pixels left
    float px = display.getWidth() - getResources().getDimension(R.dimen.textview_RMS_layout_width) - 5;
    
    float fs = tv.getTextSize();  // size in pixel
    float fs1 = tv1.getTextSize();  // size in pixel

    while (mTestPaint.measureText(text) > px && fs > 5) {
      fs -= 0.5;
      mTestPaint.setTextSize(fs);
      mTestPaint.setTextSize(fs1);
    }
    ((TextView) findViewById(R.id.textview_peak)).setTextSize(fs / DPRatio);
    ((TextView) findViewById(R.id.result)).setTextSize(fs / DPRatio);

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
 
  private void refreshPeakLabel() {
	int maxPeak;
    textPeak.setLength(0);
    textPeak.append("Peak:");
    maxPeak = (int) Math.round(maxAmpFreq);
    textPeak.append(maxPeak);
    textPeak.append("Hz");
    textPeak.getChars(0, Math.min(textPeak.length(), textPeakChar.length), textPeakChar, 0);
    
    
    TextView tv = (TextView) findViewById(R.id.textview_peak);
	TextView tv1 = (TextView) findViewById(R.id.result);

    tv.setText(textPeak);
    if(freq_found == true)
    	tv1.setText("Frequency Detected: "+freq_output+"Hz");
    else
    	tv1.setText("Frequency Detected: ");
    tv.invalidate();
  }

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
    	  Main.this.invalidateGraphView(paddingViewMask);
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

    @Override
    public void run() {
    	double temp_before = 0;
    	double temp_after = 0;
    	
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
      while (isRunning) {
        // Read data
      	numOfReadShort = record.read(audioSamples, 0, readChunkSize);

        stft.feedData(audioSamples, numOfReadShort);

        // If there is new spectrum data, do plot
        if (stft.nElemSpectrumAmp() >= nFFTAverage) {

          update(spectrumDBcopy);
          
          stft.calculatePeak();
          Log.i("Peak",""+maxAmpFreq);
          temp_before = maxAmpFreq;
          maxAmpFreq = stft.maxAmpFreq;
          temp_after = maxAmpFreq;
          
          if (temp_after - temp_before < 3){
        	  freq_result = (temp_after + temp_before)/2;
        	  counter++;
          }else{
        	  counter = 0;
          }
          
          Log.i("Freq_result",""+freq_result);
          Log.i("Counter",""+counter);

          if (counter > 30){
        	  freq_found = true;
        	  freq_output = Math.round(freq_result);
        	  Log.i("Found",""+Math.round(freq_result));
        	  counter = 0;
          }
        }
      }
      Log.i(TAG, "Looper::Run(): Stopping and releasing recorder.");
      record.stop();
      record.release();
      record = null;
    }
    
    long lastTimeNotifyOverrun = 0;

    private void update(final double[] data) {
     
    	Main.this.runOnUiThread(new Runnable() {
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