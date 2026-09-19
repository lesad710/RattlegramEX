/*
Rattlegram

Minimal location helper: prefers a recent last-known GPS fix and falls back
to a single GPS update with a timeout, then to the network provider.
*/

package com.aicodix.rattlegram;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

public final class LocationHelper {

	public interface Callback {
		void onLocation(double lat, double lon);
		void onFailed();
	}

	private static final long MAX_AGE = 120000;

	private LocationHelper() {
	}

	public static void fetch(Context context, Callback callback) {
		if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
			!= PackageManager.PERMISSION_GRANTED) {
			callback.onFailed();
			return;
		}
		LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
		if (manager == null) {
			callback.onFailed();
			return;
		}
		try {
			Location last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
			if (last != null && System.currentTimeMillis() - last.getTime() < MAX_AGE) {
				callback.onLocation(last.getLatitude(), last.getLongitude());
				return;
			}
		} catch (SecurityException | IllegalArgumentException ignored) {
		}
		Handler handler = new Handler(Looper.getMainLooper());
		final boolean[] done = {false};
		LocationListener listener = new LocationListener() {
			@Override
			public void onLocationChanged(Location location) {
				if (done[0])
					return;
				done[0] = true;
				handler.removeCallbacksAndMessages(null);
				try {
					manager.removeUpdates(this);
				} catch (SecurityException | IllegalArgumentException ignored) {
				}
				callback.onLocation(location.getLatitude(), location.getLongitude());
			}

			@Override
			public void onStatusChanged(String provider, int status, Bundle extras) {
			}

			@Override
			public void onProviderEnabled(String provider) {
			}

			@Override
			public void onProviderDisabled(String provider) {
			}
		};
		Runnable timeout = () -> {
			if (done[0])
				return;
			done[0] = true;
			try {
				manager.removeUpdates(listener);
			} catch (SecurityException | IllegalArgumentException ignored) {
			}
			try {
				Location network = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
				if (network != null && System.currentTimeMillis() - network.getTime() < MAX_AGE) {
					callback.onLocation(network.getLatitude(), network.getLongitude());
					return;
				}
			} catch (SecurityException | IllegalArgumentException ignored) {
			}
			callback.onFailed();
		};
		try {
			manager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, handler.getLooper());
		} catch (Exception e) {
			timeout.run();
			return;
		}
		handler.postDelayed(timeout, 20000);
	}
}