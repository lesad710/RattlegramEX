/*
Rattlegram

MapActivity renders received GPS coordinates on an osmdroid MapView and
supports configurable custom tile sources (OpenStreetMap, OpenTopoMap,
or a user-specified tile URL) including offline tile caching.
*/

package com.aicodix.rattlegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MapActivity extends AppCompatActivity {

	private MapView mapView;

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		Context context = getApplicationContext();
		SharedPreferences pref = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
		Configuration.getInstance().load(context, pref);
		Configuration.getInstance().setOsmdroidBasePath(getFilesDir());
		Configuration.getInstance().setUserAgentValue(context.getPackageName());
		mapView = new MapView(this);
		setContentView(mapView);
		mapView.setMultiTouchControls(true);
		applyTileSource(pref);
		double[] lats = getIntent().getDoubleArrayExtra("lats");
		double[] lons = getIntent().getDoubleArrayExtra("lons");
		double singleLat = getIntent().getDoubleExtra("lat", Double.NaN);
		double singleLon = getIntent().getDoubleExtra("lon", Double.NaN);
		List<GeoPoint> points = new ArrayList<>();
		if (lats != null && lons != null) {
			int count = Math.min(lats.length, lons.length);
			for (int i = 0; i < count; ++i) {
				GeoPoint point = new GeoPoint(lats[i], lons[i]);
				addMarker(point);
				points.add(point);
			}
		} else if (!Double.isNaN(singleLat) && !Double.isNaN(singleLon)) {
			GeoPoint point = new GeoPoint(singleLat, singleLon);
			addMarker(point);
			points.add(point);
		}
		if (!points.isEmpty()) {
			if (points.size() == 1) {
				mapView.getController().setZoom(15.0);
				mapView.getController().setCenter(points.get(0));
			} else {
				BoundingBox box = BoundingBox.fromGeoPoints(points);
				mapView.zoomToBoundingBox(box.increaseByScale(1.2f), false);
			}
		} else {
			mapView.getController().setZoom(1.0);
			mapView.getController().setCenter(new GeoPoint(0.0, 0.0));
		}
	}

	private void addMarker(GeoPoint point) {
		Marker marker = new Marker(mapView);
		marker.setPosition(point);
		marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
		marker.setSnippet(String.format(Locale.US, "%.6f, %.6f", point.getLatitude(), point.getLongitude()));
		mapView.getOverlays().add(marker);
	}

	private void applyTileSource(SharedPreferences pref) {
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
		String[] urls;
		if (base.contains("{s}")) {
			String domain = base.substring(base.indexOf("://") + 3);
			String protocol = base.substring(0, base.indexOf("://") + 3);
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
		return new XYTileSource("CustomTiles",
			0, 19, 256, ext, urls);
	}

	@Override
	protected void onResume() {
		super.onResume();
		mapView.onResume();
	}

	@Override
	protected void onPause() {
		mapView.onPause();
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		mapView.onDetach();
		super.onDestroy();
	}
}