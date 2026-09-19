/*
Rattlegram

Utilities for formatting and parsing in-band GPS coordinates embedded in
message text. The marker format is: [GPS:lat,lon]
*/

package com.aicodix.rattlegram;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GpsUtils {

	private static final Pattern GPS_PATTERN = Pattern.compile("\\[GPS:(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)\\]");

	private GpsUtils() {
	}

	public static String format(double lat, double lon) {
		return String.format(Locale.US, "[GPS:%.6f,%.6f]", lat, lon);
	}

	public static double[] extract(String text) {
		if (text == null)
			return null;
		Matcher m = GPS_PATTERN.matcher(text);
		if (m.find()) {
			try {
				double lat = Double.parseDouble(m.group(1));
                double lon = Double.parseDouble(m.group(2));
                if (!Double.isNaN(lat) && !Double.isInfinite(lat) && !Double.isNaN(lon)
                        && !Double.isInfinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180)
                    return new double[]{lat, lon};
			} catch (NumberFormatException ignored) {
			}
		}
		return null;
	}

	public static String locator(double lat, double lon) {
		double lonPortion = lon + 180.0;
		double latPortion = lat + 90.0;
		char a = (char) ('A' + (int) (lonPortion / 20.0));
		char b = (char) ('A' + (int) (latPortion / 10.0));
		lonPortion -= (int) (lonPortion / 20.0) * 20.0;
		latPortion -= (int) (latPortion / 10.0) * 10.0;
		char c = (char) ('0' + (int) (lonPortion / 2.0));
		char d = (char) ('0' + (int) latPortion);
		lonPortion -= (int) (lonPortion / 2.0) * 2.0;
		latPortion -= (int) latPortion;
		char e = (char) ('A' + (int) (lonPortion / (2.0 / 24.0)));
		char f = (char) ('A' + (int) (latPortion / (1.0 / 24.0)));
		return String.valueOf(new char[]{a, b, c, d, e, f});
	}

	public static double bearing(double lat1, double lon1, double lat2, double lon2) {
		double lat1r = Math.toRadians(lat1);
		double lat2r = Math.toRadians(lat2);
		double dLon = Math.toRadians(lon2 - lon1);
		double y = Math.sin(dLon) * Math.cos(lat2r);
		double x = Math.cos(lat1r) * Math.sin(lat2r)
			- Math.sin(lat1r) * Math.cos(lat2r) * Math.cos(dLon);
		double brng = Math.toDegrees(Math.atan2(y, x));
		return (brng + 360.0) % 360.0;
	}
}