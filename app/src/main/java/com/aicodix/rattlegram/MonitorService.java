/*
Rattlegram

Foreground service that keeps the microphone monitor running while the app
is in the background. It owns the SignalMonitor (AudioRecord + native
decoder), posts a notification with a routed notification sound whenever a
message is successfully decoded and relays events to the bound MainActivity.
*/

package com.aicodix.rattlegram;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.Manifest.permission.RECORD_AUDIO;

public class MonitorService extends Service {

	public interface Listener {
		void onStatus(String str, boolean tmp);
		void onLine(String call, String info);
		void onMessage(String call, byte[] payload, int bits);
		void onSpectrum(int[] spectrum, int[] spectrogram);
	}

	private static final int FOREGROUND_ID = 1;
	private static final int MESSAGE_ID = 2;
	private static final String MONITOR_CHANNEL = "monitor";
	private static final String MESSAGE_CHANNEL = "messages";

	private static final String PREFS = MainActivity.PREFS;
	private SharedPreferences pref;
	private SignalMonitor monitor;
	private boolean foregroundReady;
	private NotificationManager nm;
	private Listener activityListener;
	private boolean notifySoundEnabled = true;
	private int notifyDevice = Notifier.DEVICE_SPEAKER;

	private final IBinder binder = new LocalBinder();

	public class LocalBinder extends android.os.Binder {
		public MonitorService getService() {
			return MonitorService.this;
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		pref = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		createChannels();
		monitor = new SignalMonitor(this, monitorListener);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		readSettings();
		boolean micPermission = ContextCompat.checkSelfPermission(this, RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
		if (!micPermission) {
			stopSelf();
			return START_NOT_STICKY;
		}
		try {
			startForegroundSafe();
			foregroundReady = true;
			resume();
		} catch (SecurityException | IllegalStateException | IllegalArgumentException e) {
			Log.e("MonitorService", "Cannot start foreground microphone service", e);
			foregroundReady = false;
			monitor.stop();
			stopSelf();
		}
		return START_NOT_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	private void createChannels() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
			return;
		NotificationChannel monitorChannel = new NotificationChannel(MONITOR_CHANNEL,
			getString(R.string.monitor_channel_name), NotificationManager.IMPORTANCE_LOW);
		monitorChannel.setDescription(getString(R.string.monitor_notification_text));
		nm.createNotificationChannel(monitorChannel);
		NotificationChannel messageChannel = new NotificationChannel(MESSAGE_CHANNEL,
			getString(R.string.message_channel_name), NotificationManager.IMPORTANCE_HIGH);
		messageChannel.setSound(null, null);
		messageChannel.setDescription(getString(R.string.message_notification_text));
		nm.createNotificationChannel(messageChannel);
	}

	private void startForegroundSafe() {
		Intent intent = new Intent(this, MainActivity.class);
		PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
			PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		Notification notification = new NotificationCompat.Builder(this, MONITOR_CHANNEL)
			.setSmallIcon(R.drawable.ic_baseline_notifications_none_24)
			.setContentTitle(getString(R.string.monitor_notification_title))
			.setContentText(getString(R.string.monitor_notification_text))
			.setContentIntent(pending)
			.setOngoing(true)
			.build();
		int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
			? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0;
		ServiceCompat.startForeground(this, FOREGROUND_ID, notification, type);
	}

	private void readSettings() {
		notifySoundEnabled = pref.getBoolean("notifySoundEnabled", true);
		notifyDevice = pref.getInt("notifyDevice", Notifier.DEVICE_SPEAKER);
	}

	private int recordRate() {
		return pref.getInt("recordRate", 8000);
	}

	private int recordChannel() {
		return pref.getInt("recordChannel", 0);
	}

	private int audioSource() {
		return pref.getInt("audioSource", MediaRecorder.AudioSource.DEFAULT);
	}

	private void postMessageNotification(String call, String text) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
			&& ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
			return;
		Intent intent = new Intent(this, MainActivity.class);
		PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
			PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		Notification notification = new NotificationCompat.Builder(this, MESSAGE_CHANNEL)
			.setSmallIcon(R.drawable.ic_baseline_chat_bubble_outline_24)
			.setContentTitle(getString(R.string.message_notification_title, call))
			.setContentText(text)
			.setStyle(new NotificationCompat.BigTextStyle().bigText(text))
			.setContentIntent(pending)
			.setAutoCancel(true)
			.build();
		try {
			NotificationManagerCompat.from(this).notify(MESSAGE_ID, notification);
		} catch (SecurityException ignored) {
		}
	}

	private final SignalMonitor.Listener monitorListener = new SignalMonitor.Listener() {
		@Override
		public void onStatus(String str, boolean tmp) {
			if (activityListener != null)
				activityListener.onStatus(str, tmp);
		}

		@Override
		public void onLine(String call, String info) {
			if (activityListener != null)
				activityListener.onLine(call, info);
		}

		@Override
		public void onMessage(String call, byte[] payload, int bits) {
			String text = new String(payload).trim();
			if (activityListener != null)
				activityListener.onMessage(call, payload, bits);
			final boolean notify = notifySoundEnabled;
			final int device = notifyDevice;
			new Thread(() -> {
				postMessageNotification(call, text);
				if (notify)
					Notifier.play(MonitorService.this, device);
			}).start();
		}

		@Override
		public void onSpectrum(int[] spectrum, int[] spectrogram) {
			if (activityListener != null)
				activityListener.onSpectrum(spectrum, spectrogram);
		}
	};

	public void attach(Listener listener) {
		activityListener = listener;
	}

	public void detach() {
		activityListener = null;
	}

	public void onSettingsChanged() {
		resume();
	}

	public void setShowSpectrum(boolean show) {
		monitor.setShowSpectrum(show);
	}

	public void setSpectrumTint(int tint) {
		monitor.setSpectrumTint(tint);
	}

	public boolean isListening() {
		return monitor != null && monitor.isListening();
	}

	public void resume() {
		if (foregroundReady && monitor != null
			&& ContextCompat.checkSelfPermission(this, RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
			readSettings();
			monitor.configure(recordRate(), recordChannel(), audioSource());
			monitor.start();
		}
	}

	public void pause() {
		if (monitor != null)
			monitor.stop();
	}

	public void shutdown() {
		foregroundReady = false;
		if (monitor != null)
			monitor.release();
		notificationManagerCancelForeground();
		stopSelf();
	}

	private void notificationManagerCancelForeground() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
			stopForeground(STOP_FOREGROUND_REMOVE);
		else
			stopForeground(true);
	}

	@Override
	public void onDestroy() {
		if (monitor != null)
			monitor.release();
		super.onDestroy();
	}
}