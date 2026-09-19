/*
Rattlegram

Copyright 2022 Ahmet Inan <inan@aicodix.de>

SignalMonitor captures the microphone through AudioRecord and feeds the
native COFDMTV decoder continuously. It is owned by MonitorService so that
listening continues while the app is in the background.

Audio is read and decoded on a dedicated HandlerThread so that main-thread
stalls (e.g. while the app is in the background) can never starve the mic
input and drop part of a transmission. Listener events are dispatched back
to the main looper so the UI can update safely.
*/

package com.aicodix.rattlegram;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.Arrays;

public class SignalMonitor {

	static {
		System.loadLibrary("rattlegram");
	}

	public interface Listener {
		void onStatus(String str, boolean tmp);
		void onLine(String call, String info);
		void onMessage(String call, byte[] payload, int bits);
		void onSpectrum(int[] spectrum, int[] spectrogram);
	}

	private final Context context;
	private final Handler handler;
	private final HandlerThread recordThread;
	private final Handler recordHandler;
	private volatile boolean listening;
	private volatile boolean released;
	// Accessed only by this monitor's recording thread and JNI.
	private long nativeDecoder;
	private int bufferedSamples;
	private Listener listener;
	private AudioRecord audioRecord;
	private int recordRate;
	private int recordChannel;
	private int audioSource;
	private int recordCount;
	private short[] recordBuffer;
	private volatile boolean showSpectrum;
	private volatile int spectrumTint;
	private final int spectrumWidth = 360, spectrumHeight = 128;
	private final int spectrogramWidth = 360, spectrogramHeight = 128;
	private int[] spectrumPixels, spectrogramPixels;
	private final float[] stagedCFO = new float[1];
	private final int[] stagedMode = new int[1];
	private final byte[] stagedCall = new byte[10];
	private final byte[] payload = new byte[170];

	private native boolean createDecoder(int sampleRate);

	private native void destroyDecoder();

	private native boolean feedDecoder(short[] audioBuffer, int sampleCount, int channelSelect);

	private native int processDecoder();

	private native void spectrumDecoder(int[] spectrumPixels, int[] spectrogramPixels, int spectrumTint);

	private native void stagedDecoder(float[] carrierFrequencyOffset, int[] operationMode, byte[] callSign);

	private native int fetchDecoder(byte[] payload);

	public SignalMonitor(Context context, Listener listener) {
		this.context = context;
		this.listener = listener;
		this.handler = new Handler(Looper.getMainLooper());
		recordThread = new HandlerThread("AudioRecordThread");
		recordThread.start();
		recordHandler = new Handler(recordThread.getLooper());
	}

	public void setListener(Listener listener) {
		this.listener = listener;
	}

	public void setShowSpectrum(boolean show) {
		showSpectrum = show;
	}

	public void setSpectrumTint(int tint) {
		spectrumTint = tint;
	}

	public boolean isListening() {
		return listening;
	}

	public void start() {
		if (!released)
			recordHandler.post(this::startOnWorker);
	}

	private void startOnWorker() {
		if (released || listening || audioRecord == null)
			return;
		try {
			audioRecord.startRecording();
			listening = audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
			if (!listening) {
				setStatus(context.getString(R.string.audio_recording_error));
				return;
			}
			bufferedSamples = 0;
			setStatus(context.getString(R.string.listening));
			recordHandler.post(capture);
		} catch (IllegalStateException | SecurityException e) {
			Log.e("SignalMonitor", "Cannot start microphone", e);
			setStatus(context.getString(R.string.audio_recording_error));
		}
	}

	public void stop() {
		if (!released)
			recordHandler.post(this::stopOnWorker);
	}

	private void stopOnWorker() {
		listening = false;
		recordHandler.removeCallbacks(capture);
		if (audioRecord != null) {
			try {
				if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING)
					audioRecord.stop();
			} catch (IllegalStateException e) {
				Log.w("SignalMonitor", "Cannot stop microphone", e);
			}
		}
		bufferedSamples = 0;
	}

	public void release() {
		if (released)
			return;
		released = true;
		listening = false;
		listener = null;
		handler.removeCallbacksAndMessages(null);
		recordHandler.post(() -> {
			stopOnWorker();
			if (audioRecord != null) {
				audioRecord.release();
				audioRecord = null;
			}
			destroyDecoder();
			recordThread.quitSafely();
		});
	}

	private final Runnable capture = new Runnable() {
		@Override
		public void run() {
			if (released || !listening || audioRecord == null)
				return;
			try {
				int count;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
					count = audioRecord.read(recordBuffer, bufferedSamples,
						recordBuffer.length - bufferedSamples, AudioRecord.READ_NON_BLOCKING);
				else
					count = audioRecord.read(recordBuffer, bufferedSamples,
						recordBuffer.length - bufferedSamples);
				if (count < 0) {
					stopOnWorker();
					setStatus(context.getString(R.string.audio_recording_error));
					return;
				}
				bufferedSamples += count;
				if (bufferedSamples == recordBuffer.length) {
					bufferedSamples = 0;
					decodeBlock();
				}
				if (!released && listening)
					recordHandler.postDelayed(this, count == 0 ? 10 : 0);
			} catch (IllegalStateException | SecurityException e) {
				Log.e("SignalMonitor", "Microphone read failed", e);
				stopOnWorker();
				setStatus(context.getString(R.string.audio_recording_error));
			}
		}
	};

	public void configure(int newRate, int newChannel, int newSource) {
		if (!released)
			recordHandler.post(() -> configureOnWorker(newRate, newChannel, newSource));
	}

	private void configureOnWorker(int newRate, int newChannel, int newSource) {
		if (released)
			return;
		if (audioRecord != null) {
			boolean rateChanged = audioRecord.getSampleRate() != newRate;
			boolean channelChanged = recordChannel != newChannel;
			boolean sourceChanged = audioRecord.getAudioSource() != newSource;
			if (!rateChanged && !channelChanged && !sourceChanged)
				return;
			stopOnWorker();
			audioRecord.release();
			audioRecord = null;
		}
		recordRate = newRate;
		recordChannel = newChannel;
		audioSource = newSource;
		int channelConfig = AudioFormat.CHANNEL_IN_MONO;
		int channelCount = 1;
		if (recordChannel != 0) {
			channelCount = 2;
			channelConfig = AudioFormat.CHANNEL_IN_STEREO;
		}
		int sampleSize = 2;
		int frameSize = sampleSize * channelCount;
		int minimum = AudioRecord.getMinBufferSize(recordRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
		if (minimum <= 0) {
			setStatus(context.getString(R.string.audio_setup_failed));
			return;
		}
		int bufferSize = Math.max(minimum, 2 * Integer.highestOneBit(3 * recordRate) * frameSize);
		try {
			AudioRecord testAudioRecord = new AudioRecord(audioSource, recordRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
			if (testAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
				if (createDecoder(recordRate)) {
					audioRecord = testAudioRecord;
					recordCount = recordRate / 50;
					recordBuffer = new short[recordCount * channelCount];

				} else {
					testAudioRecord.release();
					setStatus(context.getString(R.string.heap_error));
				}
			} else {
				testAudioRecord.release();
				setStatus(context.getString(R.string.audio_init_failed));
			}
		} catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
			setStatus(context.getString(R.string.audio_setup_failed));
		} catch (SecurityException e) {
			setStatus(context.getString(R.string.audio_permission_denied));
		}
	}

	private void decodeBlock() {
		if (!feedDecoder(recordBuffer, recordCount, recordChannel))
			return;
		int status = processDecoder();
		final int STATUS_OKAY = 0;
		final int STATUS_FAIL = 1;
		final int STATUS_SYNC = 2;
		final int STATUS_DONE = 3;
		final int STATUS_HEAP = 4;
		final int STATUS_NOPE = 5;
		final int STATUS_PING = 6;
		if (showSpectrum) {
			if (spectrumPixels == null) {
				spectrumPixels = new int[spectrumWidth * spectrumHeight];
				spectrogramPixels = new int[spectrogramWidth * spectrogramHeight];
			}
			spectrumDecoder(spectrumPixels, spectrogramPixels, spectrumTint);
			final int[] spectrum = spectrumPixels.clone();
			final int[] spectrogram = spectrogramPixels.clone();
			handler.post(() -> {
				if (listener != null)
					listener.onSpectrum(spectrum, spectrogram);
			});
		}
		switch (status) {
			case STATUS_OKAY:
				break;
			case STATUS_FAIL:
				handler.post(() -> setStatus(context.getString(R.string.preamble_fail), true));
				break;
			case STATUS_NOPE:
				stagedDecoder(stagedCFO, stagedMode, stagedCall);
				final String nopeCall = new String(stagedCall).trim();
				final String nopeInfo = context.getString(R.string.preamble_nope, stagedMode[0]);
				final String nopeStatus = context.getString(R.string.from_status, nopeCall, stagedMode[0], stagedCFO[0]);
				handler.post(() -> {
					setStatus(nopeStatus, true);
					if (listener != null)
						listener.onLine(nopeCall, nopeInfo);
				});
				break;
			case STATUS_PING:
				stagedDecoder(stagedCFO, stagedMode, stagedCall);
				final String pingCall = new String(stagedCall).trim();
				final String pingStatus = context.getString(R.string.from_status, pingCall, stagedMode[0], stagedCFO[0]);
				handler.post(() -> {
					setStatus(pingStatus, true);
					if (listener != null)
						listener.onLine(pingCall, context.getString(R.string.preamble_ping));
				});
				break;
			case STATUS_HEAP:
				stopOnWorker();
				handler.post(() -> setStatus(context.getString(R.string.heap_error), false));
				break;
			case STATUS_SYNC:
				stagedDecoder(stagedCFO, stagedMode, stagedCall);
				final String syncCall = new String(stagedCall).trim();
				final String syncStatus = context.getString(R.string.from_status, syncCall, stagedMode[0], stagedCFO[0]);
				handler.post(() -> setStatus(syncStatus, true));
				break;
			case STATUS_DONE:
				int result = fetchDecoder(payload);
				if (result < 0) {
					final String doneCall = new String(stagedCall).trim();
					final String failInfo = context.getString(R.string.decoding_failed);
					handler.post(() -> {
						if (listener != null)
							listener.onLine(doneCall, failInfo);
					});
				} else {
					final byte[] outPayload = Arrays.copyOf(payload, payload.length);
					final String doneCall = new String(stagedCall).trim();
					final int bits = result;
					final String bitsStatus = context.getResources().getQuantityString(R.plurals.bits_flipped, bits, bits);
					handler.post(() -> {
						setStatus(bitsStatus, true);
						if (listener != null)
							listener.onMessage(doneCall, outPayload, bits);
					});
				}
				break;
		}
	}

	private void setStatus(String str, boolean tmp) {
		handler.post(() -> {
			if (!released && listener != null)
				listener.onStatus(str, tmp);
		});
	}

	private void setStatus(String str) {
		setStatus(str, false);
	}
}