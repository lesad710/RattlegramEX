/*
Rattlegram

Copyright 2022 Ahmet Inan <inan@aicodix.de>

MainActivity is the UI shell. Microphone monitoring and the native decoder
are owned by MonitorService (and its SignalMonitor). This Activity binds
to the service, relays settings, shows received messages, manages transmit
via its own AudioTrack/encoder pipeline and handles the GPS / map features.
*/

package com.aicodix.rattlegram;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.MenuItemCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.aicodix.rattlegram.databinding.ActivityMainBinding;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

	static {
		System.loadLibrary("rattlegram");
	}

	public static final String PREFS = "rattlegram_settings";

	private static class Message {
		public long time;
		public byte[] call;
		public byte[] data;

		public Message(byte[] call, byte[] data) {
			this.time = SystemClock.elapsedRealtime() / 1000;
			this.call = Arrays.copyOf(call, 10);
			this.data = Arrays.copyOf(data, 170);
		}
	}

	private static class NodeInfo {
		public String call;
		public long lastSeen;
		public double lat;
		public double lon;
		public boolean hasPosition;
	}

	private static final class NodesAdapter extends ArrayAdapter<NodeInfo> {
		private static final int[] AVATAR_COLORS = {
			0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1,
			0xFF3949AB, 0xFF1E88E5, 0xFF00897B, 0xFF43A047,
			0xFFF4511E, 0xFFFB8C00, 0xFF6D4C41, 0xFF546E7A
		};
		private final LayoutInflater inflater;
		private final int accentColor;
		private final int grayColor;

		NodesAdapter(Context context, int accentColor, int grayColor) {
			super(context, 0);
			this.inflater = LayoutInflater.from(context);
			this.accentColor = accentColor;
			this.grayColor = grayColor;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View view = inflater.inflate(R.layout.node_row, parent, false);
			NodeInfo node = getItem(position);
			if (node == null || node.call == null || node.call.isEmpty())
				return view;
			TextView callView = view.findViewById(R.id.node_call);
			TextView infoView = view.findViewById(R.id.node_info);
			TextView avatarView = view.findViewById(R.id.node_avatar);
			callView.setText(node.call);
			callView.setTextColor(accentColor);
			int avatarColor = AVATAR_COLORS[(node.call.hashCode() & 0x7FFFFFFF) % AVATAR_COLORS.length];
			avatarView.setBackgroundTintList(ColorStateList.valueOf(avatarColor));
			int firstCodePoint = node.call.codePointAt(0);
			avatarView.setText(new String(Character.toChars(firstCodePoint)).toUpperCase(Locale.US));
			long ago = (System.currentTimeMillis() - node.lastSeen) / 1000;
			String shown;
			if (ago < 60)
				shown = getContext().getString(R.string.just_now);
			else if (ago < 3600)
				shown = getContext().getString(R.string.minutes_ago, ago / 60);
			else
				shown = getContext().getString(R.string.hours_ago, ago / 3600);
			StringBuilder info = new StringBuilder(shown);
			if (node.hasPosition) {
				info.append(" · ").append(GpsUtils.locator(node.lat, node.lon));
				info.append(" · ").append(String.format(Locale.US, "%.5f, %.5f", node.lat, node.lon));
			}
			infoView.setText(info.toString());
			infoView.setTextColor(grayColor);
			return view;
		}
	}

	private static final class MessageAdapter extends ArrayAdapter<String> {
		private final LayoutInflater inflater;
		private int gpsColor;
		private int accentColor;
		private int tintColor;
		private int grayColor;
		private String myCall = "";

		MessageAdapter(Context context, int gpsColor, int tintColor, int grayColor, int accentColor) {
			super(context, 0);
			this.inflater = LayoutInflater.from(context);
			this.gpsColor = gpsColor;
			this.tintColor = tintColor;
			this.grayColor = grayColor;
			this.accentColor = accentColor;
		}

		void setMyCall(String call) {
			myCall = call;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View view = inflater.inflate(R.layout.message_row, parent, false);
			String item = getItem(position);
			if (item == null)
				return view;
			String[] lines = item.split("\n", 2);
			String header = lines[0];
			String body = lines.length == 2 ? lines[1].trim() : "";
			String[] parts = header.split(" - ");
			String call = parts.length >= 2 ? parts[1].trim() : "";
			String time = parts.length >= 1 ? parts[0].trim() : "";
			String info = parts.length >= 3 ? parts[2].trim() : "";
			if (info.endsWith(":"))
				info = info.substring(0, info.length() - 1);
			boolean mine = myCall != null && !myCall.isEmpty() && call.equalsIgnoreCase(myCall);
			LinearLayout root = (LinearLayout) view;
			LinearLayout bubble = view.findViewById(R.id.bubble);
			TextView callView = view.findViewById(R.id.row_callsign);
			TextView bodyView = view.findViewById(R.id.row_body);
			TextView infoView = view.findViewById(R.id.row_info);
			root.setGravity(mine ? Gravity.END : Gravity.START);
			bubble.setBackgroundResource(mine ? R.drawable.bubble_out : R.drawable.bubble_in);
			callView.setText(mine ? "Me" : call);
			callView.setTextColor(mine ? Color.WHITE : accentColor);
			bodyView.setText(body);
			boolean hasGps = GpsUtils.extract(item) != null;
			bodyView.setTextColor(mine && !hasGps ? Color.WHITE : hasGps ? gpsColor : Color.WHITE);
			String timeLabel = time.contains(" ") ? time.substring(time.indexOf(' ') + 1) : time;
			infoView.setText(info.isEmpty() ? timeLabel : info + " · " + timeLabel);
			infoView.setTextColor(Color.WHITE);
			return view;
		}
	}

	private ArrayList<Message> repeatedMessages;

	private static final int PERMISSION_ID = 1;
	private final int sampleSize = 2;
	private final int spectrumWidth = 360, spectrumHeight = 128;
	private final int spectrogramWidth = 360, spectrogramHeight = 128;
	private Bitmap spectrumBitmap, spectrogramBitmap;
	private int[] spectrumPixels, spectrogramPixels;
	private ImageView spectrumView, spectrogramView;
	private TextView status;
	private AudioTrack audioTrack;
	private boolean fancyHeader;
	private boolean repeaterMode;
	private boolean showSpectrum;
	private int spectrumTint;
	private int noiseSymbols;
	private int repeaterDelay;
	private int repeaterDebounce;
	private int recordRate;
	private int outputRate;
	private int recordChannel;
	private int outputChannel;
	private int audioSource;
	private int carrierFrequency;
	private short[] outputBuffer;
	private Menu menu;
	private Handler handler;
	private Runnable statusTimer;
	private String prevStatus;
	private byte[] payload;
	private MessageAdapter messages;
	private String callSign;
	private String draftText;

	private boolean backgroundMonitor;
	private boolean notifySoundEnabled;
	private int notifyDevice;
	private boolean attachLocation;
	private int tileIndex;
	private String tileUrl;

	private MonitorService monitorService;
	private boolean serviceBound;
	private boolean bindingRequested;
	private boolean activityResumed;
	private boolean monitorServiceStarted;

	private EditText messageInput;
	private ImageButton sendButton;
	private ImageButton templateButton;
	private TextView capacityText;
	private BottomNavigationView bottomNav;
	private LinearLayout chatPage;
	private FrameLayout meshPage;
	private FrameLayout mapPage;

	private MapView mapView;
	private int selectedTab;
	private final Map<String, GeoPoint> latestPositions = new HashMap<>();
	private boolean mapFitPending;
	private GeoPoint pendingMapFocus;
	private GeoPoint myPosition;
	private boolean meshRelay;
	private final Map<String, NodeInfo> nodesMap = new HashMap<>();
	private NodesAdapter nodesAdapter;
	private boolean positionBeacon;
	private final Handler beaconHandler = new Handler(Looper.getMainLooper());
	private final Runnable beaconRunnable = new Runnable() {
		@Override
		public void run() {
			sendBeacon();
			if (positionBeacon)
				beaconHandler.postDelayed(this, BEACON_INTERVAL);
		}
	};
	private static final long BEACON_INTERVAL = 10 * 60 * 1000L;

	private native boolean createEncoder(int sampleRate);

	private native void configureEncoder(byte[] payload, byte[] callSign, int carrierFrequency, int noiseSymbols, boolean fancyHeader);

	private native boolean produceEncoder(short[] audioBuffer, int channelSelect);

	private native void destroyEncoder();

	private final AudioTrack.OnPlaybackPositionUpdateListener outputListener = new AudioTrack.OnPlaybackPositionUpdateListener() {
		@Override
		public void onMarkerReached(AudioTrack ignore) {
		}

		@Override
		public void onPeriodicNotification(AudioTrack audioTrack) {
			if (audioTrack != MainActivity.this.audioTrack || isDestroyed())
				return;
			if (produceEncoder(outputBuffer, outputChannel)) {
				audioTrack.write(outputBuffer, 0, outputBuffer.length);
			} else {
				audioTrack.stop();
				handler.postDelayed(() -> startListening(), 1000);
			}
		}
	};

	private void releaseAudioTrack() {
		if (audioTrack == null)
			return;
		audioTrack.setPlaybackPositionUpdateListener(null);
		try {
			if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED)
				audioTrack.stop();
		} catch (IllegalStateException e) {
			Log.w("MainActivity", "Cannot stop playback", e);
		} finally {
			audioTrack.release();
			audioTrack = null;
		}
	}

	private void initAudioTrack() {
		if (audioTrack != null && audioTrack.getState() == AudioTrack.STATE_INITIALIZED
			&& audioTrack.getSampleRate() == outputRate
			&& audioTrack.getChannelCount() == (outputChannel == 0 ? 1 : 2))
			return;
		releaseAudioTrack();
		int channelCount = outputChannel == 0 ? 1 : 2;
		int channelConfig = channelCount == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
		int symbolLength = (1280 * outputRate) / 8000;
		int extendedLength = symbolLength + symbolLength / 8;
		int minimum = AudioTrack.getMinBufferSize(outputRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
		if (minimum <= 0) {
			setStatus(getString(R.string.audio_setup_failed), true);
			return;
		}
		int bufferSize = Math.max(minimum, 5 * extendedLength * sampleSize * channelCount);
		try {
			audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, outputRate, channelConfig,
				AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
			if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED || !createEncoder(outputRate)) {
				releaseAudioTrack();
				setStatus(getString(R.string.audio_init_failed), true);
				return;
			}
			outputBuffer = new short[extendedLength * channelCount];
			audioTrack.setPlaybackPositionUpdateListener(outputListener);
			audioTrack.setPositionNotificationPeriod(extendedLength);
		} catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
			Log.e("MainActivity", "Cannot open playback", e);
			releaseAudioTrack();
			setStatus(getString(R.string.audio_setup_failed), true);
		}
	}

	private void setStatus(String str, boolean tmp) {
		if (statusTimer != null)
			handler.removeCallbacks(statusTimer);
		if (tmp) {
			statusTimer = () -> status.setText(prevStatus);
			handler.postDelayed(statusTimer, 10000);
		} else {
			prevStatus = str;
		}
		status.setText(str);
	}

	private void setStatus(String str) {
		setStatus(str, false);
	}

	private byte[] callTerm() {
		return Arrays.copyOf(callSign.getBytes(StandardCharsets.US_ASCII), callSign.length() + 1);
	}

	private String currentTime() {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
	}

	private void addLine(String call, String info) {
		addString(getString(R.string.title_line, currentTime(), call, info));
	}

	private void addMessage(String call, String info, String mesg) {
		addString(getString(R.string.title_message, currentTime(), call, info, mesg));
	}

	private void addString(String str) {
		int count = 100;
		if (messages.getCount() >= count)
			messages.remove(messages.getItem(count - 1));
		messages.insert(str, 0);
		storeSettings();
	}

	private void startListening() {
		if (monitorService != null)
			monitorService.resume();
	}

	private void stopListening() {
		if (monitorService != null)
			monitorService.pause();
	}

	private void setRecordRate(int newSampleRate) {
		if (recordRate == newSampleRate)
			return;
		recordRate = newSampleRate;
		updateRecordRateMenu();
		notifyServiceSettingsChanged();
	}

	private void setRecordChannel(int newChannelSelect) {
		if (recordChannel == newChannelSelect)
			return;
		recordChannel = newChannelSelect;
		updateRecordChannelMenu();
		notifyServiceSettingsChanged();
	}

	private void setAudioSource(int newAudioSource) {
		if (audioSource == newAudioSource)
			return;
		audioSource = newAudioSource;
		updateAudioSourceMenu();
		notifyServiceSettingsChanged();
	}

	private void notifyServiceSettingsChanged() {
		storeSettings();
		if (monitorService != null)
			monitorService.onSettingsChanged();
	}

	private final MonitorService.Listener monitorListener = new MonitorService.Listener() {
		@Override
		public void onStatus(String str, boolean tmp) {
			setStatus(str, tmp);
		}

		@Override
		public void onLine(String call, String info) {
			touchNode(call.trim(), null, null);
			addLine(call, info);
		}

		@Override
		public void onMessage(String call, byte[] payload, int bits) {
			String callTrim = call.trim();
			String text = new String(payload).trim();
			double[] loc = GpsUtils.extract(text);
			touchNode(callTrim, loc != null ? loc[0] : null, loc != null ? loc[1] : null);
			if (repeaterMode || (meshRelay && !callTrim.equalsIgnoreCase(callSign.trim())))
				repeatMessage(call, payload);
			else
				addMessage(callTrim, getString(R.string.received), text);
			if (selectedTab == 2)
				refreshMapMarkers();
		}

		@Override
		public void onSpectrum(int[] spectrum, int[] spectrogram) {
			if (showSpectrum && spectrumBitmap != null) {
				spectrumBitmap.setPixels(spectrum, 0, spectrumWidth, 0, 0, spectrumWidth, spectrumHeight);
				spectrogramBitmap.setPixels(spectrogram, 0, spectrogramWidth, 0, 0, spectrogramWidth, spectrogramHeight);
				spectrumView.invalidate();
				spectrogramView.invalidate();
			}
		}
	};

	private final ServiceConnection connection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder binder) {
			MonitorService service = ((MonitorService.LocalBinder) binder).getService();
			monitorService = service;
			serviceBound = true;
			service.attach(monitorListener);
			service.setShowSpectrum(showSpectrum);
			service.setSpectrumTint(spectrumTint);
			// onStartCommand owns the initial microphone start.
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			serviceBound = false;
			monitorService = null;
		}
	};

	private void startMonitorService() {
		if (monitorServiceStarted || !activityResumed
			|| ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
				!= PackageManager.PERMISSION_GRANTED)
			return;
		Intent intent = new Intent(this, MonitorService.class);
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
				startForegroundService(intent);
			else
				startService(intent);
			bindingRequested = bindService(intent, connection, Context.BIND_AUTO_CREATE);
			monitorServiceStarted = bindingRequested;
		} catch (SecurityException | IllegalStateException e) {
			Log.e("MainActivity", "Cannot start microphone service", e);
			monitorServiceStarted = false;
			setStatus(getString(R.string.audio_recording_error), true);
		}
	}

	private void unbindMonitor() {
		if (monitorService != null)
			monitorService.detach();
		if (bindingRequested) {
			unbindService(connection);
			bindingRequested = false;
		}
		serviceBound = false;
		monitorService = null;
		monitorServiceStarted = false;
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle state) {
		state.putInt("nightMode", AppCompatDelegate.getDefaultNightMode());
		state.putInt("outputRate", outputRate);
		state.putInt("outputChannel", outputChannel);
		state.putInt("recordRate", recordRate);
		state.putInt("recordChannel", recordChannel);
		state.putInt("audioSource", audioSource);
		state.putInt("carrierFrequency", carrierFrequency);
		state.putInt("noiseSymbols", noiseSymbols);
		state.putInt("repeaterDelay", repeaterDelay);
		state.putInt("repeaterDebounce", repeaterDebounce);
		state.putString("callSign", callSign);
		state.putString("draftText", messageInput != null ? messageInput.getText().toString() : draftText);
		state.putBoolean("fancyHeader", fancyHeader);
		state.putBoolean("repeaterMode", repeaterMode);
		state.putBoolean("backgroundMonitor", backgroundMonitor);
		state.putBoolean("notifySoundEnabled", notifySoundEnabled);
		state.putInt("notifyDevice", notifyDevice);
		state.putBoolean("attachLocation", attachLocation);
		state.putInt("tileIndex", tileIndex);
		state.putString("tileUrl", tileUrl != null ? tileUrl : "");
		state.putInt("selectedTab", selectedTab);
		state.putBoolean("meshRelay", meshRelay);
		state.putBoolean("positionBeacon", positionBeacon);
		for (int i = 0; i < messages.getCount(); ++i)
			state.putString("m" + i, messages.getItem(i));
		super.onSaveInstanceState(state);
	}

	private void storeSettings() {
		SharedPreferences pref = prefs();
		SharedPreferences.Editor edit = pref.edit();
		edit.putInt("nightMode", AppCompatDelegate.getDefaultNightMode());
		edit.putInt("outputRate", outputRate);
		edit.putInt("outputChannel", outputChannel);
		edit.putInt("recordRate", recordRate);
		edit.putInt("recordChannel", recordChannel);
		edit.putInt("audioSource", audioSource);
		edit.putInt("carrierFrequency", carrierFrequency);
		edit.putInt("noiseSymbols", noiseSymbols);
		edit.putInt("repeaterDelay", repeaterDelay);
		edit.putInt("repeaterDebounce", repeaterDebounce);
		edit.putString("callSign", callSign);
		edit.putString("draftText", messageInput != null ? messageInput.getText().toString() : draftText);
		edit.putBoolean("fancyHeader", fancyHeader);
		edit.putBoolean("repeaterMode", repeaterMode);
		edit.putBoolean("backgroundMonitor", backgroundMonitor);
		edit.putBoolean("notifySoundEnabled", notifySoundEnabled);
		edit.putInt("notifyDevice", notifyDevice);
		edit.putBoolean("attachLocation", attachLocation);
		edit.putInt("tileIndex", tileIndex);
		edit.putString("tileUrl", tileUrl != null ? tileUrl : "");
		edit.putInt("selectedTab", selectedTab);
		edit.putBoolean("meshRelay", meshRelay);
		edit.putBoolean("positionBeacon", positionBeacon);
		for (int i = 0; i < messages.getCount(); ++i)
			edit.putString("m" + i, messages.getItem(i));
		edit.apply();
	}

	private SharedPreferences prefs() {
		return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	@Override
	protected void onCreate(Bundle state) {
		final int defaultSampleRate = 8000;
		final int defaultChannelSelect = 0;
		final int defaultAudioSource = MediaRecorder.AudioSource.DEFAULT;
		final int defaultCarrierFrequency = 1500;
		final int defaultNoiseSymbols = 6;
		final int defaultRepeaterDelay = 1;
		final int defaultRepeaterDebounce = 60;
		final String defaultCallSign = "ANONYMOUS";
		final String defaultDraftText = "";
		final boolean defaultFancyHeader = false;
		final boolean defaultRepeaterMode = false;
		final boolean defaultBackgroundMonitor = true;
		final boolean defaultNotifySoundEnabled = true;
		final int defaultNotifyDevice = Notifier.DEVICE_SPEAKER;
		final boolean defaultAttachLocation = true;
		if (state == null) {
			SharedPreferences pref = prefs();
			AppCompatDelegate.setDefaultNightMode(pref.getInt("nightMode", AppCompatDelegate.getDefaultNightMode()));
			outputRate = pref.getInt("outputRate", defaultSampleRate);
			outputChannel = pref.getInt("outputChannel", defaultChannelSelect);
			recordRate = pref.getInt("recordRate", defaultSampleRate);
			recordChannel = pref.getInt("recordChannel", defaultChannelSelect);
			audioSource = pref.getInt("audioSource", defaultAudioSource);
			carrierFrequency = pref.getInt("carrierFrequency", defaultCarrierFrequency);
			noiseSymbols = pref.getInt("noiseSymbols", defaultNoiseSymbols);
			repeaterDelay = pref.getInt("repeaterDelay", defaultRepeaterDelay);
			repeaterDebounce = pref.getInt("repeaterDebounce", defaultRepeaterDebounce);
			callSign = pref.getString("callSign", defaultCallSign);
			draftText = pref.getString("draftText", defaultDraftText);
			fancyHeader = pref.getBoolean("fancyHeader", defaultFancyHeader);
			repeaterMode = pref.getBoolean("repeaterMode", defaultRepeaterMode);
			backgroundMonitor = pref.getBoolean("backgroundMonitor", defaultBackgroundMonitor);
			notifySoundEnabled = pref.getBoolean("notifySoundEnabled", defaultNotifySoundEnabled);
			notifyDevice = pref.getInt("notifyDevice", defaultNotifyDevice);
			attachLocation = pref.getBoolean("attachLocation", defaultAttachLocation);
			tileIndex = pref.getInt("tileIndex", 0);
			tileUrl = pref.getString("tileUrl", "");
			meshRelay = pref.getBoolean("meshRelay", false);
			positionBeacon = pref.getBoolean("positionBeacon", false);
		} else {
			AppCompatDelegate.setDefaultNightMode(state.getInt("nightMode", AppCompatDelegate.getDefaultNightMode()));
			outputRate = state.getInt("outputRate", defaultSampleRate);
			outputChannel = state.getInt("outputChannel", defaultChannelSelect);
			recordRate = state.getInt("recordRate", defaultSampleRate);
			recordChannel = state.getInt("recordChannel", defaultChannelSelect);
			audioSource = state.getInt("audioSource", defaultAudioSource);
			carrierFrequency = state.getInt("carrierFrequency", defaultCarrierFrequency);
			noiseSymbols = state.getInt("noiseSymbols", defaultNoiseSymbols);
			repeaterDelay = state.getInt("repeaterDelay", defaultRepeaterDelay);
			repeaterDebounce = state.getInt("repeaterDebounce", defaultRepeaterDebounce);
			callSign = state.getString("callSign", defaultCallSign);
			draftText = state.getString("draftText", defaultDraftText);
			fancyHeader = state.getBoolean("fancyHeader", defaultFancyHeader);
			repeaterMode = state.getBoolean("repeaterMode", defaultRepeaterMode);
			backgroundMonitor = state.getBoolean("backgroundMonitor", defaultBackgroundMonitor);
			notifySoundEnabled = state.getBoolean("notifySoundEnabled", defaultNotifySoundEnabled);
			notifyDevice = state.getInt("notifyDevice", defaultNotifyDevice);
			attachLocation = state.getBoolean("attachLocation", defaultAttachLocation);
			tileIndex = state.getInt("tileIndex", 0);
			tileUrl = state.getString("tileUrl", "");
			selectedTab = state.getInt("selectedTab", 0);
			meshRelay = state.getBoolean("meshRelay", false);
			positionBeacon = state.getBoolean("positionBeacon", false);
		}
		super.onCreate(state);
		EdgeToEdge.enable(this);
		ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
		status = binding.status;
		handler = new Handler(getMainLooper());
		setContentView(binding.getRoot());
		handleInsets();
		showCrashReport();

		messageInput = binding.messageInput;
		sendButton = binding.sendButton;
		templateButton = binding.templateButton;
		capacityText = binding.capacity;
		bottomNav = binding.bottomNav;
		chatPage = binding.chatPage;
		meshPage = binding.meshPage;
		mapPage = binding.mapPage;
		binding.nodes.setEmptyView(binding.nodesEmpty);
		binding.locationButton.setOnClickListener(v -> requestLocationFix());
		binding.shareButton.setOnClickListener(v -> shareMyLocation());

		payload = new byte[170];
		repeatedMessages = new ArrayList<>();

		int gpsColor = ContextCompat.getColor(this, R.color.gps_green);
		int tintColor = ContextCompat.getColor(this, R.color.tint);
		int grayColor = ContextCompat.getColor(this, R.color.gray);
		int accentColor = ContextCompat.getColor(this, R.color.accent);
		messages = new MessageAdapter(this, gpsColor, tintColor, grayColor, accentColor);
		messages.setMyCall(callSign);
		binding.messages.setAdapter(messages);

		nodesAdapter = new NodesAdapter(this, accentColor, grayColor);
		binding.nodes.setAdapter(nodesAdapter);
		loadNodes();
		refreshNodeList();
		checkCallSign();

		if (state == null) {
			for (int i = 0; i < 100; ++i) {
				String mesg = prefs().getString("m" + i, null);
				if (mesg != null)
					messages.add(mesg);
			}
		} else {
			for (int i = 0; i < 100; ++i) {
				String mesg = state.getString("m" + i, null);
				if (mesg != null)
					messages.add(mesg);
			}
		}

		binding.messages.setOnItemClickListener((adapterView, view, i, l) -> {
			String item = messages.getItem(i);
			if (item != null) {
				double[] loc = GpsUtils.extract(item);
				if (loc != null) {
					showGpsOptions(loc[0], loc[1]);
				} else {
					String[] mesg = item.split("\n", 2);
					if (mesg.length == 2)
						focusInput(mesg[1]);
				}
			}
		});
		binding.messages.setOnItemLongClickListener((adapterView, view, i, l) -> {
			String item = messages.getItem(i);
			if (item != null) {
				double[] loc = GpsUtils.extract(item);
				if (loc != null) {
					openNavigator(loc[0], loc[1]);
					return true;
				}
				String[] mesg = item.split("\n", 2);
				if (mesg.length == 2)
					transmitMessage(mesg[1]);
			}
			return true;
		});

		binding.nodes.setOnItemLongClickListener((adapterView, view, i, l) -> {
			NodeInfo node = nodesAdapter.getItem(i);
			if (node == null || !node.hasPosition)
				return false;
			String[] options = {
				getString(R.string.open_in_map),
				getString(R.string.open_in_navigator),
				getString(R.string.copy_coordinates)
			};
			new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
				.setTitle(node.call)
				.setItems(options, (dialog, which) -> {
					double lat = node.lat, lon = node.lon;
					switch (which) {
						case 0:
							bottomNav.setSelectedItemId(R.id.nav_map);
							focusMapOnPoint(lat, lon);
							break;
						case 1:
							openNavigator(lat, lon);
							break;
						case 2: {
							ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
							if (cm != null) {
								cm.setPrimaryClip(ClipData.newPlainText("coordinates",
									String.format(Locale.US, "%.6f, %.6f", lat, lon)));
								setStatus(getString(R.string.coordinates_copied), true);
							}
							break;
						}
					}
				})
				.show();
			return true;
		});

		sendButton.setEnabled(false);
		sendButton.setOnClickListener(v -> {
			String text = messageInput.getText().toString();
			if (text.getBytes(StandardCharsets.UTF_8).length > 0) {
				transmitMessage(text);
				messageInput.setText("");
				draftText = "";
			}
		});

		templateButton.setOnClickListener(v -> showTemplatesDialog());

		messageInput.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				int bytes = s.toString().getBytes(StandardCharsets.UTF_8).length;
				if (bytes == 0) {
					capacityText.setVisibility(View.GONE);
					sendButton.setEnabled(false);
					return;
				}
				capacityText.setVisibility(View.VISIBLE);
				sendButton.setEnabled(bytes <= 170);
				if (bytes <= 85) {
					int num = 85 - bytes;
					capacityText.setText(getResources().getQuantityString(R.plurals.strong_bytes_left, num, num));
					capacityText.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.gps_green));
				} else if (bytes <= 128) {
					int num = 128 - bytes;
					capacityText.setText(getResources().getQuantityString(R.plurals.medium_bytes_left, num, num));
					capacityText.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.gps_green));
				} else if (bytes <= 170) {
					int num = 170 - bytes;
					capacityText.setText(getResources().getQuantityString(R.plurals.normal_bytes_left, num, num));
					capacityText.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.tint));
				} else {
					int num = bytes - 170;
					capacityText.setText(getResources().getQuantityString(R.plurals.over_capacity, num, num));
					capacityText.setTextColor(Color.RED);
				}
			}

			@Override
			public void afterTextChanged(Editable s) {
			}
		});

		if (!draftText.isEmpty())
			messageInput.setText(draftText);

		int restoredTab = selectedTab;
		setupTabBar();

		if (restoredTab == 1)
			bottomNav.setSelectedItemId(R.id.nav_mesh);
		else if (restoredTab == 2)
			bottomNav.setSelectedItemId(R.id.nav_map);

		// Open playback lazily, so startup only needs the microphone.
		List<String> permissions = new ArrayList<>();
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
			permissions.add(Manifest.permission.RECORD_AUDIO);
		if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
			permissions.add(Manifest.permission.POST_NOTIFICATIONS);
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
			permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
		}
		if (permissions.isEmpty()) {
			startMonitorService();
		} else {
			setStatus(getString(R.string.audio_permission_denied));
			ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_ID);
		}
		String message = extractIntent(getIntent());
		if (message != null)
			focusInput(message);
	}

	private void showGpsOptions(double lat, double lon) {
		String[] options = {
			getString(R.string.open_in_map),
			getString(R.string.open_in_navigator),
			getString(R.string.copy_coordinates)
		};
		new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
			.setTitle(R.string.location)
			.setItems(options, (dialog, which) -> {
				switch (which) {
					case 0:
						bottomNav.setSelectedItemId(R.id.nav_map);
						focusMapOnPoint(lat, lon);
						break;
					case 1:
						openNavigator(lat, lon);
						break;
					case 2:
						ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
						cm.setPrimaryClip(ClipData.newPlainText("coordinates", String.format(Locale.US, "%.6f, %.6f", lat, lon)));
						setStatus(getString(R.string.coordinates_copied), true);
						break;
				}
			})
			.show();
	}

	private void showTemplatesDialog() {
		String[] templates = getResources().getStringArray(R.array.message_templates);
		new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
			.setTitle(R.string.templates)
			.setItems(templates, (dialog, which) -> {
				messageInput.setText(templates[which]);
				messageInput.setSelection(templates[which].length());
			})
			.show();
	}

	private void setPositionBeacon(boolean enabled) {
		positionBeacon = enabled;
		storeSettings();
		updateMonitorMenu();
		beaconHandler.removeCallbacks(beaconRunnable);
		if (enabled) {
			setStatus(getString(R.string.beacon_started), false);
			sendBeacon();
			beaconHandler.postDelayed(beaconRunnable, BEACON_INTERVAL);
		} else {
			setStatus(getString(R.string.beacon_stopped), false);
		}
	}

	private void sendBeacon() {
		setupMap();
		LocationHelper.fetch(this, new LocationHelper.Callback() {
			@Override
			public void onLocation(double lat, double lon) {
				myPosition = new GeoPoint(lat, lon);
				if (myPosition != null)
					transmitMessage(GpsUtils.format(lat, lon));
			}

			@Override
			public void onFailed() {
				setStatus(getString(R.string.gps_unavailable), true);
			}
		});
	}

	private void applyFmRadioProfile() {
		noiseSymbols = 11;
		fancyHeader = false;
		carrierFrequency = 1500;
		storeSettings();
		updateNoiseSymbolsMenu();
		updateFancyHeaderMenu();
		setStatus(getString(R.string.fm_radio_link_done), true);
	}
	
	private void applySsbRadioProfile() {
		noiseSymbols = 11;
		fancyHeader = false;
		carrierFrequency = 1000;
		storeSettings();
		updateNoiseSymbolsMenu();
		updateFancyHeaderMenu();
		setStatus(getString(R.string.ssb_radio_link_done), true);
	}

	private void toggleBackgroundMonitor() {
		if (!backgroundMonitor) {
			new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
				.setTitle(R.string.background_monitor)
				.setMessage(R.string.background_monitor_enable_warning)
				.setPositiveButton(R.string.enable, (dialog, which) -> {
					backgroundMonitor = true;
					storeSettings();
					updateMonitorMenu();
					startMonitorService();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
		} else {
			new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
				.setTitle(R.string.background_monitor)
				.setMessage(R.string.background_monitor_disable_warning)
				.setPositiveButton(R.string.disable, (dialog, which) -> {
					backgroundMonitor = false;
					storeSettings();
					updateMonitorMenu();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
		}
	}

	private void focusInput(String temp) {
		if (temp != null) {
			messageInput.setText(temp);
			messageInput.setSelection(messageInput.length());
		}
		messageInput.requestFocus();
		InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.showSoftInput(messageInput, InputMethodManager.SHOW_IMPLICIT);
	}

	private void setupTabBar() {
		bottomNav.setOnItemSelectedListener(item -> {
			if (item.getItemId() == R.id.nav_chat) {
				switchToChat();
				return true;
			}
			if (item.getItemId() == R.id.nav_mesh) {
				switchToMesh();
				return true;
			}
			if (item.getItemId() == R.id.nav_map) {
				switchToMap();
				return true;
			}
			return false;
		});
		bottomNav.setSelectedItemId(R.id.nav_chat);
	}

	private void switchToChat() {
		selectedTab = 0;
		chatPage.setVisibility(View.VISIBLE);
		meshPage.setVisibility(View.GONE);
		mapPage.setVisibility(View.GONE);
		pauseMap();
	}

	private void switchToMesh() {
		selectedTab = 1;
		chatPage.setVisibility(View.GONE);
		meshPage.setVisibility(View.VISIBLE);
		mapPage.setVisibility(View.GONE);
		refreshNodeList();
		pauseMap();
	}

	private void pauseMap() {
		if (mapView == null)
			return;
		try {
			mapView.onPause();
		} catch (Exception ignored) {
		}
	}

	private void resumeMap() {
		if (mapView == null)
			return;
		try {
			mapView.onResume();
		} catch (Exception ignored) {
		}
	}

	private void switchToMap() {
		selectedTab = 2;
		chatPage.setVisibility(View.GONE);
		meshPage.setVisibility(View.GONE);
		mapPage.setVisibility(View.VISIBLE);
		try {
			setupMap();
			refreshMapMarkers();
			resumeMap();
		} catch (Exception e) {
			setStatus(getString(R.string.map_load_failed), true);
		}
	}

	private void setupMap() {
		if (mapView != null)
			return;
		try {
			Context context = getApplicationContext();
			SharedPreferences pref = prefs();
			Configuration.getInstance().load(context, pref);
			Configuration.getInstance().setOsmdroidBasePath(getFilesDir());
			Configuration.getInstance().setUserAgentValue(context.getPackageName());
			mapView = new MapView(this);
			mapView.setMultiTouchControls(true);
			applyTileSource(pref);
			mapPage.addView(mapView);
		} catch (Exception e) {
			mapView = null;
			throw e;
		}
	}

	private void applyTileSource(SharedPreferences pref) {
		if (mapView == null)
			return;
		int index = pref.getInt("tileIndex", 0);
		String url = pref.getString("tileUrl", "");
		switch (index) {
			case 1:
				mapView.setTileSource(new XYTileSource("OpenTopoMap",
					0, 17, 256, ".png",
					new String[]{
						"https://a.tile.opentopomap.org/",
						"https://b.tile.opentopomap.org/",
						"https://c.tile.opentopomap.org/"
					}));
				break;
			case 2:
				if (url != null && !url.isEmpty())
					mapView.setTileSource(parseCustomTileSource(url));
				else
					mapView.setTileSource(new XYTileSource("MAPNIK",
						0, 19, 256, ".png", new String[]{"https://tile.openstreetmap.org/"}));
				break;
			default:
				mapView.setTileSource(new XYTileSource("MAPNIK",
					0, 19, 256, ".png", new String[]{"https://tile.openstreetmap.org/"}));
				break;
		}
	}

	private XYTileSource parseCustomTileSource(String template) {
		String base = template;
		String ext = ".png";
		String token = "{z}/{x}/{y}";
		int idx = base.indexOf(token);
		if (idx >= 0) {
			ext = base.substring(idx + token.length()).split("\\?|#")[0];
			if (ext.isEmpty())
				ext = ".png";
			base = base.substring(0, idx);
		}
		int scheme = base.indexOf("://");
		if (scheme < 0 || base.isEmpty())
			return new XYTileSource("MAPNIK",
				0, 19, 256, ".png", new String[]{"https://tile.openstreetmap.org/"});
		String[] urls;
		if (base.contains("{s}")) {
			String domain = base.substring(scheme + 3);
			String protocol = base.substring(0, scheme + 3);
			int domainEnd = domain.indexOf('/');
			if (domainEnd < 0)
				domainEnd = domain.length();
			String host = domain.substring(0, domainEnd);
			String rest = domain.substring(domainEnd);
			String[] subs = {"a", "b", "c"};
			urls = new String[subs.length];
			for (int i = 0; i < subs.length; ++i)
				urls[i] = protocol + subs[i] + host + rest;
		} else {
			urls = new String[]{base};
		}
		return new XYTileSource("CustomTiles", 0, 19, 256, ext, urls);
	}

	private void touchNode(String call, Double lat, Double lon) {
		if (call == null || call.trim().isEmpty())
			return;
		String key = call.trim().toUpperCase(Locale.US);
		NodeInfo node = nodesMap.get(key);
		if (node == null) {
			node = new NodeInfo();
			node.call = call.trim();
			nodesMap.put(key, node);
		}
		node.lastSeen = System.currentTimeMillis();
		if (lat != null && lon != null) {
			node.hasPosition = true;
			node.lat = lat;
			node.lon = lon;
		}
		refreshNodeList();
		saveNodes();
	}

	private void saveNodes() {
		try {
			JSONArray arr = new JSONArray();
			for (NodeInfo node : nodesMap.values()) {
				JSONObject entry = new JSONObject();
				entry.put("call", node.call);
				entry.put("lastSeen", node.lastSeen);
				entry.put("lat", node.lat);
				entry.put("lon", node.lon);
				entry.put("hasPosition", node.hasPosition);
				arr.put(entry);
			}
			JSONObject root = new JSONObject();
			root.put("nodes", arr);
			prefs().edit().putString("nodesJson", root.toString()).apply();
		} catch (Exception ignored) {
		}
	}

	private void loadNodes() {
		try {
			String json = prefs().getString("nodesJson", null);
			if (json == null)
				return;
			JSONObject root = new JSONObject(json);
			JSONArray arr = root.getJSONArray("nodes");
			for (int i = 0; i < arr.length(); ++i) {
				JSONObject entry = arr.getJSONObject(i);
				NodeInfo node = new NodeInfo();
				node.call = entry.getString("call");
				node.lastSeen = entry.getLong("lastSeen");
				node.lat = entry.optDouble("lat", 0.0);
				node.lon = entry.optDouble("lon", 0.0);
				node.hasPosition = entry.optBoolean("hasPosition", false);
				if (node.call != null && !node.call.trim().isEmpty()) {
					String key = node.call.trim().toUpperCase(Locale.US);
					nodesMap.put(key, node);
				}
			}
		} catch (Exception ignored) {
		}
	}

	private void refreshNodeList() {
		if (nodesAdapter == null)
			return;
		List<NodeInfo> nodes = new ArrayList<>(nodesMap.values());
		Collections.sort(nodes, (left, right) -> Long.compare(right.lastSeen, left.lastSeen));
		nodesAdapter.clear();
		nodesAdapter.addAll(nodes);
		nodesAdapter.notifyDataSetChanged();
	}

	private void shareMyLocation() {
		setupMap();
		setStatus(getString(R.string.gps_obtaining), true);
		LocationHelper.fetch(this, new LocationHelper.Callback() {
			@Override
			public void onLocation(double lat, double lon) {
				myPosition = new GeoPoint(lat, lon);
				transmitMessage(GpsUtils.format(lat, lon));
			}

			@Override
			public void onFailed() {
				setStatus(getString(R.string.gps_unavailable), true);
			}
		});
	}

	private void requestLocationFix() {
		setupMap();
		setStatus(getString(R.string.gps_obtaining), true);
		LocationHelper.fetch(this, new LocationHelper.Callback() {
			@Override
			public void onLocation(double lat, double lon) {
				myPosition = new GeoPoint(lat, lon);
				refreshMapMarkers();
				pendingMapFocus = new GeoPoint(lat, lon);
				requestFitMap();
				setStatus(getString(R.string.location_attached), true);
			}

			@Override
			public void onFailed() {
				setStatus(getString(R.string.gps_unavailable), true);
			}
		});
	}

	private void refreshMapMarkers() {
		if (mapView == null)
			return;
		try {
			mapView.getOverlays().clear();
			latestPositions.clear();
			for (int i = 0; i < messages.getCount(); ++i) {
				String item = messages.getItem(i);
				if (item == null)
					continue;
				double[] loc = GpsUtils.extract(item);
				if (loc == null)
					continue;
				String[] label = markerLabel(item);
				String key = label[0].trim().toUpperCase(Locale.US);
				if (key.isEmpty())
					key = String.format(Locale.US, "%.6f,%.6f", loc[0], loc[1]);
				if (latestPositions.containsKey(key))
					continue;
				latestPositions.put(key, new GeoPoint(loc[0], loc[1]));
				addMapMarker(new GeoPoint(loc[0], loc[1]), label[0], label[1]);
			}
			for (NodeInfo node : nodesMap.values()) {
				if (!node.hasPosition || node.call == null || node.call.trim().isEmpty())
					continue;
				String key = node.call.trim().toUpperCase(Locale.US);
				if (latestPositions.containsKey(key))
					continue;
				GeoPoint position = new GeoPoint(node.lat, node.lon);
				if (position.getLatitude() < -90 || position.getLatitude() > 90
					|| position.getLongitude() < -180 || position.getLongitude() > 180)
					continue;
				latestPositions.put(key, position);
				addMapMarker(position, node.call.trim(), null);
			}
			if (myPosition != null)
				addMyPositionMarker();
			requestFitMap();
		} catch (Exception e) {
			latestPositions.clear();
		}
	}

	private String[] markerLabel(String item) {
		String call = "";
		String body = "";
		String[] lines = item.split("\n", 2);
		if (lines.length == 2)
			body = lines[1].trim();
		String[] parts = lines[0].split(" - ");
		if (parts.length >= 2)
			call = parts[1].trim();
		body = body.replaceAll("\\[GPS:[^\\]]*\\]", "").trim();
		return new String[]{call, body};
	}

	private void requestFitMap() {
		if (mapView == null || mapFitPending)
			return;
		mapFitPending = true;
		mapView.post(this::fitMapBounds);
	}

	private void fitMapBounds() {
		if (mapView == null)
			return;
		try {
			if (mapView.getWidth() <= 0 || mapView.getHeight() <= 0) {
				mapView.post(this::fitMapBounds);
				return;
			}
			mapFitPending = false;
			if (pendingMapFocus != null) {
				mapView.getController().setZoom(15.0);
				mapView.getController().setCenter(pendingMapFocus);
				pendingMapFocus = null;
				return;
			}
			if (latestPositions.isEmpty()) {
				mapView.getController().setZoom(6.0);
				mapView.getController().setCenter(new GeoPoint(55.7558, 37.6173));
				return;
			}
			List<GeoPoint> points = new ArrayList<>(latestPositions.values());
			if (points.size() == 1) {
				mapView.getController().setZoom(15.0);
				mapView.getController().setCenter(points.get(0));
			} else {
				BoundingBox box = BoundingBox.fromGeoPoints(points).increaseByScale(1.2f);
				if (box.getLatitudeSpan() > 0 && box.getLongitudeSpan() > 0)
					mapView.zoomToBoundingBox(box, false);
				else {
					mapView.getController().setZoom(15.0);
					mapView.getController().setCenter(points.get(0));
				}
			}
		} catch (Exception e) {
			mapFitPending = false;
		}
	}

	private void focusMapOnPoint(double lat, double lon) {
		if (mapView == null)
			return;
		pendingMapFocus = new GeoPoint(lat, lon);
		mapView.getOverlays().clear();
		addMapMarker(pendingMapFocus, null, null);
		requestFitMap();
	}

	private void addMapMarker(GeoPoint point, String title, String body) {
		Marker marker = new Marker(mapView);
		marker.setPosition(point);
		marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
		String coords = String.format(Locale.US, "%.6f, %.6f", point.getLatitude(), point.getLongitude());
		StringBuilder snippet = new StringBuilder(coords)
			.append("\n").append(getString(R.string.locator)).append(": ")
			.append(GpsUtils.locator(point.getLatitude(), point.getLongitude()));
		if (myPosition != null) {
			int az = (int) Math.round(GpsUtils.bearing(myPosition.getLatitude(), myPosition.getLongitude(),
				point.getLatitude(), point.getLongitude()));
			snippet.append("\n").append(getString(R.string.azimuth, az));
		}
		if (body != null && !body.isEmpty())
			snippet.insert(0, body + "\n");
		if (title == null || title.isEmpty())
			marker.setTitle(coords);
		else
			marker.setTitle(title);
		marker.setSnippet(snippet.toString());
		mapView.getOverlays().add(marker);
	}

	private void addMyPositionMarker() {
		Marker marker = new Marker(mapView);
		marker.setPosition(myPosition);
		marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
		String myTitle = callSign != null && !callSign.trim().isEmpty() ? callSign.trim() : getString(R.string.my_position);
		marker.setTitle(myTitle);
		String coords = String.format(Locale.US, "%.6f, %.6f", myPosition.getLatitude(), myPosition.getLongitude());
		marker.setSnippet(coords + "\n" + getString(R.string.locator) + ": "
			+ GpsUtils.locator(myPosition.getLatitude(), myPosition.getLongitude()));
		Drawable icon = ContextCompat.getDrawable(this, R.drawable.ic_baseline_my_location_24);
		if (icon != null) {
			icon = icon.mutate();
			icon.setTint(ContextCompat.getColor(this, R.color.my_location));
			marker.setIcon(icon);
		}
		mapView.getOverlays().add(marker);
	}

	private void handleInsets() {
		ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
			Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
			return insets;
		});
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		String message = extractIntent(intent);
		if (message != null)
			focusInput(message);
	}

	private String extractIntent(Intent intent) {
		String action = intent.getAction();
		if (action == null)
			return null;
		if (!action.equals(Intent.ACTION_SEND))
			return null;
		String type = intent.getType();
		if (type == null)
			return null;
		if (!type.equals("text/plain"))
			return null;
		return intent.getStringExtra(Intent.EXTRA_TEXT);
	}

	private void setNoiseSymbols(int newNoiseSymbols) {
		if (noiseSymbols == newNoiseSymbols)
			return;
		noiseSymbols = newNoiseSymbols;
		updateNoiseSymbolsMenu();
	}

	private void updateNoiseSymbolsMenu() {
		switch (noiseSymbols) {
			case 0:
				menu.findItem(R.id.action_disable_noise).setChecked(true);
				break;
			case 1:
				menu.findItem(R.id.action_set_noise_quarter_second).setChecked(true);
				break;
			case 3:
				menu.findItem(R.id.action_set_noise_half_second).setChecked(true);
				break;
			case 6:
				menu.findItem(R.id.action_set_noise_one_second).setChecked(true);
				break;
			case 11:
				menu.findItem(R.id.action_set_noise_two_seconds).setChecked(true);
				break;
			case 22:
				menu.findItem(R.id.action_set_noise_four_seconds).setChecked(true);
				break;
		}
	}

	private void setRepeaterDelay(int newRepeaterDelay) {
		if (repeaterDelay == newRepeaterDelay)
			return;
		repeaterDelay = newRepeaterDelay;
		updateRepeaterDelayMenu();
	}

	private void updateRepeaterDelayMenu() {
		switch (repeaterDelay) {
			case 0:
				menu.findItem(R.id.action_set_repeater_no_delay).setChecked(true);
				break;
			case 1:
				menu.findItem(R.id.action_set_repeater_delay_one_second).setChecked(true);
				break;
			case 2:
				menu.findItem(R.id.action_set_repeater_delay_two_seconds).setChecked(true);
				break;
			case 4:
				menu.findItem(R.id.action_set_repeater_delay_four_seconds).setChecked(true);
				break;
			case 8:
				menu.findItem(R.id.action_set_repeater_delay_eight_seconds).setChecked(true);
				break;
		}
	}

	private void setRepeaterDebounce(int newRepeaterDebounce) {
		if (repeaterDebounce == newRepeaterDebounce)
			return;
		repeaterDebounce = newRepeaterDebounce;
		updateRepeaterDebounceMenu();
	}

	private void updateRepeaterDebounceMenu() {
		switch (repeaterDebounce) {
			case 0:
				menu.findItem(R.id.action_set_repeater_allow_bouncing).setChecked(true);
				break;
			case 15:
				menu.findItem(R.id.action_set_repeater_debounce_quarter_minute).setChecked(true);
				break;
			case 30:
				menu.findItem(R.id.action_set_repeater_debounce_half_minute).setChecked(true);
				break;
			case 60:
				menu.findItem(R.id.action_set_repeater_debounce_one_minute).setChecked(true);
				break;
			case 120:
				menu.findItem(R.id.action_set_repeater_debounce_two_minutes).setChecked(true);
				break;
		}
	}

	private void setFancyHeader(boolean newFancyHeader) {
		if (fancyHeader == newFancyHeader)
			return;
		fancyHeader = newFancyHeader;
		updateFancyHeaderMenu();
	}

	private void updateFancyHeaderMenu() {
		if (fancyHeader)
			menu.findItem(R.id.action_enable_fancy_header).setChecked(true);
		else
			menu.findItem(R.id.action_disable_fancy_header).setChecked(true);
	}

	private void setRepeaterMode(boolean newRepeaterMode) {
		if (repeaterMode == newRepeaterMode)
			return;
		repeaterMode = newRepeaterMode;
		updateRepeaterModeMenu();
	}

	private void updateRepeaterModeMenu() {
		if (repeaterMode)
			menu.findItem(R.id.action_enable_repeater_mode).setChecked(true);
		else
			menu.findItem(R.id.action_disable_repeater_mode).setChecked(true);
	}

	private void setOutputRate(int newSampleRate) {
		if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
			return;
		if (outputRate == newSampleRate)
			return;
		outputRate = newSampleRate;
		updateOutputRateMenu();
		initAudioTrack();
	}

	private void updateOutputRateMenu() {
		switch (outputRate) {
			case 8000:
				menu.findItem(R.id.action_set_output_rate_8000).setChecked(true);
				break;
			case 16000:
				menu.findItem(R.id.action_set_output_rate_16000).setChecked(true);
				break;
			case 32000:
				menu.findItem(R.id.action_set_output_rate_32000).setChecked(true);
				break;
			case 44100:
				menu.findItem(R.id.action_set_output_rate_44100).setChecked(true);
				break;
			case 48000:
				menu.findItem(R.id.action_set_output_rate_48000).setChecked(true);
				break;
		}
	}

	private void updateRecordRateMenu() {
		switch (recordRate) {
			case 8000:
				menu.findItem(R.id.action_set_record_rate_8000).setChecked(true);
				break;
			case 16000:
				menu.findItem(R.id.action_set_record_rate_16000).setChecked(true);
				break;
			case 32000:
				menu.findItem(R.id.action_set_record_rate_32000).setChecked(true);
				break;
			case 44100:
				menu.findItem(R.id.action_set_record_rate_44100).setChecked(true);
				break;
			case 48000:
				menu.findItem(R.id.action_set_record_rate_48000).setChecked(true);
				break;
		}
	}

	private void updateRecordChannelMenu() {
		switch (recordChannel) {
			case 0:
				menu.findItem(R.id.action_set_record_channel_default).setChecked(true);
				break;
			case 1:
				menu.findItem(R.id.action_set_record_channel_first).setChecked(true);
				break;
			case 2:
				menu.findItem(R.id.action_set_record_channel_second).setChecked(true);
				break;
			case 3:
				menu.findItem(R.id.action_set_record_channel_summation).setChecked(true);
				break;
			case 4:
				menu.findItem(R.id.action_set_record_channel_analytic).setChecked(true);
				break;
		}
	}

	private void updateAudioSourceMenu() {
		switch (audioSource) {
			case MediaRecorder.AudioSource.DEFAULT:
				menu.findItem(R.id.action_set_source_default).setChecked(true);
				break;
			case MediaRecorder.AudioSource.MIC:
				menu.findItem(R.id.action_set_source_microphone).setChecked(true);
				break;
			case MediaRecorder.AudioSource.CAMCORDER:
				menu.findItem(R.id.action_set_source_camcorder).setChecked(true);
				break;
			case MediaRecorder.AudioSource.VOICE_RECOGNITION:
				menu.findItem(R.id.action_set_source_voice_recognition).setChecked(true);
				break;
			case MediaRecorder.AudioSource.UNPROCESSED:
				menu.findItem(R.id.action_set_source_unprocessed).setChecked(true);
				break;
		}
	}

	private void setOutputChannel(int newChannelSelect) {
		if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
			return;
		if (outputChannel == newChannelSelect)
			return;
		outputChannel = newChannelSelect;
		updateOutputChannelMenu();
		initAudioTrack();
	}

	private void updateOutputChannelMenu() {
		switch (outputChannel) {
			case 0:
				menu.findItem(R.id.action_set_output_channel_default).setChecked(true);
				break;
			case 1:
				menu.findItem(R.id.action_set_output_channel_first).setChecked(true);
				break;
			case 2:
				menu.findItem(R.id.action_set_output_channel_second).setChecked(true);
				break;
			case 4:
				menu.findItem(R.id.action_set_output_channel_analytic).setChecked(true);
				break;
		}
	}

	private void updateMonitorMenu() {
		MenuItem toggle = menu.findItem(R.id.action_toggle_background_monitor);
		if (toggle != null) {
			int tint = backgroundMonitor ? ContextCompat.getColor(this, R.color.tint) : ContextCompat.getColor(this, R.color.gray);
			MenuItemCompat.setIconTintList(toggle, ColorStateList.valueOf(tint));
		}
		menu.findItem(meshRelay ? R.id.action_enable_mesh_relay : R.id.action_disable_mesh_relay).setChecked(true);
		menu.findItem(notifySoundEnabled ? R.id.action_enable_notify_sound : R.id.action_disable_notify_sound).setChecked(true);
		menu.findItem(attachLocation ? R.id.action_enable_attach_location : R.id.action_disable_attach_location).setChecked(true);
		menu.findItem(positionBeacon ? R.id.action_enable_position_beacon : R.id.action_disable_position_beacon).setChecked(true);
		menu.findItem(R.id.action_set_notify_device_default).setChecked(notifyDevice == Notifier.DEVICE_DEFAULT);
		menu.findItem(R.id.action_set_notify_device_speaker).setChecked(notifyDevice == Notifier.DEVICE_SPEAKER);
		menu.findItem(R.id.action_set_notify_device_bluetooth).setChecked(notifyDevice == Notifier.DEVICE_BLUETOOTH);
		menu.findItem(R.id.action_set_notify_device_wired).setChecked(notifyDevice == Notifier.DEVICE_WIRED);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.menu_main, menu);
		menu.findItem(R.id.action_set_source_unprocessed).setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
		this.menu = menu;
		messages.setMyCall(callSign);
		updateOutputRateMenu();
		updateOutputChannelMenu();
		updateRecordRateMenu();
		updateRecordChannelMenu();
		updateAudioSourceMenu();
		updateNoiseSymbolsMenu();
		updateRepeaterDelayMenu();
		updateRepeaterDebounceMenu();
		updateFancyHeaderMenu();
		updateRepeaterModeMenu();
		updateMonitorMenu();
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.action_ping) {
			transmitMessage("");
			return true;
		}
		if (id == R.id.action_delete_messages) {
			if (messages.getCount() > 0)
				deleteMessages();
			return true;
		}
		if (id == R.id.action_set_output_rate_8000) { setOutputRate(8000); return true; }
		if (id == R.id.action_set_output_rate_16000) { setOutputRate(16000); return true; }
		if (id == R.id.action_set_output_rate_32000) { setOutputRate(32000); return true; }
		if (id == R.id.action_set_output_rate_44100) { setOutputRate(44100); return true; }
		if (id == R.id.action_set_output_rate_48000) { setOutputRate(48000); return true; }
		if (id == R.id.action_set_output_channel_default) { setOutputChannel(0); return true; }
		if (id == R.id.action_set_output_channel_first) { setOutputChannel(1); return true; }
		if (id == R.id.action_set_output_channel_second) { setOutputChannel(2); return true; }
		if (id == R.id.action_set_output_channel_analytic) { setOutputChannel(4); return true; }
		if (id == R.id.action_set_record_rate_8000) { setRecordRate(8000); return true; }
		if (id == R.id.action_set_record_rate_16000) { setRecordRate(16000); return true; }
		if (id == R.id.action_set_record_rate_32000) { setRecordRate(32000); return true; }
		if (id == R.id.action_set_record_rate_44100) { setRecordRate(44100); return true; }
		if (id == R.id.action_set_record_rate_48000) { setRecordRate(48000); return true; }
		if (id == R.id.action_set_record_channel_default) { setRecordChannel(0); return true; }
		if (id == R.id.action_set_record_channel_first) { setRecordChannel(1); return true; }
		if (id == R.id.action_set_record_channel_second) { setRecordChannel(2); return true; }
		if (id == R.id.action_set_record_channel_summation) { setRecordChannel(3); return true; }
		if (id == R.id.action_set_record_channel_analytic) { setRecordChannel(4); return true; }
		if (id == R.id.action_set_source_default) { setAudioSource(MediaRecorder.AudioSource.DEFAULT); return true; }
		if (id == R.id.action_set_source_microphone) { setAudioSource(MediaRecorder.AudioSource.MIC); return true; }
		if (id == R.id.action_set_source_camcorder) { setAudioSource(MediaRecorder.AudioSource.CAMCORDER); return true; }
		if (id == R.id.action_set_source_voice_recognition) { setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION); return true; }
		if (id == R.id.action_set_source_unprocessed) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				setAudioSource(MediaRecorder.AudioSource.UNPROCESSED);
				return true;
			}
			return false;
		}
		if (id == R.id.action_show_spectrum) { spectrumAnalyzer(); return true; }
		if (id == R.id.action_edit_call_sign) { editCallSign(); return true; }
		if (id == R.id.action_set_carrier_frequency) { setCarrierFrequency(); return true; }
		if (id == R.id.action_disable_noise) { setNoiseSymbols(0); return true; }
		if (id == R.id.action_set_noise_quarter_second) { setNoiseSymbols(1); return true; }
		if (id == R.id.action_set_noise_half_second) { setNoiseSymbols(3); return true; }
		if (id == R.id.action_set_noise_one_second) { setNoiseSymbols(6); return true; }
		if (id == R.id.action_set_noise_two_seconds) { setNoiseSymbols(11); return true; }
		if (id == R.id.action_set_noise_four_seconds) { setNoiseSymbols(22); return true; }
		if (id == R.id.action_set_repeater_no_delay) { setRepeaterDelay(0); return true; }
		if (id == R.id.action_set_repeater_delay_one_second) { setRepeaterDelay(1); return true; }
		if (id == R.id.action_set_repeater_delay_two_seconds) { setRepeaterDelay(2); return true; }
		if (id == R.id.action_set_repeater_delay_four_seconds) { setRepeaterDelay(4); return true; }
		if (id == R.id.action_set_repeater_delay_eight_seconds) { setRepeaterDelay(8); return true; }
		if (id == R.id.action_set_repeater_allow_bouncing) { setRepeaterDebounce(0); return true; }
		if (id == R.id.action_set_repeater_debounce_quarter_minute) { setRepeaterDebounce(15); return true; }
		if (id == R.id.action_set_repeater_debounce_half_minute) { setRepeaterDebounce(30); return true; }
		if (id == R.id.action_set_repeater_debounce_one_minute) { setRepeaterDebounce(60); return true; }
		if (id == R.id.action_set_repeater_debounce_two_minutes) { setRepeaterDebounce(120); return true; }
		if (id == R.id.action_enable_fancy_header) { setFancyHeader(true); return true; }
		if (id == R.id.action_disable_fancy_header) { setFancyHeader(false); return true; }
		if (id == R.id.action_enable_repeater_mode) { setRepeaterMode(true); return true; }
		if (id == R.id.action_disable_repeater_mode) { setRepeaterMode(false); return true; }
		if (id == R.id.action_enable_night_mode) { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES); return true; }
		if (id == R.id.action_disable_night_mode) { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO); return true; }
		if (id == R.id.action_force_quit) { forcedQuit(); return true; }
		if (id == R.id.action_privacy_policy) { showTextPage(getString(R.string.privacy_policy), getString(R.string.privacy_policy_text)); return true; }
		if (id == R.id.action_about) { showTextPage(getString(R.string.about), getString(R.string.about_text, BuildConfig.VERSION_NAME)); return true; }
		if (id == R.id.action_toggle_background_monitor) { toggleBackgroundMonitor(); return true; }
		if (id == R.id.action_enable_mesh_relay) { meshRelay = true; storeSettings(); updateMonitorMenu(); return true; }
		if (id == R.id.action_disable_mesh_relay) { meshRelay = false; storeSettings(); updateMonitorMenu(); return true; }
		if (id == R.id.action_enable_notify_sound) { notifySoundEnabled = true; storeSettings(); updateMonitorMenu(); return true; }
		if (id == R.id.action_disable_notify_sound) { notifySoundEnabled = false; storeSettings(); updateMonitorMenu(); return true; }
		if (id == R.id.action_set_notify_device_default) { notifyDevice = Notifier.DEVICE_DEFAULT; storeSettings(); updateMonitorMenu(); return true; }
		if (id == R.id.action_set_notify_device_speaker) { notifyDevice = Notifier.DEVICE_SPEAKER; storeSettings(); updateMonitorMenu(); return true; }
		if (id == R.id.action_set_notify_device_bluetooth) { notifyDevice = Notifier.DEVICE_BLUETOOTH; storeSettings(); updateMonitorMenu(); return true; }
		if (id == R.id.action_set_notify_device_wired) { notifyDevice = Notifier.DEVICE_WIRED; storeSettings(); updateMonitorMenu(); return true; }
		if (id == R.id.action_enable_attach_location) { attachLocation = true; storeSettings(); updateMonitorMenu(); return true; }
		if (id == R.id.action_disable_attach_location) { attachLocation = false; storeSettings(); updateMonitorMenu(); return true; }
		if (id == R.id.action_enable_position_beacon) { setPositionBeacon(true); return true; }
		if (id == R.id.action_disable_position_beacon) { setPositionBeacon(false); return true; }
		if (id == R.id.action_fm_radio_link) { applyFmRadioProfile(); return true; }
		if (id == R.id.action_ssb_radio_link) { applySsbRadioProfile(); return true; }
		if (id == R.id.action_open_map) { bottomNav.setSelectedItemId(R.id.nav_map); return true; }
		if (id == R.id.action_map_tiles) { showMapTilesDialog(); return true; }
		return super.onOptionsItemSelected(item);
	}

	private void deleteMessages() {
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setTitle(R.string.delete_messages)
			.setMessage(R.string.delete_messages_prompt)
			.setPositiveButton(R.string.delete, (dialog, which) -> {
				messages.clear();
				SharedPreferences.Editor editor = prefs().edit();
				for (int i = 0; i < 100; ++i)
					editor.remove("m" + i);
				editor.apply();
			})
			.setNegativeButton(R.string.cancel, null)
			.show();
	}

	private void forcedQuit() {
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setTitle(R.string.force_quit)
			.setMessage(R.string.force_quit_prompt)
			.setPositiveButton(R.string.quit, (dialog, which) -> {
				storeSettings();
				if (monitorService != null)
					monitorService.shutdown();
				System.exit(0);
			})
			.setNegativeButton(R.string.cancel, null)
			.show();
	}

	private void spectrumAnalyzer() {
		View view = getLayoutInflater().inflate(R.layout.spectrum_analyzer, null);
		spectrogramView = view.findViewById(R.id.spectrogram);
		spectrumView = view.findViewById(R.id.spectrum);
		spectrumBitmap = Bitmap.createBitmap(spectrumWidth, spectrumHeight, Bitmap.Config.ARGB_8888);
		spectrogramBitmap = Bitmap.createBitmap(spectrogramWidth, spectrogramHeight, Bitmap.Config.ARGB_8888);
		spectrumView.setImageBitmap(spectrumBitmap);
		spectrogramView.setImageBitmap(spectrogramBitmap);
		spectrumPixels = new int[spectrumWidth * spectrumHeight];
		spectrogramPixels = new int[spectrogramWidth * spectrogramHeight];
		spectrumTint = ContextCompat.getColor(this, R.color.tint);
		if (monitorService != null) {
			monitorService.setShowSpectrum(true);
			monitorService.setSpectrumTint(spectrumTint);
		}
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setTitle(R.string.spectrum_analyzer);
		builder.setView(view);
		builder.setNeutralButton(R.string.close, (dialogInterface, i) -> {
			showSpectrum = false;
			if (monitorService != null)
				monitorService.setShowSpectrum(false);
		});
		builder.setOnCancelListener(dialogInterface -> {
			showSpectrum = false;
			if (monitorService != null)
				monitorService.setShowSpectrum(false);
		});
		builder.show();
		showSpectrum = true;
	}

	private void transmitMessage(String message) {
		if (message == null)
			message = "";
		final String outMessage = message;
		if (attachLocation && outMessage.length() > 0) {
			setStatus(getString(R.string.gps_obtaining), true);
			LocationHelper.fetch(this, new LocationHelper.Callback() {
				@Override
				public void onLocation(double lat, double lon) {
					String marker = GpsUtils.format(lat, lon);
					String body = outMessage + marker;
					if (body.getBytes(StandardCharsets.UTF_8).length > 170) {
						setStatus(getString(R.string.location_too_large));
						doTransmit(outMessage);
					} else {
						setStatus(getString(R.string.location_attached), true);
						doTransmit(body);
					}
				}

				@Override
				public void onFailed() {
					setStatus(getString(R.string.gps_unavailable), true);
					doTransmit(outMessage);
				}
			});
		} else {
			doTransmit(outMessage);
		}
	}

	private void doTransmit(String message) {
		if (isFinishing() || isDestroyed())
			return;
		initAudioTrack();
		if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
			return;
		stopListening();
		if (audioTrack == null) {
			setStatus(getString(R.string.audio_setup_failed), true);
			handler.postDelayed(() -> startListening(), 1500);
			return;
		}
		byte[] mesg = Arrays.copyOf(message.getBytes(StandardCharsets.UTF_8), payload.length);
		double[] loc = GpsUtils.extract(message);
		touchNode(callSign.trim(), loc != null ? loc[0] : null, loc != null ? loc[1] : null);
		if (message.length() == 0)
			addLine(callSign.trim(), getString(R.string.sent_ping));
		else
			addMessage(callSign.trim(), getString(R.string.transmitted), new String(mesg).trim());
		configureEncoder(mesg, callTerm(), carrierFrequency, noiseSymbols, fancyHeader);
		for (int i = 0; i < 5; ++i) {
			produceEncoder(outputBuffer, outputChannel);
			audioTrack.write(outputBuffer, 0, outputBuffer.length);
		}
		playTransmission();
	}

	private void playTransmission() {
		if (isDestroyed() || audioTrack == null)
			return;
		try {
			audioTrack.play();
			setStatus(getString(R.string.transmitting));
		} catch (IllegalStateException e) {
			Log.e("MainActivity", "Cannot start playback", e);
			releaseAudioTrack();
			setStatus(getString(R.string.audio_recording_error), true);
			startListening();
		}
	}

	private void repeatMessage(String call, byte[] data) {
		if (isFinishing() || isDestroyed())
			return;
		initAudioTrack();
		if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
			return;
		byte[] callBytes = Arrays.copyOf(call.getBytes(StandardCharsets.US_ASCII), 10);
		Message message = new Message(callBytes, data);
		long minDebounce = Math.max(repeaterDebounce, 10);
		Iterator<Message> iterator = repeatedMessages.iterator();
		while (iterator.hasNext()) {
			Message repeated = iterator.next();
			if (message.time - repeated.time < minDebounce) {
				if (Arrays.equals(repeated.call, message.call) && Arrays.equals(repeated.data, message.data)) {
					setStatus(getString(R.string.ignoring), true);
					return;
				}
			} else {
				iterator.remove();
			}
		}
		repeatedMessages.add(message);
		if (repeatedMessages.size() > 200)
			repeatedMessages.remove(0);
		stopListening();
		addMessage(call.trim(), getString(R.string.repeated), new String(data).trim());
		if (audioTrack == null) {
			handler.postDelayed(() -> startListening(), 1500);
			setStatus(getString(R.string.audio_setup_failed), true);
			return;
		}
		configureEncoder(data, callBytes, carrierFrequency, noiseSymbols, fancyHeader);
		for (int i = 0; i < 5; ++i) {
			produceEncoder(outputBuffer, outputChannel);
			audioTrack.write(outputBuffer, 0, outputBuffer.length);
		}
		handler.postDelayed(() -> {
			if (isDestroyed() || audioTrack == null)
				return;
			playTransmission();
		}, 1000L * repeaterDelay);
	}

	private void setInputType(ViewGroup np, int it) {
		int count = np.getChildCount();
		for (int i = 0; i < count; i++) {
			final View child = np.getChildAt(i);
			if (child instanceof ViewGroup) {
				setInputType((ViewGroup) child, it);
			} else if (child instanceof EditText) {
				EditText et = (EditText) child;
				et.setInputType(it);
				break;
			}
		}
	}

	private String[] carrierValues(int minCarrierFrequency, int maxCarrierFrequency) {
		int count = (maxCarrierFrequency - minCarrierFrequency) / 50 + 1;
		String[] values = new String[count];
		for (int i = 0; i < count; ++i)
			values[i] = String.format(Locale.US, "%d", i * 50 + minCarrierFrequency);
		return values;
	}

	private void setCarrierFrequency() {
		View view = getLayoutInflater().inflate(R.layout.carrier_frequency, null);
		NumberPicker picker = view.findViewById(R.id.carrier);
		int bandWidth = 1600;
		int maxCarrierFrequency = 3000;
		int minCarrierFrequency = outputChannel == 4 ? -maxCarrierFrequency : 1000;
		if (carrierFrequency < minCarrierFrequency || carrierFrequency > maxCarrierFrequency)
			carrierFrequency = 1500;
		picker.setMinValue(0);
		picker.setDisplayedValues(null);
		picker.setMaxValue((maxCarrierFrequency - minCarrierFrequency) / 50);
		picker.setValue((carrierFrequency - minCarrierFrequency) / 50);
		picker.setDisplayedValues(carrierValues(minCarrierFrequency, maxCarrierFrequency));
		setInputType(picker, InputType.TYPE_CLASS_NUMBER);
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setTitle(R.string.carrier_frequency);
		builder.setView(view);
		builder.setNegativeButton(R.string.cancel, null);
		builder.setPositiveButton(R.string.okay, (dialogInterface, i) -> carrierFrequency = picker.getValue() * 50 + minCarrierFrequency);
		builder.show();
	}

	private void editCallSign() {
		View view = getLayoutInflater().inflate(R.layout.call_sign, null);
		EditText edit = view.findViewById(R.id.call);
		edit.setText(callSign);
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setTitle(R.string.call_sign);
		builder.setView(view);
		builder.setNegativeButton(R.string.cancel, null);
		builder.setPositiveButton(R.string.okay, (dialogInterface, i) -> {
			callSign = edit.getText().toString();
			messages.setMyCall(callSign);
		});
		builder.show();
	}
	
	private void checkCallSign() {
    if ("ANONYMOUS".equals(callSign)) {
        editCallSign();
    }
	}
	
	private void showCrashReport() {
		final String crash = CrashLogger.takeLastCrash(this);
		if (crash == null || crash.isEmpty())
			return;
		handler.postDelayed(() -> {
			if (isFinishing() || isDestroyed())
				return;
			ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
				.setTitle(R.string.crash_dialog_title)
				.setMessage(crash)
				.setNeutralButton(R.string.copy_crash, (dialog, which) -> {
					if (cm != null)
						cm.setPrimaryClip(ClipData.newPlainText("crash", crash));
				})
				.setNegativeButton(R.string.close, null)
				.show();
		}, 500);
	}

	private void showTextPage(String title, String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setNeutralButton(R.string.close, null);
		builder.setTitle(title);
		builder.setMessage(message);
		builder.show();
	}

	private void openNavigator(double lat, double lon) {
		Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon));
		try {
			startActivity(intent);
		} catch (Exception ignored) {
		}
	}

	private void showMapTilesDialog() {
		View view = getLayoutInflater().inflate(R.layout.map_tiles, null);
		RadioGroup group = view.findViewById(R.id.tile_source_group);
		EditText urlEdit = view.findViewById(R.id.tile_url);
		group.check(tileIndex == 1 ? R.id.tile_topo : tileIndex == 2 ? R.id.tile_custom : R.id.tile_osm);
		urlEdit.setText(tileUrl != null ? tileUrl : "");
		urlEdit.setEnabled(tileIndex == 2);
		group.setOnCheckedChangeListener((rg, checkedId) -> urlEdit.setEnabled(checkedId == R.id.tile_custom));
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setTitle(R.string.map_tiles);
		builder.setView(view);
		builder.setNegativeButton(R.string.cancel, null);
		builder.setPositiveButton(R.string.okay, (dialogInterface, i) -> {
			int checkedId = group.getCheckedRadioButtonId();
			if (checkedId == R.id.tile_topo)
				tileIndex = 1;
			else if (checkedId == R.id.tile_custom)
				tileIndex = 2;
			else
				tileIndex = 0;
			tileUrl = urlEdit.getText().toString();
			storeSettings();
		});
		builder.show();
	}

	@Override
	protected void onResume() {
		super.onResume();
		activityResumed = true;
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
			if (!monitorServiceStarted)
				startMonitorService();
			else if (monitorService != null)
				monitorService.resume();
		}
		if (selectedTab == 2)
			resumeMap();
	}

	@Override
	protected void onPause() {
		activityResumed = false;
		pauseMap();
		if (!backgroundMonitor) {
			if (monitorService != null)
				monitorService.shutdown();
			stopService(new Intent(this, MonitorService.class));
			unbindMonitor();
		}
		storeSettings();
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		beaconHandler.removeCallbacks(beaconRunnable);
		if (handler != null)
			handler.removeCallbacksAndMessages(null);
		if (mapView != null) {
			try {
				mapView.onDetach();
			} catch (Exception ignored) {
			}
			mapView = null;
		}
		unbindMonitor();
		releaseAudioTrack();
		destroyEncoder();
		super.onDestroy();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode != PERMISSION_ID)
			return;
		// Recheck the actual permission state: a cancelled dialog may return empty arrays.
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
			startMonitorService();
	}
}
