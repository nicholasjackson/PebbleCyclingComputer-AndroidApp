package com.njackson.gps;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GpsStatus.NmeaListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;

import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.njackson.*;
import com.njackson.activities.MainActivity;
import com.njackson.application.modules.PebbleBikeApplication;
import com.njackson.events.GPSService.ChangeRefreshInterval;
import com.njackson.events.GPSService.ResetGPSState;
import com.njackson.events.GPSService.CurrentState;
import com.njackson.events.GPSService.NewLocation;
import com.squareup.otto.Bus;
import com.squareup.otto.Subscribe;

import javax.inject.Inject;

import fr.jayps.android.AdvancedLocation;

/**
 * Created with IntelliJ IDEA.
 * User: njackson
 * Date: 23/05/2013
 * Time: 13:30
 * To change this template use File | Settings | File Templates.
 */
public class GPSService extends Service {

    private static final String TAG = "PB-GPSService";

    @Inject LocationManager _locationMgr;
    @Inject SharedPreferences _sharedPreferences;
    @Inject Bus _bus;

    Location firstLocation = null;
    private AdvancedLocation _advancedLocation;

    private int _refresh_interval = 1000;
    private boolean _gpsStarted = false;

    @Subscribe
    public void onResetGPSStateEvent(ResetGPSState event) {
        //stop service stopLocationUpdates();
        resetGPSStats();
    }

    @Subscribe
    public void onGPSRefreshChangeEvent(ChangeRefreshInterval event) {
        changeRefreshInterval(event.getRefreshInterval());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handleCommand(intent);
        // ensures that if the service is recycled then it is restarted with the same refresh interval
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ((PebbleBikeApplication)getApplication()).inject(this);
        _bus.register(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void onDestroy (){
        Log.d(TAG, "Stopped GPS Service");
        saveGPSStats();
        //removeServiceForeground();

        stopLocationUpdates();
    }

    private void handleCommand(Intent intent) {
        Log.d(TAG, "Started GPS Service");

        _advancedLocation = new AdvancedLocation(getApplicationContext());
        _advancedLocation.debugTagPrefix = "PB-";

        // the intent has an extra which relates to the refresh interval
        _refresh_interval = intent.getIntExtra("REFRESH_INTERVAL", 1000);

        loadGPSStats();

        // check to see if GPS is enabled
        if(checkGPSEnabled(_locationMgr)) {
            requestLocationUpdates(_refresh_interval);
        } else {
            _bus.post(new CurrentState(CurrentState.State.DISABLED)); // GPS DISABLED
        }
    }

    private boolean checkGPSEnabled(LocationManager locationMgr) {
        return locationMgr.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    // load the saved state
    private void loadGPSStats() {
        Log.d(TAG, "loadGPSStats()");

        _advancedLocation.setDistance(_sharedPreferences.getFloat("GPS_DISTANCE",0.0f));
        _advancedLocation.setElapsedTime(_sharedPreferences.getLong("GPS_ELAPSEDTIME", 0));

        try {
            _advancedLocation.setAscent(_sharedPreferences.getFloat("GPS_ASCENT", 0.0f));
        } catch (ClassCastException e) {
            _advancedLocation.setAscent(0.0);
        }

    }

    // save the state
    private void saveGPSStats() {
        SharedPreferences.Editor editor = _sharedPreferences.edit();
        editor.putFloat("GPS_DISTANCE",_advancedLocation.getDistance());
        editor.putLong("GPS_ELAPSEDTIME", _advancedLocation.getElapsedTime());
        editor.putFloat("GPS_ASCENT", (float) _advancedLocation.getAscent());
        editor.commit();
    }

    // reset the saved state
    private void resetGPSStats() {
        SharedPreferences.Editor editor = _sharedPreferences.edit();
        editor.putFloat("GPS_DISTANCE",0.0f);
        editor.putLong("GPS_ELAPSEDTIME", 0);
        editor.putFloat("GPS_ASCENT", 0.0f);
        editor.commit();

        // GPS is running
        // reninit all properties
        _advancedLocation = new AdvancedLocation(getApplicationContext());
        _advancedLocation.debugTagPrefix = "PB-";
        //_advancedLocation.debugLevel = MainActivity.debug ? 2 : 0;

        loadGPSStats();
    }

    private void requestLocationUpdates(int refresh_interval) {

        _refresh_interval = refresh_interval;

        if (_gpsStarted) {
            _locationMgr.removeUpdates(_locationListener);
        }
        _locationMgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, (long)_refresh_interval, 2.0f, _locationListener);

        _gpsStarted = true;
    }

    private void stopLocationUpdates() {
        _locationMgr.removeUpdates(_locationListener);
        _locationMgr.removeNmeaListener(mNmeaListener);
    }

    private void changeRefreshInterval(int refresh_interval) {
        _refresh_interval = refresh_interval;
        requestLocationUpdates(refresh_interval);
    }

    private LocationListener _locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            _advancedLocation.onLocationChanged(location);
            broadcastLocation();
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

    NmeaListener mNmeaListener = new NmeaListener() {
        @Override
        public void onNmeaReceived(long timestamp, String nmea) {
            if (nmea.startsWith("$GPGGA")) {

                String[] strValues = nmea.split(",");

                try {
                    // Height of geoid above WGS84 ellipsoid
                    double geoid_height = Double.parseDouble(strValues[11]);
                    _advancedLocation.setGeoidHeight(geoid_height);
                    _locationMgr.removeNmeaListener(mNmeaListener);
                } catch (Exception e) {
                }
            }
        }
    };

    private SensorEventListener mSensorListener = new SensorEventListener() {

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {

        }

        @Override
        public void onSensorChanged(SensorEvent event) {

            float pressure_value = 0.0f;
            double altitude = 0.0f;

            // we register to TYPE_PRESSURE, so we don't really need this test
            if( Sensor.TYPE_PRESSURE == event.sensor.getType()) {
                pressure_value = event.values[0];
                altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure_value);
                _advancedLocation.onAltitudeChanged(altitude);

                broadcastLocation();
            }
        }
    };

    private void broadcastLocation() {
        NewLocation event = new NewLocation();

        event.setSpeed(_advancedLocation.getSpeed());
        event.setDistance(_advancedLocation.getDistance());
        event.setAvgSpeed(_advancedLocation.getAverageSpeed());
        event.setLatitude(_advancedLocation.getLatitude());
        event.setLongitude(_advancedLocation.getLongitude());
        event.setAltitude(_advancedLocation.getAltitude()); // m
        event.setAscent(_advancedLocation.getAscent()); // m
        event.setAscentRate(3600f * _advancedLocation.getAscentRate()); // in m/h
        event.setSlope(100f * _advancedLocation.getSlope()); // in %
        event.setAccuracy(_advancedLocation.getAccuracy()); // m
        event.setTime(_advancedLocation.getElapsedTime());
        //event.setXpos(_advancedLocation.get);
        //event.setYpos(ypos);
        event.setBearing(_advancedLocation.getBearing());

        _bus.post(event);
        Log.d(TAG,"New Location");
    }

    private void makeServiceForeground(String title, String text) {
        final int myID = 1000;

        //The intent to launch when the user clicks the expanded notification
        Intent i = new Intent(this, MainActivity.class);

        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendIntent = PendingIntent.getActivity(this, 0, i, 0);

        // The following code is deprecated since API 11 (Android 3.x). Notification.Builder could be used instead, but without Android 2.x compatibility 
        Notification notification = new Notification(R.drawable.ic_launcher, "Pebble Bike", System.currentTimeMillis());
        notification.setLatestEventInfo(this, title, text, pendIntent);

        notification.flags |= Notification.FLAG_NO_CLEAR;

        startForeground(myID, notification);
    }

    private void removeServiceForeground() {
        stopForeground(true);
    }

}