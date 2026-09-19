/*
Rattlegram

Notification beep played through a configurable output device so that the
alert can go to the phone speaker while the microphone is capturing from a
headset, or to a Bluetooth headset while the phone microphone is in use.
*/

package com.aicodix.rattlegram;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;

public final class Notifier {

	public static final int DEVICE_DEFAULT = 0;
	public static final int DEVICE_SPEAKER = 1;
	public static final int DEVICE_BLUETOOTH = 2;
	public static final int DEVICE_WIRED = 3;

	private Notifier() {
	}

	private static AudioDeviceInfo pickDevice(Context context, int device) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
			return null;
		AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
		AudioDeviceInfo[] outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
		for (AudioDeviceInfo output : outputs) {
			switch (device) {
				case DEVICE_SPEAKER:
					if (output.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
						return output;
					break;
				case DEVICE_BLUETOOTH:
					if (output.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
						|| output.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
						return output;
					break;
				case DEVICE_WIRED:
					if (output.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET
						|| output.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
						|| output.getType() == AudioDeviceInfo.TYPE_USB_HEADSET)
						return output;
					break;
				default:
					return null;
			}
		}
		return null;
	}

	public static void play(Context context, int device) {
		AudioTrack track = null;
		try {
			final int rate = 16000;
			final int durationMs = 450;
			final int count = rate * durationMs / 1000;
			short[] tone = new short[count];
			double phase = 0;
			int length = tone.length;
			for (int i = 0; i < length; ++i) {
				double progress = (double) i / length;
				double freq = 880.0 + 440.0 * progress;
				double envelope = Math.min(1.0, Math.min(progress / 0.05, (1.0 - progress) / 0.15));
				tone[i] = (short) (0.75 * Short.MAX_VALUE * envelope * Math.sin(phase));
				phase += 2.0 * Math.PI * freq / rate;
			}
			track = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
				AudioFormat.ENCODING_PCM_16BIT, count * 2, AudioTrack.MODE_STATIC);
			AudioDeviceInfo deviceInfo = pickDevice(context, device);
			if (deviceInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
				track.setPreferredDevice(deviceInfo);
			track.write(tone, 0, tone.length);
			if (track.getState() != AudioTrack.STATE_INITIALIZED)
				return;
			track.play();
			// Called on the notification worker, never the UI thread.
			SystemClock.sleep(durationMs + 100);
		} catch (Exception ignored) {
		} finally {
			if (track != null)
				track.release();
		}
	}
}